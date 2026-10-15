package com.github.cerealklla.audyble.voice;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.github.cerealklla.audyble.api.AudybleApi;
import com.github.cerealklla.audyble.api.TreatmentModifier;
import com.github.cerealklla.audyble.config.AudybleConfig;

import net.minecraft.server.level.ServerPlayer;

/**
 * The core {@code Speaker -> Listener = treatment} decision (design-document.md section 4),
 * combining Phase 3 (world occlusion) and Phase 4 (gameplay hooks) in order, then running the
 * result through any externally-registered {@link TreatmentModifier}s.
 *
 * <p>Occlusion raycasting is real work (block sampling along the line between two players) run
 * once per (speaker, listener) pair per voice packet -- at 20ms/frame this would mean re-raycasting
 * every single frame for every pair in range, which doesn't scale. Results are cached per pair for
 * {@link #CACHE_MILLIS}, since block occlusion between two players doesn't meaningfully change
 * faster than that from normal movement.</p>
 */
public final class TreatmentEngine {

    private static final long CACHE_MILLIS = 250;

    private record PairKey(UUID speaker, UUID listener) {
    }

    private record CacheEntry(VoiceTreatment treatment, long computedAtMillis) {
    }

    private final ConcurrentHashMap<PairKey, CacheEntry> cache = new ConcurrentHashMap<>();

    public VoiceTreatment resolve(ServerPlayer speaker, ServerPlayer listener) {
        PairKey key = new PairKey(speaker.getUUID(), listener.getUUID());
        long now = System.currentTimeMillis();
        CacheEntry cached = cache.get(key);
        if (cached != null && (now - cached.computedAtMillis()) < CACHE_MILLIS) {
            return cached.treatment();
        }
        VoiceTreatment treatment = compute(speaker, listener);
        cache.put(key, new CacheEntry(treatment, now));
        return treatment;
    }

    private VoiceTreatment compute(ServerPlayer speaker, ServerPlayer listener) {
        VoiceTreatment treatment;

        if (GameplayHearing.isDeafened(listener)) {
            treatment = VoiceTreatment.inaudible();
        } else if (GameplayHearing.isHoldingRadio(speaker) && GameplayHearing.isHoldingRadio(listener)) {
            treatment = VoiceTreatment.radio(
                    (float) (double) AudybleConfig.RADIO_VOLUME.get(),
                    (float) (double) AudybleConfig.RADIO_CUTOFF_HZ.get(),
                    (float) (double) AudybleConfig.RADIO_RANGE.get());
        } else if (speaker.level() != listener.level()) {
            // Different dimensions -- can't hear each other regardless of any other rule.
            treatment = VoiceTreatment.inaudible();
        } else if (!AudybleConfig.OCCLUSION_ENABLED.get()) {
            treatment = VoiceTreatment.normal();
        } else {
            int occludingBlocks = AcousticOcclusion.countOccludingBlocks(
                    speaker.level(), speaker.getEyePosition(), listener.getEyePosition());
            if (GameplayHearing.hasKeenHearing(listener)) {
                occludingBlocks = Math.max(0, occludingBlocks - AudybleConfig.KEEN_HEARING_BLOCK_BONUS.get());
            }
            treatment = AcousticOcclusion.treatmentFor(occludingBlocks,
                    (float) (double) AudybleConfig.MUFFLE_VOLUME_PER_BLOCK.get(),
                    (float) (double) AudybleConfig.BASE_MUFFLE_CUTOFF_HZ.get());
        }

        List<TreatmentModifier> modifiers = AudybleApi.modifiers();
        for (TreatmentModifier modifier : modifiers) {
            treatment = modifier.modify(treatment, speaker.getUUID(), listener.getUUID());
        }
        return treatment;
    }

    /** Called on player disconnect to avoid an unbounded cache growing forever. */
    public void forgetPlayer(UUID playerId) {
        cache.keySet().removeIf(k -> k.speaker().equals(playerId) || k.listener().equals(playerId));
    }
}
