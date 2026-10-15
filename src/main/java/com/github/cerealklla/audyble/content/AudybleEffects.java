package com.github.cerealklla.audyble.content;

import com.github.cerealklla.audyble.AudybleMod;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffectCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Phase 4 gameplay-driven hearing: the two custom status effects (design-document.md section 6). */
public final class AudybleEffects {

    private AudybleEffects() {
    }

    public static final DeferredRegister<net.minecraft.world.effect.MobEffect> MOB_EFFECTS =
            DeferredRegister.create(Registries.MOB_EFFECT, AudybleMod.MODID);

    public static final DeferredHolder<net.minecraft.world.effect.MobEffect, DeafenedMobEffect> DEAFENED =
            MOB_EFFECTS.register("deafened", () -> new DeafenedMobEffect(MobEffectCategory.HARMFUL, 0x2B2B2B));

    public static final DeferredHolder<net.minecraft.world.effect.MobEffect, KeenHearingMobEffect> KEEN_HEARING =
            MOB_EFFECTS.register("keen_hearing", () -> new KeenHearingMobEffect(MobEffectCategory.BENEFICIAL, 0x9FE2BF));
}
