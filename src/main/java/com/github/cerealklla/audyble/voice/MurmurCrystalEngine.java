package com.github.cerealklla.audyble.voice;

import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.github.cerealklla.audyble.bridge.LyfeKnowledgeBridge;
import com.github.cerealklla.audyble.bridge.SettlemyntsMurmurBridge;
import com.github.cerealklla.audyble.config.AudybleConfig;
import com.github.cerealklla.audyble.registration.ModDataComponents;

import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Drives the Murmur Crystal item (design-document.md Phase 6). Deliberately mirrors {@code
 * npc.NpcConversationEngine}'s own {@code init()}/{@code instance()}/{@code onServerStarted}
 * shape -- a {@link VoicechatServerApi} is only ever handed to this mod once, from {@code
 * voice.AudyblePlugin}'s own {@code onServerStarted} hook, and there's no other way to reach it.
 *
 * <p>Uses SVC's real {@link StaticAudioChannel} (confirmed directly against the actual
 * {@code voicechat-api-2.6.20} jar via {@code javap}, not guessed) -- not tied to any player's
 * microphone or any world position, unlike {@code EntityAudioChannel}. One unscoped channel per
 * actively-listening player (via {@code createStaticAudioChannel(UUID)}, then {@code
 * addTarget(connection)}), so delivery is scoped to exactly that one holder, never broadcast.
 * Playback mechanics ({@code createEncoder()} -> {@code createAudioPlayer(channel, encoder, pcm)}
 * -> {@code startPlaying()}) mirror {@code NpcConversationEngine#playOnNpc}'s already-proven
 * template, just swapping the channel type.
 *
 * <p>Phase 1 scope, explicit user confirmation: plays a real placeholder tone through this actual
 * pipeline (not silent plumbing) -- proves the mechanism end-to-end now; swapping in real music
 * later only changes the audio source, not this pipeline.
 */
public final class MurmurCrystalEngine {

    private static volatile MurmurCrystalEngine INSTANCE;

    public static void init() {
        MurmurCrystalEngine engine = new MurmurCrystalEngine();
        INSTANCE = engine;
        NeoForge.EVENT_BUS.register(engine);
    }

    public static MurmurCrystalEngine instance() {
        return INSTANCE;
    }

    private static final int SAMPLE_RATE = 48000;
    private static final int FRAME_SIZE = 960; // SVC's AudioPlayer requires exactly 960 samples/frame.
    private static final double TONE_HZ = 220.0; // Placeholder test tone -- Phase 2 swaps in real music.
    private static final float VOLUME_FLOOR = 0.1f; // Matches AcousticOcclusion's own floor, reused for consistency.
    private static final double STATIC_THRESHOLD_FRACTION = 0.9; // Outer 10% of range ramps to the floor.

    private static final int SCAN_INTERVAL_TICKS = 100; // 5 real seconds at 20 TPS.
    private static final int TICK_OFFSET = 40;

    private volatile VoicechatServerApi api;
    private final Map<UUID, ListenerSession> sessions = new ConcurrentHashMap<>();

    public void onServerStarted(VoicechatServerApi api) {
        this.api = api;
    }

    private static final class ListenerSession {
        final StaticAudioChannel channel;
        AudioPlayer player;
        volatile float gain;
        private double phase;

        ListenerSession(StaticAudioChannel channel) {
            this.channel = channel;
        }

        /** Generates the next frame of the placeholder tone, scaled by the current gain -- a running phase accumulator avoids an audible click at each frame boundary. */
        short[] nextFrame() {
            short[] frame = new short[FRAME_SIZE];
            double step = 2.0 * Math.PI * TONE_HZ / SAMPLE_RATE;
            float currentGain = gain;
            for (int i = 0; i < FRAME_SIZE; i++) {
                frame[i] = (short) (Math.sin(phase) * Short.MAX_VALUE * currentGain * 0.3);
                phase += step;
            }
            phase %= 2.0 * Math.PI;
            return frame;
        }
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        VoicechatServerApi currentApi = api;
        if (currentApi == null || !AudybleConfig.MURMUR_CRYSTAL_ENABLED.get()) {
            return;
        }
        MinecraftServer server = event.getServer();
        if ((server.getTickCount() + TICK_OFFSET) % SCAN_INTERVAL_TICKS != 0) {
            return;
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!(player.level() instanceof net.minecraft.server.level.ServerLevel playerLevel)) {
                continue;
            }
            MurmurCrystalState state = readState(player);
            Optional<SettlemyntsMurmurBridge.MurmurSignalInfo> signal = state != null && state.active() && SettlemyntsMurmurBridge.isLoaded()
                    ? SettlemyntsMurmurBridge.findNearestMurmurSignal(playerLevel, player.blockPosition())
                    : Optional.empty();

            if (signal.isEmpty()) {
                stopSession(player.getUUID());
                continue;
            }

            SettlemyntsMurmurBridge.MurmurSignalInfo info = signal.get();
            float strength = signalStrength(player.blockPosition(), info);
            ListenerSession session = sessions.computeIfAbsent(player.getUUID(),
                    id -> startSession(currentApi, player));
            if (session == null) {
                continue;
            }
            session.gain = strength * state.volume();

            if (LyfeKnowledgeBridge.isLoaded() && info.tier() >= 3) {
                LyfeKnowledgeBridge.grantTowerKnowledge(player, info.settlementPlaceId(), player.level(), info.towerPos(), info.tier());
            }
        }

        // Anyone who logged out since the last scan never gets a stopSession call above -- clean up here.
        sessions.keySet().removeIf(id -> {
            if (server.getPlayerList().getPlayer(id) != null) {
                return false;
            }
            stopSession(id);
            return true;
        });
    }

    private static MurmurCrystalState readState(ServerPlayer player) {
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty() || !held.has(ModDataComponents.MURMUR_CRYSTAL_STATE.get())) {
            return null;
        }
        return held.get(ModDataComponents.MURMUR_CRYSTAL_STATE.get());
    }

    /** Clean inside {@link #STATIC_THRESHOLD_FRACTION} of max range, ramping linearly to {@link #VOLUME_FLOOR} at the edge -- same linear-falloff-with-floor shape as {@code AcousticOcclusion#treatmentFor}, reused for consistency rather than new math. */
    private static float signalStrength(BlockPos listenerPos, SettlemyntsMurmurBridge.MurmurSignalInfo info) {
        double distance = Math.sqrt(listenerPos.distSqr(info.towerPos()));
        double maxRange = info.maxRangeBlocks();
        double staticThreshold = maxRange * STATIC_THRESHOLD_FRACTION;
        if (distance <= staticThreshold) {
            return 1.0f;
        }
        if (distance >= maxRange || maxRange <= staticThreshold) {
            return VOLUME_FLOOR;
        }
        double t = (distance - staticThreshold) / (maxRange - staticThreshold);
        return (float) Math.max(VOLUME_FLOOR, 1.0 - t * (1.0 - VOLUME_FLOOR));
    }

    private ListenerSession startSession(VoicechatServerApi currentApi, ServerPlayer player) {
        VoicechatConnection connection = currentApi.getConnectionOf(player.getUUID());
        if (connection == null) {
            return null;
        }
        StaticAudioChannel channel = currentApi.createStaticAudioChannel(UUID.randomUUID());
        if (channel == null) {
            return null;
        }
        channel.addTarget(connection);
        OpusEncoder encoder = currentApi.createEncoder();
        ListenerSession session = new ListenerSession(channel);
        AudioPlayer audioPlayer = currentApi.createAudioPlayer(channel, encoder, session::nextFrame);
        session.player = audioPlayer;
        audioPlayer.setOnStopped(encoder::close);
        audioPlayer.startPlaying();
        return session;
    }

    private void stopSession(UUID playerId) {
        ListenerSession session = sessions.remove(playerId);
        if (session != null) {
            session.player.stopPlaying();
            session.channel.clearTargets();
        }
    }
}
