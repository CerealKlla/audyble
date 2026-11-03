package com.github.cerealklla.audyble.voice;

import java.util.List;

import com.github.cerealklla.audyble.AudybleMod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server -> client, sent whenever a player with an active Murmur Crystal is within a station's
 * real audible range -- on first tuning in, and again periodically (every scan) to correct any
 * local drift (2026-11-02 rework). Carries everything the client needs to mirror the station
 * locally and compute its own live volume/static: the current track + where into it the station
 * is right now, the upcoming queue (for continued prefetching), the tower's own position/range (so
 * the client can recompute distance every tick with no further network round-trip), and the HTTP
 * port to fetch files from.
 */
public record MurmurTunedInPayload(
        String trackId,
        String artist,
        String song,
        float secondsIntoTrack,
        List<String> upcomingTrackIds,
        int httpPort,
        BlockPos towerPos,
        float maxRangeBlocks) implements CustomPacketPayload {

    public static final Type<MurmurTunedInPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(AudybleMod.MODID, "murmur_tuned_in"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MurmurTunedInPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, MurmurTunedInPayload::trackId,
            ByteBufCodecs.STRING_UTF8, MurmurTunedInPayload::artist,
            ByteBufCodecs.STRING_UTF8, MurmurTunedInPayload::song,
            ByteBufCodecs.FLOAT, MurmurTunedInPayload::secondsIntoTrack,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), MurmurTunedInPayload::upcomingTrackIds,
            ByteBufCodecs.VAR_INT, MurmurTunedInPayload::httpPort,
            BlockPos.STREAM_CODEC, MurmurTunedInPayload::towerPos,
            ByteBufCodecs.FLOAT, MurmurTunedInPayload::maxRangeBlocks,
            MurmurTunedInPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
