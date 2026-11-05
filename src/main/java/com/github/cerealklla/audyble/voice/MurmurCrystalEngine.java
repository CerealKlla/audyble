package com.github.cerealklla.audyble.voice;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.github.cerealklla.audyble.bridge.LyfeKnowledgeBridge;
import com.github.cerealklla.audyble.bridge.SettlemyntsMurmurBridge;
import com.github.cerealklla.audyble.config.AudybleConfig;
import com.github.cerealklla.audyble.registration.ModDataComponents;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Drives the Murmur Crystal item (design-document.md Phase 6).
 *
 * <p><b>2026-11-02 rework: no longer touches Simple Voice Chat at all.</b> The original design
 * relayed decoded music through SVC's Opus codec; a real user report ("a ton of distortion")
 * traced to Opus's auto-selected bitrate having no exposed control in the plugin API at all
 * (confirmed by decompiling the real SVC implementation). Fixed by moving playback entirely
 * client-side instead: this class now only tracks *which* track each {@link MurmurStation} is
 * playing and broadcasts that (plus a lookahead queue, for prefetching) to in-range listeners --
 * {@code voice.MusicHttpServer} serves the actual files, and {@code voice.client
 * .ClientMurmurPlayback} downloads/decodes/plays them locally, computing its own live volume/
 * static against {@link SignalCurve} every client tick with no network round-trip needed for
 * that. The GUI (on/off/volume, {@link MurmurCrystalItem}/{@code client.MurmurCrystalScreen}) is
 * unchanged.
 *
 * <p>Two radii per signal: a station's real audible range ({@code maxRangeBlocks}) is unchanged;
 * a wider prefetch radius ({@link AudybleConfig#MUSIC_PREFETCH_RANGE_MULTIPLIER} times that) lets
 * a client start downloading before it's actually close enough to hear anything, so there's no
 * fetch delay once it is.
 */
public final class MurmurCrystalEngine {

    private static volatile MurmurCrystalEngine INSTANCE;

    public static void init() {
        MurmurCrystalEngine engine = new MurmurCrystalEngine();
        INSTANCE = engine;
        NeoForge.EVENT_BUS.register(engine);
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> MusicHttpServer.start());
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> MusicHttpServer.stop());
    }

    public static MurmurCrystalEngine instance() {
        return INSTANCE;
    }

    private static final int SCAN_INTERVAL_TICKS = 100; // 5 real seconds at 20 TPS.
    private static final int TICK_OFFSET = 40;

    /** How close counts as "arrived," clearing the waypoint -- see {@link #pendingArrivals}' own doc. */
    private static final double ARRIVAL_THRESHOLD_BLOCKS = 30.0;

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("audyble-music");

    /** Off the server tick thread -- real file I/O + header probing, see {@link MusicLibrary#probeDurationSeconds}. Daemon so it never blocks JVM shutdown. */
    private static final ExecutorService STATION_WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Audyble-Music-Station");
        t.setDaemon(true);
        return t;
    });

    /** One shared, always-ticking-forward schedule per tower -- see {@link MurmurStation}'s own doc. */
    private static final Map<StationKey, MurmurStation> STATIONS = new ConcurrentHashMap<>();

    private record StationKey(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension, BlockPos towerPos) {
    }

    /** Per-player bookkeeping for what's already been sent, so a stop payload fires exactly once on exit. */
    private final Map<UUID, PlayerMurmurState> states = new ConcurrentHashMap<>();

    private static final class PlayerMurmurState {
        volatile long announcedTrackStartNanos;
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

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (!AudybleConfig.MURMUR_CRYSTAL_ENABLED.get()) {
            return;
        }
        MinecraftServer server = event.getServer();
        if ((server.getTickCount() + TICK_OFFSET) % SCAN_INTERVAL_TICKS != 0) {
            return;
        }

        Set<UUID> stillActive = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (refreshPlayer(player)) {
                stillActive.add(player.getUUID());
            }
        }

        states.keySet().removeIf(id -> {
            if (stillActive.contains(id) && server.getPlayerList().getPlayer(id) != null) {
                return false;
            }
            states.remove(id);
            return true;
        });
        pendingArrivals.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
    }

    /**
     * Re-evaluates one player's own listening state right now -- range/signal, same logic {@link
     * #onServerTick}'s periodic scan runs for everyone, just immediately for one player instead of
     * waiting up to {@link #SCAN_INTERVAL_TICKS} ticks (5 real seconds). Called from {@code
     * AudybleMod}'s {@code SetMurmurCrystalStatePayload} handler right after a GUI toggle/volume
     * change is written onto the item. Returns whether the player is currently prefetching or tuned
     * in to anything (used by {@link #onServerTick} to know when to clean up/send a stop).
     */
    public boolean refreshPlayer(ServerPlayer player) {
        if (!AudybleConfig.MURMUR_CRYSTAL_ENABLED.get()) {
            return false;
        }
        if (!(player.level() instanceof net.minecraft.server.level.ServerLevel playerLevel)) {
            return false;
        }
        checkWaypointArrival(player, playerLevel);
        MurmurCrystalState state = readState(player);
        Optional<SettlemyntsMurmurBridge.MurmurSignalInfo> signal = state != null && state.active() && SettlemyntsMurmurBridge.isLoaded()
                ? SettlemyntsMurmurBridge.findNearestMurmurSignal(playerLevel, player.blockPosition())
                : Optional.empty();

        if (signal.isEmpty()) {
            sendStopIfTracked(player);
            return false;
        }

        SettlemyntsMurmurBridge.MurmurSignalInfo info = signal.get();
        double distance = Math.sqrt(player.blockPosition().distSqr(info.towerPos()));
        double prefetchRange = info.maxRangeBlocks() * AudybleConfig.MUSIC_PREFETCH_RANGE_MULTIPLIER.get();
        if (distance > prefetchRange) {
            sendStopIfTracked(player);
            return false;
        }

        MurmurStation station = STATIONS.computeIfAbsent(new StationKey(playerLevel.dimension(), info.towerPos()),
                key -> new MurmurStation(STATION_WORKER));
        station.ensureCurrent(info.settlementPlaceId());

        if (distance <= info.maxRangeBlocks()) {
            sendTunedIn(player, station, info);
            grantKnowledgeAndWaypoint(player, playerLevel, info);
        } else {
            sendPrefetchHint(player, station);
        }
        return true;
    }

    private void sendPrefetchHint(ServerPlayer player, MurmurStation station) {
        var trackIds = station.allTrackIdsInOrder();
        if (trackIds.isEmpty()) {
            return;
        }
        PacketDistributor.sendToPlayer(player,
                new MurmurPrefetchHintPayload(trackIds, AudybleConfig.MUSIC_HTTP_PORT.get()));
    }

    private void sendTunedIn(ServerPlayer player, MurmurStation station, SettlemyntsMurmurBridge.MurmurSignalInfo info) {
        String trackId = station.currentTrackId();
        if (trackId == null) {
            return;
        }
        var upcoming = station.allTrackIdsInOrder();
        upcoming.remove(0); // Drop the current track -- this list is the *upcoming* queue only.
        PacketDistributor.sendToPlayer(player, new MurmurTunedInPayload(
                trackId,
                station.artist(),
                station.song(),
                (float) station.secondsIntoCurrentTrack(),
                upcoming,
                AudybleConfig.MUSIC_HTTP_PORT.get(),
                info.towerPos(),
                (float) info.maxRangeBlocks()));

        if (AudybleConfig.SHOW_ARTIST_AND_SONG.get()) {
            PlayerMurmurState state = states.computeIfAbsent(player.getUUID(), id -> new PlayerMurmurState());
            long startNanos = station.trackStartNanos();
            if (startNanos != 0L && startNanos != state.announcedTrackStartNanos) {
                state.announcedTrackStartNanos = startNanos;
                String artist = station.artist();
                String song = station.song();
                if (artist != null && song != null) {
                    player.sendOverlayMessage(net.minecraft.network.chat.Component.literal(artist + " - " + song));
                }
            }
        }
    }

    private void sendStopIfTracked(ServerPlayer player) {
        if (states.remove(player.getUUID()) != null) {
            PacketDistributor.sendToPlayer(player, new MurmurStopPlaybackPayload());
        }
    }

    private void grantKnowledgeAndWaypoint(ServerPlayer player, net.minecraft.server.level.ServerLevel playerLevel,
            SettlemyntsMurmurBridge.MurmurSignalInfo info) {
        if (!LyfeKnowledgeBridge.isLoaded() || info.tier() < 3) {
            return;
        }
        boolean firstKnowledge = !LyfeKnowledgeBridge.hasAnyKnowledge(player, info.settlementPlaceId());
        LyfeKnowledgeBridge.grantTowerKnowledge(player, info.settlementPlaceId(), player.level(), info.towerPos(), info.tier());
        if (firstKnowledge) {
            double radius = info.tier() >= 5 ? 0.0 : info.tier() == 4 ? 150.0 : 300.0;
            LyfeKnowledgeBridge.setSettlementWaypoint(player, player.level(), info.towerPos(), radius);
            pendingArrivals.put(player.getUUID(), new WaypointTarget(playerLevel.dimension(), info.towerPos()));
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
}
