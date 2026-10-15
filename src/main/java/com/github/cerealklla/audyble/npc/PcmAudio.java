package com.github.cerealklla.audyble.npc;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Small self-contained PCM/WAV utility for the Phase 5 NPC pipeline -- no audio library dependency,
 * same "byte-verified against the spec" approach as Blueprynts' own hand-built NBT writer. Needed
 * because the three external services each want/produce a different shape: Whisper wants a 16kHz
 * mono WAV file, Kokoro produces a 24kHz mono WAV file, and SVC's opus encoder wants raw 48kHz mono
 * 16-bit PCM in fixed 960-sample frames.
 */
public final class PcmAudio {

    private PcmAudio() {
    }

    public record DecodedWav(short[] samples, int sampleRate) {
    }

    /** Parses a canonical 16-bit PCM WAV file (the format both Kokoro and whisper.cpp use). */
    public static DecodedWav fromWav(byte[] wav) {
        ByteBuffer buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        buffer.position(24);
        int sampleRate = buffer.getInt();

        int pos = 12;
        int dataOffset = -1;
        int dataLength = 0;
        while (pos + 8 <= wav.length) {
            buffer.position(pos);
            int chunkId = buffer.getInt();
            int chunkSize = buffer.getInt();
            // "data" in little-endian bytes, read as a big-endian-style int literal
            if (chunkId == 0x61746164) {
                dataOffset = pos + 8;
                dataLength = chunkSize;
                break;
            }
            pos += 8 + chunkSize + (chunkSize % 2);
        }
        if (dataOffset < 0) {
            throw new IllegalArgumentException("No data chunk found in WAV file");
        }

        short[] samples = new short[dataLength / 2];
        buffer.position(dataOffset);
        for (int i = 0; i < samples.length; i++) {
            samples[i] = buffer.getShort();
        }
        return new DecodedWav(samples, sampleRate);
    }

    /** Encodes mono 16-bit PCM as a canonical WAV file. */
    public static byte[] toWav(short[] pcm, int sampleRate) {
        int dataLength = pcm.length * 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + dataLength);
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes());
        header.putInt(36 + dataLength);
        header.put("WAVE".getBytes());
        header.put("fmt ".getBytes());
        header.putInt(16); // fmt chunk size
        header.putShort((short) 1); // PCM
        header.putShort((short) 1); // mono
        header.putInt(sampleRate);
        header.putInt(sampleRate * 2); // byte rate (mono, 16-bit)
        header.putShort((short) 2); // block align
        header.putShort((short) 16); // bits per sample
        header.put("data".getBytes());
        header.putInt(dataLength);
        out.writeBytes(header.array());

        ByteBuffer dataBuffer = ByteBuffer.allocate(dataLength).order(ByteOrder.LITTLE_ENDIAN);
        for (short sample : pcm) {
            dataBuffer.putShort(sample);
        }
        out.writeBytes(dataBuffer.array());
        return out.toByteArray();
    }

    /** Simple linear-interpolation resampler -- adequate for speech, not intended for music fidelity. */
    public static short[] resample(short[] input, int fromRate, int toRate) {
        if (fromRate == toRate || input.length == 0) {
            return input;
        }
        int outputLength = (int) ((long) input.length * toRate / fromRate);
        short[] output = new short[outputLength];
        double step = (double) fromRate / toRate;
        for (int i = 0; i < outputLength; i++) {
            double sourceIndex = i * step;
            int lower = (int) sourceIndex;
            int upper = Math.min(lower + 1, input.length - 1);
            double frac = sourceIndex - lower;
            output[i] = (short) Math.round(input[lower] * (1 - frac) + input[upper] * frac);
        }
        return output;
    }

    /** Splits PCM into fixed-size frames (SVC's AudioPlayer requires exactly 960 samples/frame), zero-padding the last. */
    public static List<short[]> chunk(short[] pcm, int frameSize) {
        List<short[]> frames = new ArrayList<>();
        for (int offset = 0; offset < pcm.length; offset += frameSize) {
            short[] frame = new short[frameSize];
            int length = Math.min(frameSize, pcm.length - offset);
            System.arraycopy(pcm, offset, frame, 0, length);
            frames.add(frame);
        }
        return frames;
    }
}
