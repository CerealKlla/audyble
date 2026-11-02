package com.github.cerealklla.audyble.voice;

import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.github.cerealklla.audyble.bridge.LyfeKnowledgeBridge;
import com.github.cerealklla.audyble.bridge.SettlemyntsMurmurBridge;
import com.github.cerealklla.audyble.config.AudybleConfig;
import com.github.cerealklla.audyble.registration.ModDataComponents;

import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoderMode;

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
 * Playback mechanics ({@code createEncoder(OpusEncoderMode.AUDIO)} -> {@code
 * createAudioPlayer(channel, encoder, pcm)} -> {@code startPlaying()}) mirror {@code
 * NpcConversationEngine#playOnNpc}'s already-proven template, just swapping the channel type and
 * the encoder mode (real music needs {@code AUDIO}, not the speech-tuned {@code VOIP} default --
 * see {@link #startSession}'s own comment).
 *
 * <p>Phase 1 scope, explicit user confirmation: plays a real placeholder tone through this actual
 * pipeline (not silent plumbing) -- proves the mechanism end-to-end now; swapping in real music
 * later only changes the audio source, not this pipeline.
 *
 * <p>2026-11-02: that swap happened -- {@link AudybleConfig#MUSIC_SOURCE_DIRECTORY}, when set,
 * makes real decoded Ogg Vorbis audio ({@link MusicLibrary}) play through a shared {@link
 * MurmurStation} per tower (see that class's own doc -- a real correction the same day: the first
 * version wrongly tied track selection to each listener instead of the tower itself), falling back
 * to the tone whenever no directory is configured, no track is found, or a decode fails.
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

    /** How close counts as "arrived," clearing the waypoint -- see {@link #pendingArrivals}' own doc. */
    private static final double ARRIVAL_THRESHOLD_BLOCKS = 30.0;

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("audyble-music");

    /** Off the server tick thread -- real file I/O + Vorbis decode, see {@link MusicLibrary#decodeToMono48k}. Daemon so it never blocks JVM shutdown. */
    private static final ExecutorService MUSIC_DECODER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Audyble-Music-Decoder");
        t.setDaemon(true);
        return t;
    });

    private volatile VoicechatServerApi api;
    private final Map<UUID, ListenerSession> sessions = new ConcurrentHashMap<>();

    /** One shared, always-ticking-forward broadcast per tower -- see {@link MurmurStation}'s own doc. */
    private static final Map<StationKey, MurmurStation> STATIONS = new ConcurrentHashMap<>();

    private record StationKey(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension, BlockPos towerPos) {
    }

    /**
     * Settlements a player has a Murmur-signal-granted minimap waypoint pending arrival for --
     * explicit user request, 2026-11-02: "a self-removing-upon-arrival waypoint... when a player
     * gains partial knowledge of a settlement via the radio signal." Set the instant a player's
     * knowledge of a settlement goes from none to something (see {@link #refreshPlayer}'s own
     * {@code LyfeKnowledgeBridge.hasAnyKnowledge} check), independent of whether they're still
     * holding/listening to a crystal at the time -- checked every scan for every online player (not
     * just crystal holders), so the waypoint still clears on arrival even if the crystal's been put
     * away or walked out of range since it was granted.
     */
    private final Map<UUID, WaypointTarget> pendingArrivals = new ConcurrentHashMap<>();

    private record WaypointTarget(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension, BlockPos pos) {
    }

    public void onServerStarted(VoicechatServerApi api) {
        this.api = api;
    }

    private static final class ListenerSession {
        final StaticAudioChannel channel;
        AudioPlayer player;
        volatile float gain;
        private double phase;

        /**
         * Which tower's broadcast this listener is currently tuned to -- set in {@link
         * #refreshPlayer} whenever a signal resolves, never created or advanced by this session
         * itself (see {@link MurmurStation}'s own doc for why: toggling a crystal on/off must only
         * start/stop *receiving* the shared broadcast, never affect what it's playing).
         */
        volatile MurmurStation station;

        /** The last {@link MurmurStation#trackStartNanos} this listener was shown an announcement for -- see {@link #announceNewTracks}. */
        volatile long announcedTrackStartNanos;

        ListenerSession(StaticAudioChannel channel) {
            this.channel = channel;
        }

        /** Generates the next frame of whatever this session is currently tuned to, scaled by the current gain. */
        short[] nextFrame() {
            MurmurStation currentStation = station;
            float currentGain = gain;
            if (currentStation != null) {
                short[] frame = currentStation.currentFrame(currentGain, FRAME_SIZE);
                if (frame != null) {
                    return frame;
                }
            }
            return placeholderToneFrame(currentGain);
        }

        /** The Phase 1 placeholder tone -- a running phase accumulator avoids an audible click at each frame boundary. */
        private short[] placeholderToneFrame(float currentGain) {
            short[] frame = new short[FRAME_SIZE];
            double step = 2.0 * Math.PI * TONE_HZ / SAMPLE_RATE;
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
        if (api == null || !AudybleConfig.MURMUR_CRYSTAL_ENABLED.get()) {
            return;
        }
        MinecraftServer server = event.getServer();
        announceNewTracks(server);
        if ((server.getTickCount() + TICK_OFFSET) % SCAN_INTERVAL_TICKS != 0) {
            return;
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            refreshPlayer(player);
        }

        // Anyone who logged out since the last scan never gets a stopSession call above -- clean up here.
        sessions.keySet().removeIf(id -> {
            if (server.getPlayerList().getPlayer(id) != null) {
                return false;
            }
            stopSession(id);
            return true;
        });
        pendingArrivals.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
    }

    /**
     * Shows a "<Artist> - <Song>" actionbar message (above the health/XP bars, vanilla's own
     * fade-after-a-few-seconds behavior, no custom timer needed) to every currently-listening
     * player whose station just started a track they haven't already been shown -- explicit user
     * request, 2026-11-02. Runs every tick (not gated behind {@link #SCAN_INTERVAL_TICKS}) so the
     * announcement fires promptly when a song actually changes, but the check itself is cheap (one
     * volatile long compare per active listener) regardless.
     */
    private void announceNewTracks(MinecraftServer server) {
        if (!AudybleConfig.SHOW_ARTIST_AND_SONG.get()) {
            return;
        }
        for (Map.Entry<UUID, ListenerSession> entry : sessions.entrySet()) {
            ListenerSession session = entry.getValue();
            MurmurStation station = session.station;
            if (station == null) {
                continue;
            }
            long startNanos = station.trackStartNanos();
            if (startNanos == 0L || startNanos == session.announcedTrackStartNanos) {
                continue;
            }
            session.announcedTrackStartNanos = startNanos;
            String artist = station.artist();
            String song = station.song();
            if (artist == null || song == null) {
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null) {
                player.sendOverlayMessage(net.minecraft.network.chat.Component.literal(artist + " - " + song));
            }
        }
    }

    /**
     * Re-evaluates one player's own listening state right now -- range/signal/gain, same logic
     * {@link #onServerTick}'s periodic scan runs for everyone, just immediately for one player
     * instead of waiting up to {@link #SCAN_INTERVAL_TICKS} ticks (5 real seconds). Called from
     * {@code AudybleMod}'s {@code SetMurmurCrystalStatePayload} handler right after a GUI toggle/
     * volume change is written onto the item, per explicit user feedback, 2026-11-02: flipping the
     * switch should take effect immediately, not on the next periodic tick. The periodic scan still
     * does the actual range-change detection (walking in/out of range) -- there's no practical event
     * to hook for that, so it stays polled; only the GUI-triggered case needed to stop waiting on it.
     */
    public void refreshPlayer(ServerPlayer player) {
        VoicechatServerApi currentApi = api;
        if (currentApi == null || !AudybleConfig.MURMUR_CRYSTAL_ENABLED.get()) {
            return;
        }
        if (!(player.level() instanceof net.minecraft.server.level.ServerLevel playerLevel)) {
            return;
        }
        checkWaypointArrival(player, playerLevel);
        MurmurCrystalState state = readState(player);
        Optional<SettlemyntsMurmurBridge.MurmurSignalInfo> signal = state != null && state.active() && SettlemyntsMurmurBridge.isLoaded()
                ? SettlemyntsMurmurBridge.findNearestMurmurSignal(playerLevel, player.blockPosition())
                : Optional.empty();

        if (signal.isEmpty()) {
            stopSession(player.getUUID());
            return;
        }

        SettlemyntsMurmurBridge.MurmurSignalInfo info = signal.get();
        float strength = signalStrength(player.blockPosition(), info);
        ListenerSession session = sessions.computeIfAbsent(player.getUUID(),
                id -> startSession(currentApi, player));
        if (session == null) {
            return;
        }
        session.gain = strength * state.volume();
        session.station = STATIONS.computeIfAbsent(new StationKey(playerLevel.dimension(), info.towerPos()),
                key -> new MurmurStation(MUSIC_DECODER));

        if (LyfeKnowledgeBridge.isLoaded() && info.tier() >= 3) {
            boolean firstKnowledge = !LyfeKnowledgeBridge.hasAnyKnowledge(player, info.settlementPlaceId());
            LyfeKnowledgeBridge.grantTowerKnowledge(player, info.settlementPlaceId(), player.level(), info.towerPos(), info.tier());
            if (firstKnowledge) {
                double radius = info.tier() >= 5 ? 0.0 : info.tier() == 4 ? 150.0 : 300.0;
                LyfeKnowledgeBridge.setSettlementWaypoint(player, player.level(), info.towerPos(), radius);
                pendingArrivals.put(player.getUUID(), new WaypointTarget(playerLevel.dimension(), info.towerPos()));
            }
        }
    }

    /** Clears a pending Murmur-signal waypoint once the player gets within {@link #ARRIVAL_THRESHOLD_BLOCKS} of it, in the same dimension. */
    private void checkWaypointArrival(ServerPlayer player, net.minecraft.server.level.ServerLevel playerLevel) {
        WaypointTarget target = pendingArrivals.get(player.getUUID());
        if (target == null) {
            return;
        }
        if (!target.dimension().equals(playerLevel.dimension())) {
            return;
        }
        if (Math.sqrt(player.blockPosition().distSqr(target.pos())) <= ARRIVAL_THRESHOLD_BLOCKS) {
            pendingArrivals.remove(player.getUUID());
            if (LyfeKnowledgeBridge.isLoaded()) {
                LyfeKnowledgeBridge.clearWaypoint(player);
            }
        }
    }

    /**
     * Finds the first active Murmur Crystal anywhere on the player -- main hand, off hand, or
     * carried in their inventory -- not just the one actively held. Real user feedback, 2026-11-02:
     * there's no reason it should need to be wielded to keep playing once toggled on, any more than
     * a pocketed radio would. {@link SetMurmurCrystalStatePayload}'s handler still only ever writes
     * to the main-hand stack, since the GUI itself can only ever be opened while holding one -- this
     * only widens where the engine looks for an already-toggled-on crystal afterward.
     */
    private static MurmurCrystalState readState(ServerPlayer player) {
        MurmurCrystalState mainHand = stateOf(player.getMainHandItem());
        if (mainHand != null && mainHand.active()) {
            return mainHand;
        }
        MurmurCrystalState offHand = stateOf(player.getOffhandItem());
        if (offHand != null && offHand.active()) {
            return offHand;
        }
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            MurmurCrystalState state = stateOf(inventory.getItem(i));
            if (state != null && state.active()) {
                return state;
            }
        }
        // Nothing active anywhere -- fall back to the main-hand stack's own state (even if inactive,
        // or null if it's not a crystal at all) so the caller's existing "active()" check still works.
        return mainHand;
    }

    private static MurmurCrystalState stateOf(ItemStack stack) {
        if (stack.isEmpty() || !stack.has(ModDataComponents.MURMUR_CRYSTAL_STATE.get())) {
            return null;
        }
        return stack.get(ModDataComponents.MURMUR_CRYSTAL_STATE.get());
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
        // AUDIO mode, not the no-arg default (VOIP -- confirmed via javap against the real
        // voicechat-api-2.6.20 jar, a de.maxhenkel.voicechat.api.opus.OpusEncoderMode enum with
        // VOIP/AUDIO/RESTRICTED_LOWDELAY) -- real user report, 2026-11-02: "a ton of distortion"
        // on a known-good test file. VOIP is the right choice for NpcConversationEngine's actual
        // speech, but Opus's voice-tuned bitrate/bandwidth profile mangles real music badly; AUDIO
        // is the general-purpose music-appropriate mode.
        OpusEncoder encoder = currentApi.createEncoder(OpusEncoderMode.AUDIO);
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
