package com.github.cerealklla.audyble.voice;

import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * One settlement's own curated Murmur Tower playlist -- the Mayor/Town Planner's picks from {@link
 * MusicLibrary}'s catalog, by track id. Keyed by {@code settlementPlaceId}, the same real
 * Cartographyr place id {@code bridge.SettlemyntsMurmurBridge.MurmurSignalInfo#settlementPlaceId()}
 * already resolves -- not a new identity concept.
 */
public record MurmurPlaylistEntry(long settlementPlaceId, List<String> trackIds) {

    public static final Codec<MurmurPlaylistEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.LONG.fieldOf("settlement_place_id").forGetter(MurmurPlaylistEntry::settlementPlaceId),
            Codec.STRING.listOf().fieldOf("track_ids").forGetter(MurmurPlaylistEntry::trackIds)
    ).apply(i, MurmurPlaylistEntry::new));
}
