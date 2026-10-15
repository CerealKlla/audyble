package com.github.cerealklla.audyble.voice;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;

/**
 * Phase 2 -- turns a speaker's raw opus audio into what one specific listener should receive, per
 * {@link VoiceTreatment}. Decode/filter/encode is done independently per (speaker, listener) pair
 * rather than once per speaker, because each listener can get a genuinely different treatment
 * (different gain, different filtering) -- see design-document.md section 4. This costs more CPU
 * than a single shared decode would (O(speakers x listeners) instead of O(speakers)), an accepted
 * tradeoff for genuine per-listener audio, flagged as an open scaling concern in decisions.md.
 *
 * <p>Opus codec instances are stateful (continuity for packet-loss concealment/FEC across frames),
 * so each pair keeps its own persistent decoder/encoder/filter-state rather than creating fresh ones
 * per packet -- each one only ever sees that one speaker's frame sequence, in order, so this is
 * correct, not just convenient.</p>
 */
public final class AudioProcessor {

    /** SVC's own opus audio is 48kHz mono. */
    private static final int SAMPLE_RATE = 48000;

    private record PairKey(UUID speaker, UUID listener) {
    }

    private static final class FilterState {
        float lastOutput;
    }

    private final VoicechatApi api;
    private final Map<PairKey, OpusDecoder> decoders = new ConcurrentHashMap<>();
    private final Map<PairKey, OpusEncoder> encoders = new ConcurrentHashMap<>();
    private final Map<PairKey, FilterState> filterStates = new ConcurrentHashMap<>();

    public AudioProcessor(VoicechatApi api) {
        this.api = api;
    }

    /** Decodes, applies the treatment's gain/filter, and re-encodes -- only call when actually needed
     * (see {@link VoiceTreatment#isPassthrough()}; the caller should skip this entirely otherwise). */
    public byte[] process(UUID speaker, UUID listener, byte[] opusData, VoiceTreatment treatment) {
        PairKey key = new PairKey(speaker, listener);
        OpusDecoder decoder = decoders.computeIfAbsent(key, k -> api.createDecoder());
        OpusEncoder encoder = encoders.computeIfAbsent(key, k -> api.createEncoder());

        short[] pcm = decoder.decode(opusData);

        if (treatment.lowPassCutoffHz() > 0) {
            FilterState state = filterStates.computeIfAbsent(key, k -> new FilterState());
            pcm = lowPass(pcm, treatment.lowPassCutoffHz(), state);
        }
        if (treatment.volumeMultiplier() != 1f) {
            applyGain(pcm, treatment.volumeMultiplier());
        }

        return encoder.encode(pcm);
    }

    /** One-pole IIR low-pass filter -- cheap, stateful across frames via the caller-supplied state. */
    private short[] lowPass(short[] pcm, float cutoffHz, FilterState state) {
        double rc = 1.0 / (2.0 * Math.PI * cutoffHz);
        double dt = 1.0 / SAMPLE_RATE;
        double alpha = dt / (rc + dt);
        float last = state.lastOutput;
        short[] out = new short[pcm.length];
        for (int i = 0; i < pcm.length; i++) {
            last = (float) (last + alpha * (pcm[i] - last));
            out[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(last)));
        }
        state.lastOutput = last;
        return out;
    }

    private void applyGain(short[] pcm, float gain) {
        for (int i = 0; i < pcm.length; i++) {
            int scaled = Math.round(pcm[i] * gain);
            pcm[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, scaled));
        }
    }

    /** Releases the codec pair for one (speaker, listener) combination -- call on disconnect. */
    public void forgetPlayer(UUID playerId) {
        decoders.entrySet().removeIf(e -> {
            boolean match = involves(e.getKey(), playerId);
            if (match) {
                e.getValue().close();
            }
            return match;
        });
        encoders.entrySet().removeIf(e -> {
            boolean match = involves(e.getKey(), playerId);
            if (match) {
                e.getValue().close();
            }
            return match;
        });
        filterStates.keySet().removeIf(k -> involves(k, playerId));
    }

    private boolean involves(PairKey key, UUID playerId) {
        return key.speaker().equals(playerId) || key.listener().equals(playerId);
    }
}
