package com.github.cerealklla.audyble.voice;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.github.cerealklla.nuclyus.server.PersistedServerStore;

import net.minecraft.server.MinecraftServer;

/**
 * Persisted store of every settlement's own Murmur Tower playlist -- thin static facade over
 * {@link PersistedServerStore}, same shape as {@code npc.NpcIdentityStore}. {@link #generate()}
 * returns nothing -- a settlement has no playlist until its Mayor/Town Planner saves one, at which
 * point {@code voice.MusicLibrary#pickFromPlaylist} prefers it over the global random pick.
 */
public final class MurmurPlaylistStore {

    private static final PersistedServerStore<Long, MurmurPlaylistEntry> STORE =
            new PersistedServerStore<>("audyble_murmur_playlists.json", MurmurPlaylistEntry.CODEC, MurmurPlaylistEntry::settlementPlaceId) {
                @Override
                protected Map<Long, MurmurPlaylistEntry> generate() {
                    return Map.of();
                }
            };

    private MurmurPlaylistStore() {
    }

    public static void loadOrGenerate(MinecraftServer server) {
        STORE.loadOrGenerate(server);
    }

    public static List<String> getPlaylist(long settlementPlaceId) {
        return STORE.get(settlementPlaceId).map(MurmurPlaylistEntry::trackIds).orElse(List.of());
    }

    public static void setPlaylist(long settlementPlaceId, List<String> trackIds) {
        STORE.put(settlementPlaceId, new MurmurPlaylistEntry(settlementPlaceId, List.copyOf(trackIds)));
    }

    /** Appends {@code trackId} to {@code settlementPlaceId}'s playlist if it isn't already there -- used by the nightly request-processing job's RCON admin command. */
    public static void appendIfAbsent(long settlementPlaceId, String trackId) {
        List<String> current = getPlaylist(settlementPlaceId);
        if (!current.contains(trackId)) {
            List<String> updated = new ArrayList<>(current);
            updated.add(trackId);
            setPlaylist(settlementPlaceId, updated);
        }
    }
}
