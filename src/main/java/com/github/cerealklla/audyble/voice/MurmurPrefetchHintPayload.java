package com.github.cerealklla.audyble.voice;

import java.util.List;

import com.github.cerealklla.audyble.AudybleMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server -> client, sent once a player with an active Murmur Crystal enters a station's wider
 * prefetch radius but before they're actually in audible range (2026-11-02 rework) -- just enough
 * info for the client to start downloading {@code trackIds} (via {@code voice.MusicHttpServer} on
 * {@code httpPort}, same host as the Minecraft connection) in the background, with no position
 * sync and no "start playing" signal. By the time the player actually crosses into audible range
 * and gets a real {@link MurmurTunedInPayload}, the file should already be cached, so playback
 * starts with zero fetch delay.
 */
public record MurmurPrefetchHintPayload(List<String> trackIds, int httpPort) implements CustomPacketPayload {

    public static final Type<MurmurPrefetchHintPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(AudybleMod.MODID, "murmur_prefetch_hint"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MurmurPrefetchHintPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), MurmurPrefetchHintPayload::trackIds,
            ByteBufCodecs.VAR_INT, MurmurPrefetchHintPayload::httpPort,
            MurmurPrefetchHintPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
