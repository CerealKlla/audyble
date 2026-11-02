package com.github.cerealklla.audyble.registration;

import com.github.cerealklla.audyble.AudybleMod;
import com.github.cerealklla.audyble.voice.MurmurCrystalState;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Audyble's first data components -- mirrors Blueprynts' {@code registration.ModDataComponents} shape exactly. */
public final class ModDataComponents {

    private ModDataComponents() {
    }

    public static final DeferredRegister.DataComponents DATA_COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, AudybleMod.MODID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<MurmurCrystalState>> MURMUR_CRYSTAL_STATE =
            DATA_COMPONENTS.registerComponentType("murmur_crystal_state", builder -> builder
                    .persistent(MurmurCrystalState.CODEC)
                    .networkSynchronized(ByteBufCodecs.fromCodec(MurmurCrystalState.CODEC)));
}
