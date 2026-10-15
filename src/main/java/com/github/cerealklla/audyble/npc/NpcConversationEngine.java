package com.github.cerealklla.audyble.npc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;

import com.github.cerealklla.audyble.AudybleMod;
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

    private volatile VoicechatServerApi api;
    private volatile MinecraftServer server;
    private final Map<UUID, NpcVoiceSession> activeSessions = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<OllamaClient.Turn>> historyByNpc = new ConcurrentHashMap<>();
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
        if (activeSessions.isEmpty()) {
            return;
        }
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

            ConversationalNpcConfig npcConfig = resolveNpcConfig(npcEntityId);
            if (npcConfig == null) {
                return;
            }
            Deque<OllamaClient.Turn> history = historyByNpc.computeIfAbsent(npcEntityId, k -> new ArrayDeque<>());

            OllamaClient ollama = new OllamaClient(AudybleConfig.OLLAMA_BASE_URL.get(), AudybleConfig.OLLAMA_MODEL.get());
            String reply = ollama.chat(npcConfig.systemPrompt(), List.copyOf(history), transcript);

            synchronized (history) {
                history.addLast(new OllamaClient.Turn("user", transcript));
                history.addLast(new OllamaClient.Turn("assistant", reply));
                while (history.size() > MAX_HISTORY_TURNS * 2) {
                    history.removeFirst();
                }
            }

            KokoroTtsClient kokoro = new KokoroTtsClient(AudybleConfig.KOKORO_BASE_URL.get(), AudybleConfig.KOKORO_VOICE.get());
            byte[] ttsWav = kokoro.synthesize(reply);
            PcmAudio.DecodedWav decoded = PcmAudio.fromWav(ttsWav);
            short[] playbackPcm = PcmAudio.resample(decoded.samples(), decoded.sampleRate(), TARGET_SAMPLE_RATE);

            playOnNpc(npcEntityId, playbackPcm);
        } catch (Exception e) {
            AudybleMod.LOGGER.error("Audyble NPC conversation pipeline failed", e);
        }
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
        return speaker.level().getEntities(speaker, speaker.getBoundingBox().inflate(range), e ->
                        e.hasData(NpcAttachments.CONVERSATIONAL_NPC))
                .stream()
                .min((a, b) -> Double.compare(a.distanceToSqr(speaker), b.distanceToSqr(speaker)))
                .orElse(null);
    }

    private ConversationalNpcConfig resolveNpcConfig(UUID npcEntityId) {
        Entity entity = findEntityAnywhere(npcEntityId);
        return entity != null ? entity.getData(NpcAttachments.CONVERSATIONAL_NPC) : null;
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
