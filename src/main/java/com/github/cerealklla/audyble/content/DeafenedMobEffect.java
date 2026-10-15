package com.github.cerealklla.audyble.content;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/**
 * Phase 4 "player state" hook (design-document.md section 6): a pure marker status, checked by
 * {@link com.github.cerealklla.audyble.voice.GameplayHearing} -- carries no attribute modifiers or
 * per-tick behavior of its own, same pattern as Lyfe's WellRestedMobEffect.
 */
public final class DeafenedMobEffect extends MobEffect {

    public DeafenedMobEffect(MobEffectCategory category, int color) {
        super(category, color);
    }
}
