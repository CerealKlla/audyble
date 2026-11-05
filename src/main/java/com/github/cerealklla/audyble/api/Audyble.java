package com.github.cerealklla.audyble.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.github.cerealklla.audyble.npc.PlayerPronunciationStore;
import com.github.cerealklla.audyble.voice.MurmurPlaylistStore;
import com.github.cerealklla.audyble.voice.MusicLibrary;
import com.github.cerealklla.audyble.voice.SongRequestFile;

/**
 * Public, stable cross-mod facade for Audyble -- named {@code Audyble} to match this suite's own
 * established per-mod facade convention ({@code api.Lyfe}, {@code api.Settlemynts}, etc.), distinct
 * from {@link AudybleApi} (a narrower, pre-existing extension point just for the voice-treatment-
 * modifier pipeline).
 *
 * <p><b>Lost its whole NPC identity/relationship/lore surface, 2026-11-05</b> (see Settlemynts' own
 * {@code npc.NpcIdentityStore} class doc for the full ownership-move writeup) -- {@code findIdentity}/
 * {@code putIdentity}/{@code markDeceased}/{@code deceasedIdentities}/{@code resolveOrBeginBirth}/
 * {@code getRelationship}/{@code putRelationship}/{@code getOrAssignLore} are all gone; Settlemynts
 * now owns that data and exposes the equivalent surface on its own {@code api.Settlemynts} facade
 * (reached from this mod only through {@code bridge.SettlemyntsIdentityBridge}, never directly from a
 * general class). {@link #getPronunciation}/{@link #setPronunciation} stay here -- a player-keyed TTS
 * setting, not NPC history -- and the Murmur Tower radio methods are unrelated to any of this.
 */
public final class Audyble {

    private Audyble() {
    }

    public static Optional<String> getPronunciation(UUID playerId) {
        return PlayerPronunciationStore.get(playerId);
    }

    public static void setPronunciation(UUID playerId, String hint) {
        PlayerPronunciationStore.set(playerId, hint);
    }

    /** Every track currently found under the configured music source directory -- for a Radio tab's catalog picker. */
    public static List<MusicLibrary.TrackSummary> listCatalogTracks() {
        return MusicLibrary.listAllTracks();
    }

    /** {@code settlementPlaceId}'s own curated Murmur Tower playlist, or empty if none has been saved yet (today's global-random behavior applies). */
    public static List<String> getStationPlaylist(long settlementPlaceId) {
        return MurmurPlaylistStore.getPlaylist(settlementPlaceId);
    }

    /** Replaces {@code settlementPlaceId}'s whole playlist -- the Radio tab's "Save Playlist" action, one batch write. */
    public static void setStationPlaylist(long settlementPlaceId, List<String> trackIds) {
        MurmurPlaylistStore.setPlaylist(settlementPlaceId, trackIds);
    }

    /** Appends a Mayor/Town Planner's pasted song-link request to the external song-request queue file. */
    public static void appendSongRequest(long settlementPlaceId, String url, String requesterName) {
        SongRequestFile.append(settlementPlaceId, url, requesterName);
    }

    /** Whether the song-request box should be shown at all -- false when {@code murmurSongRequestFile} isn't configured. */
    public static boolean songRequestsEnabled() {
        return SongRequestFile.enabled();
    }
}
