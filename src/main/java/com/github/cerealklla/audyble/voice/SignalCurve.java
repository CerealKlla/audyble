package com.github.cerealklla.audyble.voice;

/**
 * Pure math for how strong a Murmur Tower's signal is at a given distance -- no engine/network/
 * client dependencies at all, so both a server-side caller and {@code voice.client
 * .ClientMurmurPlayback} can share the exact same curve without either depending on the other.
 * Clean inside {@link #STATIC_THRESHOLD_FRACTION} of max range, ramping linearly to the edge --
 * same linear-falloff-with-floor shape {@code AcousticOcclusion#treatmentFor} already uses for the
 * unrelated walkie-talkie Radio item, reused here for consistency rather than new math.
 *
 * <p>2026-11-02 rework: previously computed server-side as a plain volume multiplier applied to
 * PCM before Opus-encoding it. Now that Murmur Crystal playback is entirely client-side (see
 * {@code voice.client.ClientMurmurPlayback}'s own doc for why), {@link #strength} still gives the
 * volume multiplier, and {@link #staticAmount} is new -- how much synthesized noise to mix in,
 * something that was never practical to do well through a lossy voice codec but is cheap and
 * sounds far more like a real weakening signal once computed directly against the client's own
 * local, uncompressed audio output.
 */
public final class SignalCurve {

    public static final float VOLUME_FLOOR = 0.1f;
    public static final double STATIC_THRESHOLD_FRACTION = 0.9; // Outer 10% of range ramps to the floor.

    private SignalCurve() {
    }

    /** 1.0 (full volume) inside the clean zone, ramping down to {@link #VOLUME_FLOOR} at/past max range. */
    public static float strength(double distance, double maxRange) {
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

    /** 0.0 (clean) inside the clean zone, ramping up to 1.0 (maximum static) at/past max range. */
    public static float staticAmount(double distance, double maxRange) {
        double staticThreshold = maxRange * STATIC_THRESHOLD_FRACTION;
        if (distance <= staticThreshold) {
            return 0.0f;
        }
        if (distance >= maxRange || maxRange <= staticThreshold) {
            return 1.0f;
        }
        return (float) ((distance - staticThreshold) / (maxRange - staticThreshold));
    }
}
