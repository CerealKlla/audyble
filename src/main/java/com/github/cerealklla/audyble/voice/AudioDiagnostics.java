package com.github.cerealklla.audyble.voice;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.github.cerealklla.audyble.AudybleMod;

/**
 * Temporary instrumentation (2026-10-21, real incident: "the entire computer is almost unusable"
 * during an AFK session with no one actually speaking). Reasoning alone couldn't settle this --
 * real pushback from the user: an unmuted microphone can still transmit continuously (ambient
 * room/fan noise crossing Simple Voice Chat's own client-side voice-activation threshold) even with
 * nobody "talking," and nothing in this pipeline (confirmed by reading it) gates on real speech vs.
 * SVC's own decision to send a packet at all -- so this genuinely can't be ruled out without real
 * data, only reasoned about.
 *
 * <p>Tracks real packet rate and real processing cost for {@code AudyblePlugin#onEntitySound},
 * reported periodically (every {@link #REPORT_INTERVAL_MILLIS}) rather than per packet -- packets
 * can arrive roughly every 20ms per active connection, far too often to log individually. Also logs
 * the handling thread's name exactly once, since whether this runs on the main server thread (and
 * could therefore ever show up as Minecraft tick lag at all) or SVC's own separate thread entirely
 * is itself an open question -- confirmed from the real thread, not assumed from how the code reads.
 * Remove once the real culprit is confirmed.
 */
final class AudioDiagnostics {

    private static final long REPORT_INTERVAL_MILLIS = 5000;

    private static final AtomicLong packetsSeen = new AtomicLong();
    private static final AtomicLong packetsProcessed = new AtomicLong(); // Non-passthrough: actually decoded/filtered/re-encoded.
    private static final AtomicLong occlusionNanos = new AtomicLong();
    private static final AtomicLong processNanos = new AtomicLong();
    private static final AtomicBoolean loggedThreadName = new AtomicBoolean(false);
    private static volatile long windowStartMillis = System.currentTimeMillis();

    private AudioDiagnostics() {
    }

    static void recordPacketSeen() {
        packetsSeen.incrementAndGet();
        if (loggedThreadName.compareAndSet(false, true)) {
            AudybleMod.LOGGER.info("[AudioDiag] onEntitySound running on thread: {}", Thread.currentThread().getName());
        }
        maybeReport();
    }

    static void recordOcclusion(long elapsedNanos) {
        occlusionNanos.addAndGet(elapsedNanos);
    }

    static void recordProcess(long elapsedNanos) {
        packetsProcessed.incrementAndGet();
        processNanos.addAndGet(elapsedNanos);
    }

    private static void maybeReport() {
        long now = System.currentTimeMillis();
        if (now - windowStartMillis < REPORT_INTERVAL_MILLIS) {
            return;
        }
        long seen = packetsSeen.getAndSet(0);
        long processed = packetsProcessed.getAndSet(0);
        long occNanos = occlusionNanos.getAndSet(0);
        long procNanos = processNanos.getAndSet(0);
        windowStartMillis = now;
        if (seen == 0) {
            return; // Genuinely nothing this window -- silence here IS the data point.
        }
        AudybleMod.LOGGER.info(
                "[AudioDiag] last ~{}s: {} packets seen, {} actually processed (non-passthrough), "
                        + "occlusion={}ms total, audioProcess={}ms total",
                REPORT_INTERVAL_MILLIS / 1000, seen, processed, occNanos / 1_000_000L, procNanos / 1_000_000L);
    }
}
