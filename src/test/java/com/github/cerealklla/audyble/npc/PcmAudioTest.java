package com.github.cerealklla.audyble.npc;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PcmAudioTest {

    @Test
    void wavRoundTripPreservesSamplesAndSampleRate() {
        short[] samples = {0, 1, -1, 32767, -32768, 1234, -1234};
        byte[] wav = PcmAudio.toWav(samples, 48000);
        PcmAudio.DecodedWav decoded = PcmAudio.fromWav(wav);
        assertEquals(48000, decoded.sampleRate());
        assertArrayEquals(samples, decoded.samples());
    }

    @Test
    void resampleToSameRateReturnsInputUnchanged() {
        short[] samples = {1, 2, 3, 4, 5};
        assertArrayEquals(samples, PcmAudio.resample(samples, 48000, 48000));
    }

    @Test
    void resampleToHalfRateHalvesLength() {
        short[] samples = new short[1000];
        short[] resampled = PcmAudio.resample(samples, 48000, 24000);
        assertEquals(500, resampled.length);
    }

    @Test
    void resampleToDoubleRateDoublesLength() {
        short[] samples = new short[500];
        short[] resampled = PcmAudio.resample(samples, 24000, 48000);
        assertEquals(1000, resampled.length);
    }

    @Test
    void resampleOfSilenceStaysSilent() {
        short[] samples = new short[480];
        short[] resampled = PcmAudio.resample(samples, 48000, 16000);
        for (short sample : resampled) {
            assertEquals(0, sample);
        }
    }

    @Test
    void chunkSplitsIntoFixedSizeFrames() {
        short[] samples = new short[2500];
        List<short[]> frames = PcmAudio.chunk(samples, 960);
        assertEquals(3, frames.size());
        assertEquals(960, frames.get(0).length);
        assertEquals(960, frames.get(1).length);
        assertEquals(960, frames.get(2).length);
    }

    @Test
    void lastFrameIsZeroPadded() {
        short[] samples = new short[1000];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (short) (i + 1);
        }
        List<short[]> frames = PcmAudio.chunk(samples, 960);
        short[] last = frames.get(1);
        assertEquals(1000, last[39]);
        assertEquals(0, last[40]);
        assertEquals(0, last[959]);
    }

    @Test
    void wavRoundTripSurvivesRandomAudio() {
        Random random = new Random(42);
        short[] samples = new short[4800];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (short) random.nextInt();
        }
        byte[] wav = PcmAudio.toWav(samples, 16000);
        assertTrue(wav.length > 44);
        PcmAudio.DecodedWav decoded = PcmAudio.fromWav(wav);
        assertEquals(16000, decoded.sampleRate());
        assertArrayEquals(samples, decoded.samples());
    }
}
