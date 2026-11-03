package com.github.cerealklla.audyble.voice.client;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

import com.github.cerealklla.audyble.registration.ModDataComponents;
import com.github.cerealklla.audyble.voice.MurmurCrystalItem;
import com.github.cerealklla.audyble.voice.MurmurCrystalState;
import com.github.cerealklla.audyble.voice.MurmurPrefetchHintPayload;
import com.github.cerealklla.audyble.voice.MurmurTunedInPayload;
import com.github.cerealklla.audyble.voice.MusicLibrary;
import com.github.cerealklla.audyble.voice.SignalCurve;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plays a Murmur Tower station's current track entirely locally -- download, decode, and output
 * all happen on the client, with no audio relayed over the network at all (2026-11-02 rework).
 * Real user report, "a ton of distortion," traced to Simple Voice Chat's Opus codec having no
 * exposed bitrate control at all in its plugin API (confirmed by decompiling the real
 * implementation); the only real fix was getting off that transport entirely. Genuinely
 * client-only (reads {@code Minecraft}/local player state, opens a local {@link SourceDataLine})
 * -- only ever reached through {@code AudybleModClient}'s tick listener, never from a
 * common-sourceset class (see {@link ClientMurmurRequests}'s own doc for the isolation this relies
 * on).
 *
 * <p>Always trusts the most recent {@link MurmurTunedInPayload} as ground truth and extrapolates
 * position forward from it via wall-clock elapsed time -- deliberately does <b>not</b> try to
 * independently predict queue advancement between syncs (it doesn't know a track's exact duration
 * until it's actually decoded it itself). A track boundary landing between two periodic syncs
 * (every {@code SCAN_INTERVAL_TICKS}, 1 real second) can briefly hold on silence rather than play
 * past the end of what's locally decoded -- an accepted, flagged tradeoff for not duplicating
 * {@code MurmurStation}'s own queue-advancement math client-side.
 */
public final class ClientMurmurPlayback {

    private static final Logger LOGGER = LoggerFactory.getLogger("audyble-music");

    private static final int SAMPLE_RATE = 48000;
    private static final short NOISE_AMPLITUDE_MAX = 7000; // ~21% of full scale at maximum static.

    private static final ClientMusicCache CACHE = new ClientMusicCache();
    private static final Map<String, short[]> DECODED = new ConcurrentHashMap<>();
    private static final Set<String> DECODING = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final ExecutorService DECODER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Audyble-Murmur-Decode");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicReference<TunedIn> TUNED_IN = new AtomicReference<>();
    private static volatile Thread playbackThread;
    private static volatile SourceDataLine line;

    private record TunedIn(String trackId, String artist, String song, double secondsIntoTrackAtReceipt,
            long receiptNanos, List<String> upcomingTrackIds, String host, int httpPort,
            BlockPos towerPos, float maxRangeBlocks) {
    }

    private ClientMurmurPlayback() {
    }

    /** Called every client tick from {@code AudybleModClient} -- drains whatever's pending in {@link ClientMurmurRequests} and applies it. */
    public static void tick() {
        ClientMurmurRequests.takePendingPrefetch().ifPresent(ClientMurmurPlayback::onPrefetchHint);
        ClientMurmurRequests.takePendingTunedIn().ifPresent(ClientMurmurPlayback::onTunedIn);
        if (ClientMurmurRequests.takePendingStop()) {
            stop();
        }
    }

    private static void onPrefetchHint(MurmurPrefetchHintPayload payload) {
        String host = currentServerHost();
        if (host == null) {
            return;
        }
        for (String trackId : payload.trackIds()) {
            ensureDecodedAsync(trackId, host, payload.httpPort());
        }
    }

    private static void onTunedIn(MurmurTunedInPayload payload) {
        String host = currentServerHost();
        if (host == null) {
            return;
        }
        TUNED_IN.set(new TunedIn(payload.trackId(), payload.artist(), payload.song(), payload.secondsIntoTrack(),
                System.nanoTime(), payload.upcomingTrackIds(), host, payload.httpPort(), payload.towerPos(), payload.maxRangeBlocks()));

        ensureDecodedAsync(payload.trackId(), host, payload.httpPort());
        for (String trackId : payload.upcomingTrackIds()) {
            ensureDecodedAsync(trackId, host, payload.httpPort());
        }
        Set<String> keep = new HashSet<>(payload.upcomingTrackIds());
        keep.add(payload.trackId());
        CACHE.evictExcept(keep);
        DECODED.keySet().retainAll(keep);

        ensurePlaybackThreadRunning();
    }

    private static void stop() {
        TUNED_IN.set(null);
    }

    private static void ensurePlaybackThreadRunning() {
        if (playbackThread != null) {
            return;
        }
        synchronized (ClientMurmurPlayback.class) {
            if (playbackThread != null) {
                return;
            }
            Thread t = new Thread(ClientMurmurPlayback::playbackLoop, "Audyble-Murmur-Playback");
            t.setDaemon(true);
            t.start();
            playbackThread = t;
        }
    }

    private static void playbackLoop() {
        int chunkSize = SAMPLE_RATE / 10; // 0.1s per chunk.
        byte[] outBuffer = new byte[chunkSize * 2];
        while (true) {
            TunedIn tuned = TUNED_IN.get();
            if (tuned == null) {
                closeLineIfOpen();
                sleepQuietly(100);
                continue;
            }
            short[] track = decodedTrackOrNull(tuned.trackId(), tuned.host(), tuned.httpPort());
            float strength;
            float staticAmount;
            float guiVolume;
            long sampleIndex;
            try {
                LocalPlayer player = Minecraft.getInstance().player;
                if (player == null) {
                    sleepQuietly(100);
                    continue;
                }
                double distance = player.position().distanceTo(Vec3.atCenterOf(tuned.towerPos()));
                strength = SignalCurve.strength(distance, tuned.maxRangeBlocks());
                staticAmount = SignalCurve.staticAmount(distance, tuned.maxRangeBlocks());
                guiVolume = resolveGuiVolume(player);
                double secondsElapsed = tuned.secondsIntoTrackAtReceipt() + (System.nanoTime() - tuned.receiptNanos()) / 1_000_000_000.0;
                sampleIndex = (long) (secondsElapsed * SAMPLE_RATE);
            } catch (Exception e) {
                sleepQuietly(100);
                continue;
            }

            ensureLineOpen();
            SourceDataLine currentLine = line;
            if (currentLine == null) {
                sleepQuietly(100);
                continue;
            }

            for (int i = 0; i < chunkSize; i++) {
                short sample = 0;
                long idx = sampleIndex + i;
                if (track != null && idx >= 0 && idx < track.length) {
                    double musicSample = track[(int) idx] * strength;
                    double noise = (ThreadLocalRandom.current().nextDouble() * 2 - 1) * NOISE_AMPLITUDE_MAX * staticAmount;
                    double combined = (musicSample * (1.0 - staticAmount * 0.5) + noise) * guiVolume;
                    sample = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, combined));
                }
                outBuffer[i * 2] = (byte) (sample & 0xFF);
                outBuffer[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
            }
            currentLine.write(outBuffer, 0, outBuffer.length);
        }
    }

    /**
     * Never blocks -- a cache hit returns immediately, a miss kicks off {@link #ensureDecodedAsync}
     * in the background and returns {@code null} for this call (the caller plays silence for this
     * one chunk and tries again ~100ms later). Real bug fixed 2026-11-02, "the music is still
     * stuttery": this used to decode synchronously right here, on the same thread responsible for
     * continuously feeding {@link #line} -- decoding a multi-minute file can take real time, and
     * every second spent decoding was a second not spent writing to the audio line, starving it.
     */
    private static short[] decodedTrackOrNull(String trackId, String host, int httpPort) {
        short[] cached = DECODED.get(trackId);
        if (cached == null) {
            ensureDecodedAsync(trackId, host, httpPort);
        }
        return cached;
    }

    /** Kicks off a background download/decode for {@code trackId} if it isn't already cached or in flight -- safe to call redundantly every chunk/tick. */
    private static void ensureDecodedAsync(String trackId, String host, int httpPort) {
        if (DECODED.containsKey(trackId) || !DECODING.add(trackId)) {
            return;
        }
        var localPath = CACHE.localPathIfPresent(trackId);
        if (localPath == null) {
            CACHE.ensureDownloading(trackId, host, httpPort);
            DECODING.remove(trackId);
            return;
        }
        DECODER.submit(() -> {
            try {
                short[] decoded = MusicLibrary.decodeToMono48k(localPath);
                DECODED.put(trackId, decoded);
            } catch (Exception e) {
                LOGGER.warn("Failed to decode downloaded Murmur Crystal track {}", trackId, e);
            } finally {
                DECODING.remove(trackId);
            }
        });
    }

    private static void ensureLineOpen() {
        if (line != null) {
            return;
        }
        try {
            AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
            SourceDataLine opened = AudioSystem.getSourceDataLine(format);
            opened.open(format);
            opened.start();
            line = opened;
        } catch (Exception e) {
            LOGGER.warn("Failed to open local audio output line for Murmur Crystal playback", e);
        }
    }

    private static void closeLineIfOpen() {
        SourceDataLine current = line;
        if (current != null) {
            current.drain();
            current.close();
            line = null;
        }
    }

    /** Mirrors {@code MurmurCrystalEngine#readState}/{@code #stateOf} but against the local client player, entirely client-side, no network round trip needed for the volume slider to feel instant. */
    private static float resolveGuiVolume(LocalPlayer player) {
        MurmurCrystalState mainHand = stateOf(player.getMainHandItem());
        if (mainHand != null && mainHand.active()) {
            return mainHand.volume();
        }
        MurmurCrystalState offHand = stateOf(player.getOffhandItem());
        if (offHand != null && offHand.active()) {
            return offHand.volume();
        }
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            MurmurCrystalState state = stateOf(inventory.getItem(i));
            if (state != null && state.active()) {
                return state.volume();
            }
        }
        return 1.0f;
    }

    private static MurmurCrystalState stateOf(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof MurmurCrystalItem) || !stack.has(ModDataComponents.MURMUR_CRYSTAL_STATE.get())) {
            return null;
        }
        return stack.get(ModDataComponents.MURMUR_CRYSTAL_STATE.get());
    }

    /** The host the client used to connect to this server -- payloads only ever carry a port, never a host, so a server behind NAT/a hostname like kyngdoms.game-server.cc still works correctly. */
    private static String currentServerHost() {
        var connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            return null;
        }
        var address = connection.getConnection().getRemoteAddress();
        if (address instanceof java.net.InetSocketAddress inet) {
            return inet.getAddress().getHostAddress();
        }
        return null;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
