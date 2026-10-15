package com.github.cerealklla.audyble.api;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Public, stable entry point for other mods to plug into Audyble's per-listener voice pipeline.
 * This class is the only thing a future mod (e.g. the likely-future moderation mod split out of
 * the original design pitch, see context/design-document.md's Scope note) should need to touch.
 */
public final class AudybleApi {

    private static final List<TreatmentModifier> MODIFIERS = new CopyOnWriteArrayList<>();

    private AudybleApi() {
    }

    /** Registers a modifier that runs after Audyble's own built-in rules, in registration order. */
    public static void registerTreatmentModifier(TreatmentModifier modifier) {
        MODIFIERS.add(modifier);
    }

    public static void unregisterTreatmentModifier(TreatmentModifier modifier) {
        MODIFIERS.remove(modifier);
    }

    /** Package-private: Audyble's own engine calls this after its own rules have run. */
    public static List<TreatmentModifier> modifiers() {
        return MODIFIERS;
    }
}
