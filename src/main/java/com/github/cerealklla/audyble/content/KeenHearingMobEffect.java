package com.github.cerealklla.audyble.content;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/**
 * Phase 4 "enhanced hearing" hook (design-document.md section 6) -- the counterpart to
 * {@link DeafenedMobEffect}. Pure marker, checked by
 * {@link com.github.cerealklla.audyble.voice.GameplayHearing}.
 */
public final class KeenHearingMobEffect extends MobEffect {

    public KeenHearingMobEffect(MobEffectCategory category, int color) {
        super(category, color);
    }
}
