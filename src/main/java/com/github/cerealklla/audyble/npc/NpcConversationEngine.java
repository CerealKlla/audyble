package com.github.cerealklla.audyble.npc;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;

import com.github.cerealklla.audyble.AudybleMod;
import com.github.cerealklla.audyble.bridge.SettlemyntsIdentityBridge;
import com.github.cerealklla.audyble.config.AudybleConfig;
import com.github.cerealklla.nuclyus.api.Nuclyus;
import com.github.cerealklla.nuclyus.llm.OllamaClient;
import com.github.cerealklla.nuclyus.llm.WorldLore;

import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.packets.MicrophonePacket;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Phase 5 (lowest priority, design-document.md's own Phase 5 section) -- orchestrates a full
 * real spoken conversation with an NPC: a nearby player's speech is captured via SVC's own
 * {@link MicrophonePacketEvent}, locally transcribed (Whisper), answered (Ollama), synthesized
 * (Kokoro), and played back through the NPC's own {@link EntityAudioChannel} -- so the same
 * per-listener treatment pipeline Phases 2-4 built applies to the NPC's "voice" like any other
 * speaker once it reaches a listener (see design-document.md section 15's "Assigned to Audyble"
 * note). Off by default ({@link AudybleConfig#NPC_CONVERSATION_ENABLED}) until an administrator
 * configures and starts the three external services this depends on -- see context/decisions.md's
 * open items for what's still unverified here.
 *
 * <p>Instantiated once by {@link #init()} (called from {@code AudybleMod}'s constructor) and reached
 * from {@link com.github.cerealklla.audyble.voice.AudyblePlugin} (SVC's own separately-discovered
 * plugin instance) via the static {@link #instance()} accessor -- the two are constructed through
 * completely different mechanisms (NeoForge's mod constructor vs. SVC's annotation scanning) with no
 * natural way to hand one a reference to the other at construction time.</p>
 */
public final class NpcConversationEngine {

    private static volatile NpcConversationEngine INSTANCE;

    public static void init() {
        NpcConversationEngine engine = new NpcConversationEngine();
        INSTANCE = engine;
        NeoForge.EVENT_BUS.register(engine);
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> register(event.getDispatcher()));
    }

    public static NpcConversationEngine instance() {
        return INSTANCE;
    }

    private static final int MAX_HISTORY_TURNS = 10;
    private static final int TARGET_SAMPLE_RATE = 48000;
    private static final int WHISPER_SAMPLE_RATE = 16000;
    private static final String DEFAULT_WORKER_SYSTEM_PROMPT =
            "Speak naturally and briefly in character as this settlement resident, in a way consistent "
                    + "with your backstory and the world around you. Never break character or mention "
                    + "being an AI, a game, or anything outside this world.";

    /** One (NPC entity, player) conversation thread -- Phase 5b Milestone 4's real per-player keying. */
    private record ConversationKey(UUID npcEntityId, UUID playerId) {
    }

    /**
     * In-memory rolling state for one {@link ConversationKey} -- deliberately as disposable as the
     * old npc-only map was (lost on restart is fine, matching {@code ConversationalNpcConfig}'s own
     * doc). {@code initialIdentity} is resolved once, at the conversation's first turn, and reused as
     * the stable (ownerId, role) handle for every later relationship read/write in this thread --
     * {@code null} if Settlemynts wasn't loaded (or had no opinion) at that moment, in which case no
     * relationship memory is ever read or written for this conversation (see {@link
     * SettlemyntsIdentityBridge}'s own null-handle no-op convention).
     */
    private static final class ConversationState {
        final SettlemyntsIdentityBridge.IdentitySnapshot initialIdentity;
        final Deque<OllamaClient.Turn> history = new ArrayDeque<>();
        volatile long lastTurnAtMillis = System.currentTimeMillis();

        ConversationState(SettlemyntsIdentityBridge.IdentitySnapshot initialIdentity) {
            this.initialIdentity = initialIdentity;
        }
    }

    private volatile VoicechatServerApi api;
    private volatile MinecraftServer server;
    private final Map<UUID, NpcVoiceSession> activeSessions = new ConcurrentHashMap<>();
    private final Map<ConversationKey, ConversationState> conversations = new ConcurrentHashMap<>();
    private final ExecutorService pipelineExecutor = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "audyble-npc-conversation"));

    public void onServerStarted(VoicechatServerApi api) {
        this.api = api;
    }

    /**
     * {@code pipelineExecutor}'s thread is never a daemon thread (the default {@link
     * Executors#newSingleThreadExecutor} shape) and was never shut down anywhere -- confirmed live
     * 2026-11-04 as the real cause of the dedicated server hanging for the full 60s graceful-stop
     * window and needing a force-kill, twice in a row, right after Ollama's own HTTP timeout was
     * deliberately widened to 120s (a slow/hung in-flight Ollama call now blocks this thread far
     * longer, and nothing was ever telling it to stop). {@code shutdownNow()} interrupts any
     * in-flight HTTP call immediately rather than waiting for it to finish or time out on its own.
     * The matching shutdown for NPC birth generation's own executor moved to Settlemynts alongside
     * {@code npc.NpcIdentityPrompting} itself, 2026-11-05.
     */
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        pipelineExecutor.shutdownNow();
    }

    /** Called by {@link com.github.cerealklla.audyble.voice.AudyblePlugin} for every microphone packet. */
    public void onMicrophonePacket(MicrophonePacketEvent event) {
        if (!AudybleConfig.NPC_CONVERSATION_ENABLED.get()) {
            return;
        }
        VoicechatConnection sender = event.getSenderConnection();
        if (sender == null || !(sender.getPlayer().getPlayer() instanceof ServerPlayer speaker)) {
            return;
        }

        Entity npc = findNearestConversationalNpc(speaker);
        NpcVoiceSession session = activeSessions.get(speaker.getUUID());

        if (npc == null) {
            if (session != null) {
                activeSessions.remove(speaker.getUUID());
            }
            return;
        }

        MicrophonePacket packet = event.getPacket();
        if (session == null || !session.npcEntityId.equals(npc.getUUID())) {
            VoicechatServerApi currentApi = api;
            if (currentApi == null) {
                return;
            }
            session = new NpcVoiceSession(speaker.getUUID(), npc.getUUID(), currentApi.createDecoder());
            activeSessions.put(speaker.getUUID(), session);
        }
        session.append(packet.getOpusEncodedData());
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        server = event.getServer();
        if (!activeSessions.isEmpty()) {
            long now = System.currentTimeMillis();
            int timeoutMillis = AudybleConfig.NPC_SILENCE_TIMEOUT_MILLIS.get();
            List<NpcVoiceSession> finished = new ArrayList<>();
            activeSessions.entrySet().removeIf(e -> {
                if (now - e.getValue().lastPacketAtMillis > timeoutMillis) {
                    finished.add(e.getValue());
                    return true;
                }
                return false;
            });
            for (NpcVoiceSession session : finished) {
                short[] pcm48k = session.finish();
                pipelineExecutor.submit(() -> runPipeline(session.playerId, session.npcEntityId, pcm48k));
            }
        }
        if (!conversations.isEmpty()) {
            checkIdleConversations();
        }
    }

    /**
     * A conversation that's gone quiet past the (much longer than per-utterance) idle threshold is
     * "genuinely ended" -- summarize it into {@link PlayerRelationshipStore} (never a raw transcript,
     * the design doc's own explicit requirement) and drop the in-memory rolling history.
     */
    private void checkIdleConversations() {
        long now = System.currentTimeMillis();
        int idleMillis = AudybleConfig.NPC_RELATIONSHIP_SUMMARY_IDLE_MILLIS.get();
        List<Map.Entry<ConversationKey, ConversationState>> idle = new ArrayList<>();
        conversations.entrySet().removeIf(e -> {
            if (now - e.getValue().lastTurnAtMillis > idleMillis) {
                idle.add(Map.entry(e.getKey(), e.getValue()));
                return true;
            }
            return false;
        });
        for (Map.Entry<ConversationKey, ConversationState> entry : idle) {
            pipelineExecutor.submit(() -> summarizeAndPersist(entry.getKey(), entry.getValue()));
        }
    }

    private void runPipeline(UUID playerId, UUID npcEntityId, short[] pcm48k) {
        try {
            if (isTooQuietToBeSpeech(pcm48k)) {
                return;
            }
            short[] pcm16k = PcmAudio.resample(pcm48k, TARGET_SAMPLE_RATE, WHISPER_SAMPLE_RATE);
            byte[] wav16k = PcmAudio.toWav(pcm16k, WHISPER_SAMPLE_RATE);

            WhisperSttClient whisper = new WhisperSttClient(AudybleConfig.WHISPER_BASE_URL.get());
            String transcript = whisper.transcribe(wav16k);
            if (transcript.isBlank() || isNonSpeechHallucination(transcript)) {
                return;
            }
            AudybleMod.LOGGER.info("Audyble NPC heard: {}", transcript);
            respondToTranscript(playerId, npcEntityId, transcript);
        } catch (Exception e) {
            AudybleMod.LOGGER.error("Audyble NPC conversation pipeline failed", e);
        }
    }

    /**
     * Resolves the nearest conversational NPC to {@code player} (same proximity check voice capture
     * uses) and feeds {@code message} into the pipeline as if it had been spoken -- the no-microphone
     * text fallback (see {@code api.Audyble#sayToNearestNpc}). Runs on {@link #pipelineExecutor}, not
     * the calling thread, same as the voice path.
     *
     * @return {@code false} if no conversational NPC is currently in range.
     */
    public boolean sayToNearestNpc(ServerPlayer player, String message) {
        Entity npc = findNearestConversationalNpc(player);
        if (npc == null) {
            return false;
        }
        UUID npcEntityId = npc.getUUID();
        pipelineExecutor.submit(() -> {
            try {
                respondToTranscript(player.getUUID(), npcEntityId, message);
            } catch (Exception e) {
                AudybleMod.LOGGER.error("Audyble NPC conversation pipeline failed", e);
            }
        });
        return true;
    }

    /** The shared tail of the pipeline once a transcript exists, whether from Whisper (voice) or typed directly (text fallback) -- resolves the NPC's config/identity, calls the LLM, updates rolling history, synthesizes the reply, and plays it back on the NPC. A no-op (not an error) if {@code npcEntityId} no longer resolves to a real, still-conversational entity. */
    private void respondToTranscript(UUID playerId, UUID npcEntityId, String transcript) throws Exception {
        Entity npcEntity = findEntityAnywhere(npcEntityId);
        if (npcEntity == null) {
            return;
        }
        ConversationalNpcConfig npcConfig = resolveNpcConfig(npcEntity);
        if (npcConfig == null) {
            return;
        }

        ConversationKey pairKey = new ConversationKey(npcEntityId, playerId);
        ConversationState convo = conversations.computeIfAbsent(pairKey,
                k -> new ConversationState(resolveInitialIdentity(npcEntity)));

        String settlementName = resolveSettlementName(npcEntity).orElse("");
        // Re-resolved fresh every turn (not just reusing convo.initialIdentity) so a name/backstory
        // that finished async-generating mid-conversation shows up without waiting for a new
        // conversation to start -- resolveOrBeginBirth is a cheap store lookup after the first call,
        // same as the old Audyble-owned version this replaced.
        Optional<SettlemyntsIdentityBridge.IdentitySnapshot> identity = npcEntity.level() instanceof ServerLevel npcLevel
                ? SettlemyntsIdentityBridge.resolveOrBeginBirth(npcLevel, npcEntity)
                : Optional.empty();
        String npcName = identity.map(SettlemyntsIdentityBridge.IdentitySnapshot::name)
                .filter(n -> !n.isBlank()).orElse(npcConfig.characterName());
        String backstory = identity.map(SettlemyntsIdentityBridge.IdentitySnapshot::backstory).orElse("");
        String keyFacts = identity.map(SettlemyntsIdentityBridge.IdentitySnapshot::keyFactsPromptText).orElse("");
        String relationshipSummary = SettlemyntsIdentityBridge.getRelationshipSummary(convo.initialIdentity, playerId);
        String systemPrompt = buildSystemPrompt(npcName, backstory, keyFacts, relationshipSummary, npcConfig, settlementName);

        // A registered interceptor (see NpcReplyInterceptors' own doc) gets first refusal -- lets a
        // caller (e.g. Arryval's staged tutorial intro) supply a guaranteed, non-improvised reply
        // for specific turns without touching Whisper/the LLM at all, while every other turn still
        // falls through to the real conversation below exactly as before.
        String reply = null;
        MinecraftServer interceptServer = server;
        ServerPlayer playerForIntercept = interceptServer != null ? interceptServer.getPlayerList().getPlayer(playerId) : null;
        if (playerForIntercept != null) {
            for (NpcReplyInterceptor interceptor : NpcReplyInterceptors.all()) {
                Optional<String> intercepted = interceptor.tryIntercept(playerForIntercept, npcEntity, transcript);
                if (intercepted.isPresent()) {
                    reply = intercepted.get();
                    break;
                }
            }
        }
        if (reply == null) {
            reply = stripStageDirections(Nuclyus.chat(systemPrompt, List.copyOf(convo.history), transcript));
        }
        if (reply.isBlank()) {
            return;
        }

        synchronized (convo.history) {
            convo.history.addLast(new OllamaClient.Turn("user", transcript));
            convo.history.addLast(new OllamaClient.Turn("assistant", reply));
            while (convo.history.size() > MAX_HISTORY_TURNS * 2) {
                convo.history.removeFirst();
            }
        }
        convo.lastTurnAtMillis = System.currentTimeMillis();
        speakAndShow(playerId, npcEntityId, npcName, reply, identity);
    }

    /**
     * Shows the reply as chat text (explicit user request, 2026-11-06: "the text version of what
     * they say should also come through as if the NPC is typing so that players without audio can
     * follow along") and speaks it via TTS, played back positionally on the NPC -- the common tail
     * shared by a real LLM reply and a scripted/interceptor-supplied one.
     */
    private void speakAndShow(UUID playerId, UUID npcEntityId, String npcName, String reply,
                               Optional<SettlemyntsIdentityBridge.IdentitySnapshot> identity) throws IOException, InterruptedException {
        MinecraftServer currentServer = server;
        if (currentServer != null && currentServer.getPlayerList().getPlayer(playerId) instanceof ServerPlayer player) {
            player.sendSystemMessage(Component.literal(npcName + ": " + reply));
        }
        // "FEMALE"/"MALE" (npc.NpcGender's plain enum name, see SettlemyntsIdentityBridge's own
        // doc) picks the matching voice -- defaults to male if unresolved (no Settlemynts
        // identity at all), same as this mod's own pre-gender behavior.
        boolean female = identity.map(SettlemyntsIdentityBridge.IdentitySnapshot::genderName)
                .filter("FEMALE"::equals).isPresent();
        String voice = female ? AudybleConfig.KOKORO_VOICE_FEMALE.get() : AudybleConfig.KOKORO_VOICE_MALE.get();
        byte[] ttsWav = Nuclyus.speak(voice, reply);
        PcmAudio.DecodedWav decoded = PcmAudio.fromWav(ttsWav);
        short[] playbackPcm = PcmAudio.resample(decoded.samples(), decoded.sampleRate(), TARGET_SAMPLE_RATE);

        playOnNpc(npcEntityId, playbackPcm);
    }

    /**
     * Makes {@code npcEntityId} say {@code line} to {@code playerId} right now -- not in response to
     * anything the player said, so there's no transcript/"user" turn to record, only the line itself
     * as a new "assistant" turn (so later real conversation has it as context). For a caller (e.g.
     * Arryval's staged tutorial intro) that wants its own NPC to speak a guaranteed, proactive line --
     * see {@code api.Audyble#speakScriptedLine}. Silently does nothing if the NPC or player can't be
     * resolved right now.
     */
    public void speakScriptedLine(UUID playerId, UUID npcEntityId, String line) {
        pipelineExecutor.submit(() -> {
            try {
                Entity npcEntity = findEntityAnywhere(npcEntityId);
                if (npcEntity == null) {
                    return;
                }
                ConversationalNpcConfig npcConfig = resolveNpcConfig(npcEntity);
                String npcName = npcConfig != null ? npcConfig.characterName() : npcEntity.getName().getString();
                Optional<SettlemyntsIdentityBridge.IdentitySnapshot> identity = npcEntity.level() instanceof ServerLevel npcLevel
                        ? SettlemyntsIdentityBridge.resolveOrBeginBirth(npcLevel, npcEntity)
                        : Optional.empty();
                ConversationKey pairKey = new ConversationKey(npcEntityId, playerId);
                ConversationState convo = conversations.computeIfAbsent(pairKey,
                        k -> new ConversationState(resolveInitialIdentity(npcEntity)));
                synchronized (convo.history) {
                    convo.history.addLast(new OllamaClient.Turn("assistant", line));
                    while (convo.history.size() > MAX_HISTORY_TURNS * 2) {
                        convo.history.removeFirst();
                    }
                }
                convo.lastTurnAtMillis = System.currentTimeMillis();
                speakAndShow(playerId, npcEntityId, npcName, line, identity);
            } catch (Exception e) {
                AudybleMod.LOGGER.error("Audyble NPC scripted line failed", e);
            }
        });
    }

    /** World Lore + this NPC's own persisted backstory/key facts + the player's relationship summary + the character's own behavior prompt (unchanged). */
    /**
     * Confirmed live 2026-11-04: without this, Ollama's replies drift into writing the conversation
     * like a script/story -- narrating actions ("pauses, looks around") rather than just speaking --
     * which Kokoro then reads aloud completely literally, since it has no idea this is meant to be
     * pure spoken dialogue. Placed first and reinforced with a defensive strip in {@link
     * #stripStageDirections} for whenever the model still slips into asterisk-wrapped narration
     * despite the instruction.
     */
    private static final String SPOKEN_DIALOGUE_ONLY_INSTRUCTION =
            "You are having a real, out-loud spoken conversation -- not writing a story or script. "
                    + "Respond with ONLY the words you'd actually say out loud. Never narrate actions, "
                    + "gestures, or your surroundings (e.g. never write things like \"*pauses, looks "
                    + "around*\" or \"(clicks tongue)\") -- if it wouldn't be heard as speech, don't write it.\n";

    private static final java.util.regex.Pattern STAGE_DIRECTION = java.util.regex.Pattern.compile("\\*[^*]*\\*");

    /** Defensive cleanup for whenever the model still slips into asterisk-wrapped action narration despite {@link #SPOKEN_DIALOGUE_ONLY_INSTRUCTION}. */
    private static String stripStageDirections(String reply) {
        return STAGE_DIRECTION.matcher(reply).replaceAll("").trim();
    }

    private static String buildSystemPrompt(String npcName, String backstory, String keyFacts,
                                             String relationshipSummary, ConversationalNpcConfig npcConfig, String settlementName) {
        StringBuilder prompt = new StringBuilder();
        prompt.append(SPOKEN_DIALOGUE_ONLY_INSTRUCTION);
        prompt.append(WorldLore.TEXT).append('\n');
        if (!settlementName.isBlank()) {
            // Real settlement name (2026-11-04) -- without this stated explicitly every turn, nothing
            // here ever names the actual town, so the model would invent/hallucinate its own place
            // name instead of using the real one (confirmed live: a guard referenced a settlement by
            // a made-up name until this was added).
            prompt.append("The settlement you live in is called ").append(settlementName)
                    .append(". Always refer to it by this real name -- never invent or use a different one.\n");
        }
        prompt.append("You are ").append(npcName).append(". ").append(backstory).append('\n');
        if (!keyFacts.isBlank()) {
            prompt.append("Key facts about you:\n").append(keyFacts).append('\n');
        }
        if (!relationshipSummary.isBlank()) {
            prompt.append("Your history with the player you're currently speaking to: ")
                    .append(relationshipSummary).append('\n');
        }
        if (!npcConfig.systemPrompt().isBlank()) {
            prompt.append(npcConfig.systemPrompt());
        }
        return prompt.toString();
    }

    /**
     * Re-summarizes an idle-ended conversation's full rolling history (never persisted as a raw
     * transcript) into a new, bounded relationship summary (persisted via {@link
     * SettlemyntsIdentityBridge#putRelationshipSummary}), building on whatever summary already
     * existed. Runs on {@link #pipelineExecutor}, same as the main pipeline -- calls the LLM once
     * more, so it must never run on the server thread.
     */
    private void summarizeAndPersist(ConversationKey pairKey, ConversationState convo) {
        List<OllamaClient.Turn> turns = List.copyOf(convo.history);
        MinecraftServer currentServer = server;
        if (turns.isEmpty() || convo.initialIdentity == null || currentServer == null) {
            return; // No relationship memory to update without a resolved identity (Settlemynts not loaded) or a server reference for the write.
        }
        try {
            String existingSummary = SettlemyntsIdentityBridge.getRelationshipSummary(convo.initialIdentity, pairKey.playerId());
            StringBuilder transcript = new StringBuilder();
            for (OllamaClient.Turn turn : turns) {
                transcript.append(turn.role()).append(": ").append(turn.content()).append('\n');
            }
            String summarizePrompt = "You maintain a short, bounded running summary of an NPC's relationship "
                    + "with one player, for reuse in future conversations. Reply with ONLY the new summary "
                    + "text (2-4 sentences, no preamble), building on the prior summary where relevant.\n"
                    + "Prior summary: " + (existingSummary.isBlank() ? "(none yet -- this is their first conversation)" : existingSummary);

            String newSummary = Nuclyus.chat(summarizePrompt, List.of(), transcript.toString()).trim();
            if (newSummary.isBlank()) {
                return;
            }
            SettlemyntsIdentityBridge.putRelationshipSummary(currentServer.overworld(), convo.initialIdentity, pairKey.playerId(), newSummary);
        } catch (Exception e) {
            AudybleMod.LOGGER.error("Audyble NPC relationship summarization failed", e);
        }
    }

    /**
     * A cheap local VAD gate, checked before ever calling Whisper at all -- confirmed live
     * 2026-11-04 that ambient mic noise reaching Whisper gets hallucinated into real-looking short
     * words (not just Whisper's own bracketed non-speech tags), triggering unwanted replies and
     * piling up overlapping voice channels. Plain RMS amplitude over the raw 48kHz capture.
     */
    private static boolean isTooQuietToBeSpeech(short[] pcm) {
        if (pcm.length == 0) {
            return true;
        }
        long sumSquares = 0;
        for (short sample : pcm) {
            sumSquares += (long) sample * sample;
        }
        double rms = Math.sqrt((double) sumSquares / pcm.length);
        return rms < AudybleConfig.NPC_MIN_SPEECH_RMS.get();
    }

    private static final java.util.regex.Pattern NON_SPEECH_HALLUCINATION =
            java.util.regex.Pattern.compile("^[\\[(].*[])]$");

    /**
     * Whisper's real, well-known convention for "nothing was actually said" (ambient mic noise,
     * silence, etc.) is to hallucinate one of its own bracket/paren-wrapped non-speech tags --
     * {@code [BLANK_AUDIO]}, {@code [ Laughter ]}, {@code (clicks tongue)}, {@code [inaudible]}, etc.
     * -- instead of genuinely returning an empty string. Confirmed live 2026-11-04: without this
     * filter these pass the plain {@code isBlank()} check and get treated as a real thing the player
     * said, producing an endless unprompted reply loop whenever a player's mic stays open picking up
     * ambient noise near an NPC. A real sentence never starts with an opening bracket/paren AND ends
     * with its matching close across the *entire* trimmed string, so this is a safe, narrow match.
     */
    private static boolean isNonSpeechHallucination(String transcript) {
        return NON_SPEECH_HALLUCINATION.matcher(transcript.trim()).matches();
    }

    /** Resolves {@code entity}'s persistent identity handle -- a real plot-bound worker/Guard via Settlemynts, otherwise its own entity UUID as a wandering identity (Wandering Trader, or a manually bound test entity) -- {@code null} only if Settlemynts isn't loaded at all. */
    private SettlemyntsIdentityBridge.IdentitySnapshot resolveInitialIdentity(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return null;
        }
        return SettlemyntsIdentityBridge.resolveOrBeginBirth(level, entity).orElse(null);
    }

    /** {@code entity}'s real settlement name, if it's a Settlemynts plot-bound worker/guard with one -- see {@link #buildSystemPrompt}. */
    private Optional<String> resolveSettlementName(Entity entity) {
        if (SettlemyntsIdentityBridge.isLoaded()) {
            return SettlemyntsIdentityBridge.resolveSettlementName(entity);
        }
        return Optional.empty();
    }

    private void playOnNpc(UUID npcEntityId, short[] pcm) {
        VoicechatServerApi currentApi = api;
        Entity entity = findEntityAnywhere(npcEntityId);
        if (currentApi == null || entity == null) {
            return;
        }
        EntityAudioChannel channel = currentApi.createEntityAudioChannel(UUID.randomUUID(), currentApi.fromEntity(entity));
        if (channel == null) {
            return;
        }
        // Confirmed against SVC's own real decompiled source 2026-11-04: EntityAudioChannel ties
        // playback to this entity's UUID, and the CLIENT resolves that UUID's own live position for
        // 3D audio -- the same mechanism real player proximity voice uses, so this should already be
        // positional by construction. Left unset, `distance` defaults to SVC's own generic
        // Utils.getDefaultDistanceServer() value (not tuned for "a person talking," closer to the
        // mod's overall max voice chat range). Confirmed live 2026-11-04: calling setCategory with
        // an arbitrary, never-registered category string ("audyble:npc_voice") correlates exactly
        // with every single packet send to the listener starting to fail silently right after this
        // was added (SVC's own send path swallows the real exception -- see NpcConversationEngine's
        // other notes on this -- but the timing match across two independent live tests, including
        // after a full player relog that should have ruled out a stale-connection cause, is strong
        // enough to treat this as the real cause rather than coincidence). Category was never
        // actually confirmed necessary for positional audio in the first place (that comes from the
        // entity UUID, not the category) -- dropped entirely rather than chasing SVC's own category
        // registration requirements further. `distance` alone is kept; it's a plain float field with
        // no equivalent risk.
        channel.setDistance((float) (AudybleConfig.NPC_INTERACTION_RANGE.get() * 2.0));
        var encoder = currentApi.createEncoder();
        var player = currentApi.createAudioPlayer(channel, encoder, pcm);
        player.setOnStopped(encoder::close);
        player.startPlaying();
    }

    private Entity findEntityAnywhere(UUID entityId) {
        MinecraftServer currentServer = server;
        if (currentServer == null) {
            return null;
        }
        for (ServerLevel level : currentServer.getAllLevels()) {
            Entity entity = level.getEntity(entityId);
            if (entity != null) {
                return entity;
            }
        }
        return null;
    }

    private Entity findNearestConversationalNpc(ServerPlayer speaker) {
        double range = AudybleConfig.NPC_INTERACTION_RANGE.get();
        return speaker.level().getEntities(speaker, speaker.getBoundingBox().inflate(range), this::isConversational)
                .stream()
                .min((a, b) -> Double.compare(a.distanceToSqr(speaker), b.distanceToSqr(speaker)))
                .orElse(null);
    }

    /**
     * True for an explicitly {@code /audyble npc bind}-bound test entity, a real vanilla {@link
     * WanderingTrader} (every one gets a real Phase 5b Milestone 3 identity), a plain vanilla {@link
     * net.minecraft.world.entity.npc.villager.Villager} (added 2026-11-05, real report: a naturally-
     * generated village's villagers had no name and wouldn't respond at all -- {@code
     * getClass() == Villager.class}, not {@code instanceof}, since Settlemynts' own plot-bound worker
     * subclasses all extend {@code Villager} too and are already covered by the
     * {@code isRecognizedWorkerOrGuard} check below; this is purely for a plain one neither Settlemynts
     * nor this mod ever spawned itself -- Settlemynts' own {@code npc.VillagerIdentityListener} is what
     * actually gives it a name/identity in the first place), or a real plot-bound worker/Guard
     * recognized via {@link SettlemyntsIdentityBridge#isRecognizedWorkerOrGuard} -- deliberately NOT
     * true for an arbitrary mob, so ordinary wildlife never becomes talkable just by standing near a
     * player. Uses the narrower {@code isRecognizedWorkerOrGuard} check specifically (not {@link
     * SettlemyntsIdentityBridge#resolveOrBeginBirth}, which assigns a generic identity to literally
     * anything) -- see that method's own doc for why.
     */
    private boolean isConversational(Entity entity) {
        if (entity.hasData(NpcAttachments.CONVERSATIONAL_NPC)) {
            return true;
        }
        if (entity instanceof WanderingTrader) {
            return true;
        }
        if (entity.getClass() == net.minecraft.world.entity.npc.villager.Villager.class) {
            return true;
        }
        return SettlemyntsIdentityBridge.isRecognizedWorkerOrGuard(entity);
    }

    /**
     * An explicitly bound test entity uses its own real character config; a real worker/Guard/
     * Wandering Trader with no explicit bind gets a generic default behavior prompt instead (its
     * actual characterization comes from {@link #buildSystemPrompt}'s identity/backstory/key-facts,
     * not from this). {@code null} means "not conversational at all" -- the pipeline exits early.
     */
    private ConversationalNpcConfig resolveNpcConfig(Entity entity) {
        if (entity.hasData(NpcAttachments.CONVERSATIONAL_NPC)) {
            return entity.getData(NpcAttachments.CONVERSATIONAL_NPC);
        }
        if (isConversational(entity)) {
            return new ConversationalNpcConfig(entity.getName().getString(), DEFAULT_WORKER_SYSTEM_PROMPT);
        }
        return null;
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("audyble")
                .then(Commands.literal("npc")
                        .then(Commands.literal("bind")
                                .then(Commands.argument("target", EntityArgument.entity())
                                        .then(Commands.argument("characterName", StringArgumentType.string())
                                                .then(Commands.argument("systemPrompt", StringArgumentType.greedyString())
                                                        .executes(ctx -> {
                                                            Entity target = EntityArgument.getEntity(ctx, "target");
                                                            String characterName = StringArgumentType.getString(ctx, "characterName");
                                                            String systemPrompt = StringArgumentType.getString(ctx, "systemPrompt");
                                                            target.setData(NpcAttachments.CONVERSATIONAL_NPC,
                                                                    new ConversationalNpcConfig(characterName, systemPrompt));
                                                            ctx.getSource().sendSuccess(() -> Component.literal(
                                                                    "Bound " + target.getName().getString() + " as conversational NPC \""
                                                                            + characterName + "\"."), true);
                                                            return 1;
                                                        }))))))
                .then(Commands.literal("say")
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
                                        ctx.getSource().sendFailure(Component.literal("Only a player can use this."));
                                        return 0;
                                    }
                                    String message = StringArgumentType.getString(ctx, "message");
                                    // No-microphone text fallback -- never broadcast anywhere (a
                                    // command's own arguments aren't public chat), feeds directly into
                                    // the same pipeline voice capture uses. sendSuccess(..., false)
                                    // means this feedback goes only to the sender, never broadcast.
                                    if (NpcConversationEngine.instance() != null && NpcConversationEngine.instance().sayToNearestNpc(player, message)) {
                                        ctx.getSource().sendSuccess(() -> Component.literal("(said privately)"), false);
                                    } else {
                                        ctx.getSource().sendFailure(Component.literal("No one nearby to talk to."));
                                    }
                                    return 1;
                                }))));
    }
}
