package com.github.cerealklla.audyble.voice;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link AcousticOcclusion#countOccludingBlocks} needs a live {@code Level}, so only the pure
 * {@link AcousticOcclusion#treatmentFor} math is unit-tested here -- same limitation as every other
 * suite mod's world-reading classes (e.g. Cartographyr's NaturalRegionDiscovery). */
class AcousticOcclusionTest {

    @Test
    void noOcclusionIsNormal() {
        VoiceTreatment treatment = AcousticOcclusion.treatmentFor(0, 0.15f, 2200f);
        assertEquals(VoiceTreatment.Kind.NORMAL, treatment.kind());
        assertTrue(treatment.isPassthrough());
    }

    @Test
    void occlusionReducesVolumeAndCutoff() {
        VoiceTreatment treatment = AcousticOcclusion.treatmentFor(2, 0.15f, 2200f);
        assertEquals(VoiceTreatment.Kind.MUFFLED, treatment.kind());
        assertEquals(1f - 2 * 0.15f, treatment.volumeMultiplier(), 0.0001f);
        assertEquals(2200f - 2 * 150f, treatment.lowPassCutoffHz(), 0.0001f);
    }

    @Test
    void volumeNeverGoesBelowFloor() {
        VoiceTreatment treatment = AcousticOcclusion.treatmentFor(5, 0.5f, 2200f);
        assertEquals(0.1f, treatment.volumeMultiplier(), 0.0001f);
    }

    @Test
    void cutoffNeverGoesBelowFloor() {
        VoiceTreatment treatment = AcousticOcclusion.treatmentFor(5, 0.1f, 400f);
        assertEquals(300f, treatment.lowPassCutoffHz(), 0.0001f);
    }
}
