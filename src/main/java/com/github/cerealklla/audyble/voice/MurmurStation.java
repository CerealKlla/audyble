package com.github.cerealklla.audyble.voice;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

import com.github.cerealklla.audyble.config.AudybleConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One Murmur Tower's own continuous, shared track schedule -- always playing *something*, on its
 * own wall clock, regardless of whether anyone's currently tuned in (explicit user correction,
 * 2026-11-02: toggling a crystal only starts/stops *receiving* this shared signal, never touches
 * what the station itself is playing).
 *
 * <p><b>2026-11-02 rework: playback moved entirely client-side.</b> The original design decoded
 * audio server-side and relayed it to listeners through Simple Voice Chat's Opus codec -- real
 * user report, "a ton of distortion," traced to Opus's auto-selected bitrate having no exposed
 * control at all (confirmed via decompiling the real SVC implementation). Fixing that meant
 * getting off the lossy voice-chat transport entirely: a client now downloads the actual track
 * file directly (see {@code voice.MusicHttpServer}/{@code voice.client.ClientMurmurPlayback}) and
 * decodes/plays it locally, so this class no longer decodes or holds any PCM at all -- it only
 * tracks *which* track is current/queued and *when* each one started, authoritatively, for
 * clients to mirror. A listener arriving mid-song still starts mid-song, same as a real radio.
 *
 * <p>One instance per tower (keyed by dimension+position in {@link
 * MurmurCrystalEngine#STATIONS}), never per listener. {@link #ensureCurrent()} advances past any
 * track(s) that have already finished by pure math against the wall clock -- correct no matter how
 * infrequently it's called, with no drift, since it always re-derives the current track/position
 * from real elapsed time rather than incrementing anything per call.
 */
final class MurmurStation {

    private static final Logger LOGGER = LoggerFactory.getLogger("audyble-music");

    /** "Next 2" upcoming, on top of whatever's current -- 3 tracks' worth of lookahead for clients to prefetch. */
    static final int QUEUE_SIZE = 2;

    record QueuedTrack(String trackId, double durationSeconds, String artist, String song) {
    }

    private final ExecutorService worker;
    private QueuedTrack current;
    private long trackStartNanos;
    private boolean refilling;
    private final Deque<QueuedTrack> upcoming = new ArrayDeque<>();

    MurmurStation(ExecutorService worker) {
        this.worker = worker;
    }

    /**
     * Advances past any finished track(s) and tops the queue back up if it's run low -- call this
     * before reading any of this station's state ({@link #currentTrackId()}, etc.) so it's never
     * stale. Cheap and safe to call redundantly (every scan, for every listener) -- the real work
     * (picking + probing a new track) only ever happens in the background {@link #worker}.
     */
    synchronized void ensureCurrent() {
        maintainDebugOverride();
        if (current == null) {
            promoteFromQueue();
        } else {
            long durationNanos = (long) (current.durationSeconds() * 1_000_000_000L);
            long elapsedNanos = System.nanoTime() - trackStartNanos;
            while (durationNanos > 0 && elapsedNanos >= durationNanos) {
                trackStartNanos += durationNanos;
                elapsedNanos -= durationNanos;
                if (!promoteFromQueue()) {
                    break;
                }
                durationNanos = (long) (current.durationSeconds() * 1_000_000_000L);
            }
        }
        if (upcoming.size() < QUEUE_SIZE) {
            requestFill();
        }
    }

    private boolean promoteFromQueue() {
        QueuedTrack next = upcoming.pollFirst();
        if (next == null) {
            current = null;
            return false;
        }
        current = next;
        trackStartNanos = System.nanoTime();
        LOGGER.info("Murmur Tower station now broadcasting {} - {} ({}s)",
                next.artist(), next.song(), next.durationSeconds());
        return true;
    }

    private void requestFill() {
        if (refilling) {
            return;
        }
        refilling = true;
        worker.submit(() -> {
            QueuedTrack picked = null;
            try {
                Optional<Path> chosen = MusicLibrary.pickRandomTrack();
                if (chosen.isPresent()) {
                    Path path = chosen.get();
                    double duration = MusicLibrary.probeDurationSeconds(path);
                    MusicLibrary.TrackInfo info = MusicLibrary.trackInfoFor(path);
                    picked = new QueuedTrack(MusicLibrary.trackIdFor(path), duration, info.artist(), info.song());
                }
            } catch (Throwable e) {
                LOGGER.warn("Failed to queue a Murmur Tower station track", e);
            }
            synchronized (this) {
                if (picked != null) {
                    upcoming.addLast(picked);
                    if (current == null) {
                        promoteFromQueue();
                    }
                }
                refilling = false;
            }
        });
    }

    synchronized String currentTrackId() {
        return current != null ? current.trackId() : null;
    }

    synchronized double secondsIntoCurrentTrack() {
        return current != null ? (System.nanoTime() - trackStartNanos) / 1_000_000_000.0 : 0.0;
    }

    synchronized String artist() {
        return current != null ? current.artist() : null;
    }

    synchronized String song() {
        return current != null ? current.song() : null;
    }

    synchronized long trackStartNanos() {
        return trackStartNanos;
    }

    /** Current + queued track ids, in play order -- what a listener/prefetching client needs to know about. */
    synchronized List<String> allTrackIdsInOrder() {
        List<String> result = new java.util.ArrayList<>();
        if (current != null) {
            result.add(current.trackId());
        }
        upcoming.forEach(t -> result.add(t.trackId()));
        return result;
    }

    /**
     * Debug/testing only, see {@link AudybleConfig#DEBUG_FORCE_TRACK_FILENAME} -- if set and
     * anything currently current/queued doesn't match it by filename, drops that state entirely so
     * the next {@link #requestFill()} repopulates it; {@code MusicLibrary#pickRandomTrack} already
     * always returns the forced file while this is set, so every slot ends up holding it.
     */
    private void maintainDebugOverride() {
        String forced = AudybleConfig.DEBUG_FORCE_TRACK_FILENAME.get();
        if (forced == null || forced.isBlank()) {
            return;
        }
        boolean currentMatches = current == null || fileNameOf(current.trackId()).equalsIgnoreCase(forced);
        boolean queueMatches = upcoming.stream().allMatch(t -> fileNameOf(t.trackId()).equalsIgnoreCase(forced));
        if (!currentMatches || !queueMatches) {
            current = null;
            upcoming.clear();
        }
    }

    private static String fileNameOf(String trackId) {
        int slash = trackId.lastIndexOf('/');
        return slash >= 0 ? trackId.substring(slash + 1) : trackId;
    }
}
