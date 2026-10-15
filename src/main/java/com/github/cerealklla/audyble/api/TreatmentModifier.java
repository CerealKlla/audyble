package com.github.cerealklla.audyble.api;

import java.util.UUID;

import com.github.cerealklla.audyble.voice.VoiceTreatment;

/**
 * Extension point for other mods (or future Audyble code) to observe or adjust the per-pair voice
 * treatment Audyble's own engine has already computed, without Audyble needing to know they exist.
 *
 * <p>Registered via {@link AudybleApi#registerTreatmentModifier(TreatmentModifier)}. Modifiers run
 * in registration order, each receiving the treatment produced by the previous stage (Audyble's own
 * occlusion/gameplay rules run first). A modifier that only wants to observe (e.g. a future
 * moderation mod logging who-can-hear-whom) can simply return {@code current} unchanged.</p>
 */
@FunctionalInterface
public interface TreatmentModifier {

    /**
     * @param current  the treatment decided so far
     * @param speaker  the speaking player/entity's UUID
     * @param listener the listening player's UUID
     * @return the (possibly unchanged) treatment to use from here on
     */
    VoiceTreatment modify(VoiceTreatment current, UUID speaker, UUID listener);
}
