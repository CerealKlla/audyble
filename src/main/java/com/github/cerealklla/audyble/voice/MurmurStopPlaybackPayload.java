package com.github.cerealklla.audyble.voice;

import com.github.cerealklla.audyble.AudybleMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server -> client, sent once when a player's Murmur Crystal is turned off or walks out of a
 * station's audible range entirely -- tells the client to stop local playback (see {@code
 * voice.client.ClientMurmurPlayback#stop}). No payload data needed.
 */
public record MurmurStopPlaybackPayload() implements CustomPacketPayload {

    public static final Type<MurmurStopPlaybackPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(AudybleMod.MODID, "murmur_stop_playback"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MurmurStopPlaybackPayload> STREAM_CODEC =
            StreamCodec.unit(new MurmurStopPlaybackPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
