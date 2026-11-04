package com.github.cerealklla.audyble.npc;

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
import com.github.cerealklla.audyble.api.Audyble;
import com.github.cerealklla.audyble.bridge.SettlemyntsIdentityBridge;
import com.github.cerealklla.audyble.config.AudybleConfig;

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
     * doc). {@code npcKey} is resolved once, at the conversation's first turn, and reused for every
     * later turn/summarization in this thread rather than re-resolved from a possibly-now-gone live
     * entity.
     */
    private static final class ConversationState {
        final NpcIdentityKey npcKey;
        final Deque<OllamaClient.Turn> history = new ArrayDeque<>();
        volatile long lastTurnAtMillis = System.currentTimeMillis();

        ConversationState(NpcIdentityKey npcKey) {
            this.npcKey = npcKey;
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
            short[] pcm16k = PcmAudio.resample(pcm48k, TARGET_SAMPLE_RATE, WHISPER_SAMPLE_RATE);
            byte[] wav16k = PcmAudio.toWav(pcm16k, WHISPER_SAMPLE_RATE);

            WhisperSttClient whisper = new WhisperSttClient(AudybleConfig.WHISPER_BASE_URL.get());
            String transcript = whisper.transcribe(wav16k);
            if (transcript.isBlank()) {
                return;
            }
            AudybleMod.LOGGER.info("Audyble NPC heard: {}", transcript);

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
                    k -> new ConversationState(resolveIdentityKey(npcEntity)));

            NpcIdentity identity = Audyble.resolveOrBeginBirth(convo.npcKey, null);
            PlayerRelationship relationship = Audyble.getRelationship(convo.npcKey, playerId);
            String systemPrompt = buildSystemPrompt(identity, relationship, npcConfig);

            OllamaClient ollama = new OllamaClient(AudybleConfig.OLLAMA_BASE_URL.get(), AudybleConfig.OLLAMA_MODEL.get());
            String reply = ollama.chat(systemPrompt, List.copyOf(convo.history), transcript);

            synchronized (convo.history) {
                convo.history.addLast(new OllamaClient.Turn("user", transcript));
                convo.history.addLast(new OllamaClient.Turn("assistant", reply));
                while (convo.history.size() > MAX_HISTORY_TURNS * 2) {
                    convo.history.removeFirst();
                }
            }
            convo.lastTurnAtMillis = System.currentTimeMillis();

            KokoroTtsClient kokoro = new KokoroTtsClient(AudybleConfig.KOKORO_BASE_URL.get(), AudybleConfig.KOKORO_VOICE.get());
            byte[] ttsWav = kokoro.synthesize(reply);
            PcmAudio.DecodedWav decoded = PcmAudio.fromWav(ttsWav);
            short[] playbackPcm = PcmAudio.resample(decoded.samples(), decoded.sampleRate(), TARGET_SAMPLE_RATE);

            playOnNpc(npcEntityId, playbackPcm);
        } catch (Exception e) {
            AudybleMod.LOGGER.error("Audyble NPC conversation pipeline failed", e);
        }
    }

    /** World Lore + this NPC's own persisted backstory/key facts + the player's relationship summary + the character's own behavior prompt (unchanged). */
    private static String buildSystemPrompt(NpcIdentity identity, PlayerRelationship relationship, ConversationalNpcConfig npcConfig) {
        StringBuilder prompt = new StringBuilder();
        prompt.append(WorldLore.TEXT).append('\n');
        prompt.append("You are ").append(identity.name()).append(". ").append(identity.backstory()).append('\n');
        String keyFacts = identity.keyFactsAsPromptText();
        if (!keyFacts.isBlank()) {
            prompt.append("Key facts about you:\n").append(keyFacts).append('\n');
        }
        if (!relationship.summary().isBlank()) {
            prompt.append("Your history with the player you're currently speaking to: ")
                    .append(relationship.summary()).append('\n');
        }
        if (!npcConfig.systemPrompt().isBlank()) {
            prompt.append(npcConfig.systemPrompt());
        }
        return prompt.toString();
    }

    /**
     * Re-summarizes an idle-ended conversation's full rolling history (never persisted as a raw
     * transcript) into a new, bounded {@link PlayerRelationship}, building on whatever summary
     * already existed. Runs on {@link #pipelineExecutor}, same as the main pipeline -- calls Ollama
     * once more, so it must never run on the server thread.
     */
    private void summarizeAndPersist(ConversationKey pairKey, ConversationState convo) {
        List<OllamaClient.Turn> turns = List.copyOf(convo.history);
        if (turns.isEmpty()) {
            return;
        }
        try {
            PlayerRelationship existing = Audyble.getRelationship(convo.npcKey, pairKey.playerId());
            StringBuilder transcript = new StringBuilder();
            for (OllamaClient.Turn turn : turns) {
                transcript.append(turn.role()).append(": ").append(turn.content()).append('\n');
            }
            String summarizePrompt = "You maintain a short, bounded running summary of an NPC's relationship "
                    + "with one player, for reuse in future conversations. Reply with ONLY the new summary "
                    + "text (2-4 sentences, no preamble), building on the prior summary where relevant.\n"
                    + "Prior summary: " + (existing.summary().isBlank() ? "(none yet -- this is their first conversation)" : existing.summary());

            OllamaClient ollama = new OllamaClient(AudybleConfig.OLLAMA_BASE_URL.get(), AudybleConfig.OLLAMA_MODEL.get());
            String newSummary = ollama.chat(summarizePrompt, List.of(), transcript.toString()).trim();
            if (newSummary.isBlank()) {
                return;
            }
            MinecraftServer currentServer = server;
            long gameTime = currentServer != null ? currentServer.overworld().getGameTime() : existing.lastUpdatedGameTime();
            Audyble.putRelationship(new PlayerRelationship(existing.key(), newSummary, gameTime));
        } catch (Exception e) {
            AudybleMod.LOGGER.error("Audyble NPC relationship summarization failed", e);
        }
    }

    /** Resolves {@code entity}'s persistent identity key -- a real plot-bound worker/Guard via Settlemynts, otherwise its own entity UUID as a wandering identity (Wandering Trader, or a manually bound test entity). */
    private NpcIdentityKey resolveIdentityKey(Entity entity) {
        if (SettlemyntsIdentityBridge.isLoaded()) {
            Optional<NpcIdentityKey> key = SettlemyntsIdentityBridge.resolveIdentityKey(entity);
            if (key.isPresent()) {
                return key.get();
            }
        }
        return NpcIdentityKey.wandering(entity.getUUID());
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
     * WanderingTrader} (every one gets a real Phase 5b Milestone 3 identity), or a real plot-bound
     * worker/Guard resolvable via {@link SettlemyntsIdentityBridge} -- deliberately NOT true for an
     * arbitrary mob, so ordinary wildlife never becomes talkable just by standing near a player.
     */
    private boolean isConversational(Entity entity) {
        if (entity.hasData(NpcAttachments.CONVERSATIONAL_NPC)) {
            return true;
        }
        if (entity instanceof WanderingTrader) {
            return true;
        }
        return SettlemyntsIdentityBridge.isLoaded() && SettlemyntsIdentityBridge.resolveIdentityKey(entity).isPresent();
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
                                                        })))))));
    }
}
