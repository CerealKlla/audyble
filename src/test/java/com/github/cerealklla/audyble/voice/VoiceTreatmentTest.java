package com.github.cerealklla.audyble.voice;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceTreatmentTest {

    @Test
    void normalIsPassthrough() {
        assertTrue(VoiceTreatment.normal().isPassthrough());
    }

    @Test
    void inaudibleIsNotPassthrough() {
        assertFalse(VoiceTreatment.inaudible().isPassthrough());
    }

    @Test
    void muffledIsNotPassthrough() {
        assertFalse(VoiceTreatment.muffled(0.5f, 1000f).isPassthrough());
    }

    @Test
    void radioIsNotPassthrough() {
        assertFalse(VoiceTreatment.radio(0.9f, 3000f, 512f).isPassthrough());
    }

    @Test
    void normalWithDistanceOverrideIsNotPassthrough() {
        VoiceTreatment withOverride = new VoiceTreatment(VoiceTreatment.Kind.NORMAL, true, 1f, 0f, 10f);
        assertFalse(withOverride.isPassthrough());
    }
}
