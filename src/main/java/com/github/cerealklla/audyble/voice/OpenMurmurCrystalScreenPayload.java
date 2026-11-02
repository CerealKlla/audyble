package com.github.cerealklla.audyble.voice;

import com.github.cerealklla.audyble.AudybleMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server -> client, sent when a player right-clicks a Murmur Crystal -- carries the stack's
 * current state so the screen can pre-fill its on/off toggle and volume control. Mirrors Lyfe's
 * {@code knowledge.OpenWritingScreenPayload} shape exactly. Always the player's main-hand stack --
 * {@link MurmurCrystalItem#use} only reacts to a main-hand use, so there's no hand to round-trip.
 */
public record OpenMurmurCrystalScreenPayload(MurmurCrystalState state) implements CustomPacketPayload {

    public static final Type<OpenMurmurCrystalScreenPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(AudybleMod.MODID, "open_murmur_crystal_screen"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenMurmurCrystalScreenPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(MurmurCrystalState.CODEC), OpenMurmurCrystalScreenPayload::state,
            OpenMurmurCrystalScreenPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
