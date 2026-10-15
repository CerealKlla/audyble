package com.github.cerealklla.audyble.voice;

/**
 * The result of {@code Speaker -> Listener} rule evaluation (design-document.md section 4). Carries
 * everything {@link AudioProcessor} needs to turn a speaker's raw opus audio into what one specific
 * listener should actually receive.
 *
 * @param kind              coarse classification, mostly useful for logging/debugging and for
 *                          {@link AudyblePlugin}'s fast path (NORMAL needs no decode/encode at all)
 * @param audible           if false, nothing is sent to this listener at all -- cheapest possible
 *                          case, the event is simply cancelled with no further processing
 * @param volumeMultiplier  linear gain applied to decoded PCM, 1.0 = unchanged
 * @param lowPassCutoffHz   if &gt; 0, a low-pass filter is applied at this cutoff (muffling/radio
 *                          band-limiting); 0 disables filtering entirely
 * @param distanceOverride  if &gt;= 0, overrides the distance value sent to the client (which drives
 *                          SVC's own client-side distance falloff), e.g. so a radio transmission
 *                          sounds the same regardless of the real in-world distance; -1 leaves the
 *                          packet's own distance unchanged
 */
public record VoiceTreatment(Kind kind, boolean audible, float volumeMultiplier, float lowPassCutoffHz,
                              float distanceOverride) {

    public enum Kind {
        NORMAL, MUFFLED, RADIO, INAUDIBLE
    }

    public static final float NO_DISTANCE_OVERRIDE = -1f;

    public static VoiceTreatment normal() {
        return new VoiceTreatment(Kind.NORMAL, true, 1f, 0f, NO_DISTANCE_OVERRIDE);
    }

    public static VoiceTreatment inaudible() {
        return new VoiceTreatment(Kind.INAUDIBLE, false, 0f, 0f, NO_DISTANCE_OVERRIDE);
    }

    public static VoiceTreatment muffled(float volumeMultiplier, float lowPassCutoffHz) {
        return new VoiceTreatment(Kind.MUFFLED, true, volumeMultiplier, lowPassCutoffHz, NO_DISTANCE_OVERRIDE);
    }

    public static VoiceTreatment radio(float volumeMultiplier, float lowPassCutoffHz, float distanceOverride) {
        return new VoiceTreatment(Kind.RADIO, true, volumeMultiplier, lowPassCutoffHz, distanceOverride);
    }

    /** True if this treatment is indistinguishable from doing nothing -- lets callers skip DSP entirely. */
    public boolean isPassthrough() {
        return kind == Kind.NORMAL && volumeMultiplier == 1f && lowPassCutoffHz == 0f
                && distanceOverride == NO_DISTANCE_OVERRIDE;
    }
}
