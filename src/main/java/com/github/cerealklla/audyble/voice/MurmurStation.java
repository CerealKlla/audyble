package com.github.cerealklla.audyble.voice;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One Murmur Tower's own continuous, shared broadcast -- explicit user correction, 2026-11-02:
 * "The radio tower is supposed to always be playing music. Turning on or off the murmur crystal
 * just lets you hear the live music. You have it controlling the music being played just by
 * toggling the radio" -- the original design tied track selection/decoding to each {@code
 * ListenerSession} individually, so every toggle (or every player) started its own independent
 * random track, with no two listeners ever necessarily in sync. A real radio station doesn't work
 * that way: it's always playing *something*, on its own clock, and tuning in just lets you hear
 * wherever it currently is.
 *
 * <p>One instance per tower (keyed by dimension+position in {@link
 * MurmurCrystalEngine#STATIONS}), never per listener. {@link #currentFrame} computes playback
 * position from real wall-clock elapsed time since {@link #trackStartNanos}, not from how many
 * frames any particular listener has pulled -- so every session tuned to the same station reads
 * the identical position, and toggling a crystal on/off only starts/stops *receiving* this shared
 * signal, never touches the station's own track or position.
 */
final class MurmurStation {

    private static final Logger LOGGER = LoggerFactory.getLogger("audyble-music");
    private static final int SAMPLE_RATE = 48000; // Matches MusicLibrary#decodeToMono48k's fixed output rate.

    private final ExecutorService decoder;
    private volatile short[] trackPcm;
    private volatile long trackStartNanos;
    private volatile boolean loading;
    private volatile String currentArtist;
    private volatile String currentSong;

    MurmurStation(ExecutorService decoder) {
        this.decoder = decoder;
    }

    /**
     * The {@code frameSize}-sample window that should be playing right now, scaled by {@code gain},
     * or {@code null} if no track is currently loaded -- the caller should fall back to the Phase 1
     * placeholder tone in that case. {@code null} covers both "nothing decoded yet" and "the
     * current track just ran out, and this call just kicked off picking/decoding the next one in
     * the background" -- a listener arriving mid-song starts mid-song, same as a real radio.
     */
    short[] currentFrame(float gain, int frameSize) {
        short[] track = trackPcm;
        if (track != null) {
            long elapsedNanos = System.nanoTime() - trackStartNanos;
            long sampleIndex = elapsedNanos * SAMPLE_RATE / 1_000_000_000L;
            if (sampleIndex < track.length) {
                short[] frame = new short[frameSize];
                for (int i = 0; i < frameSize; i++) {
                    long idx = sampleIndex + i;
                    frame[i] = idx < track.length ? (short) (track[(int) idx] * gain) : 0;
                }
                return frame;
            }
        }
        requestNextTrack();
        return null;
    }

    /** The real {@code System.nanoTime()} this station's current track began -- {@code 0} means no track has ever decoded yet. Used by {@code MurmurCrystalEngine} to detect "a new song just started" per listener, to show the artist/song announcement at most once per track. */
    long trackStartNanos() {
        return trackStartNanos;
    }

    String artist() {
        return currentArtist;
    }

    String song() {
        return currentSong;
    }

    /**
     * Picks and decodes a new random track in the background, starting it "now" the moment decode
     * finishes -- deliberately does not try to reconstruct exactly which track "should" be playing
     * after a long gap with zero listeners (e.g. an hour with nobody nearby); it just resumes with
     * a fresh one, the same practical experience as tuning a real radio back in.
     */
    private void requestNextTrack() {
        if (loading) {
            return;
        }
        loading = true;
        decoder.submit(() -> {
            try {
                Optional<Path> chosen = MusicLibrary.pickRandomTrack();
                if (chosen.isPresent()) {
                    short[] decoded = MusicLibrary.decodeToMono48k(chosen.get());
                    MusicLibrary.TrackInfo info = MusicLibrary.trackInfoFor(chosen.get());
                    trackPcm = decoded;
                    currentArtist = info.artist();
                    currentSong = info.song();
                    trackStartNanos = System.nanoTime();
                    LOGGER.info("Murmur Tower station now broadcasting {} - {} ({}s)",
                            info.artist(), info.song(), decoded.length / (double) SAMPLE_RATE);
                } else {
                    trackPcm = null;
                }
            } catch (Throwable e) {
                LOGGER.warn("Failed to decode Murmur Tower station track", e);
                trackPcm = null;
            } finally {
                loading = false;
            }
        });
    }
}
