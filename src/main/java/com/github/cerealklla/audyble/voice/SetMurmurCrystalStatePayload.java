package com.github.cerealklla.audyble.voice;

import com.github.cerealklla.audyble.AudybleMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client -> server, sent from {@code client.MurmurCrystalScreen} on every on/off toggle or volume change -- writes the new state onto the player's held main-hand Murmur Crystal. */
public record SetMurmurCrystalStatePayload(MurmurCrystalState state) implements CustomPacketPayload {

    public static final Type<SetMurmurCrystalStatePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(AudybleMod.MODID, "set_murmur_crystal_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetMurmurCrystalStatePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(MurmurCrystalState.CODEC), SetMurmurCrystalStatePayload::state,
            SetMurmurCrystalStatePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
