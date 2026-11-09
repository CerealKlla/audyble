package com.github.cerealklla.audyble.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.github.cerealklla.audyble.npc.ConversationalNpcConfig;
import com.github.cerealklla.audyble.npc.NpcAttachments;
import com.github.cerealklla.audyble.npc.NpcConversationEngine;
import com.github.cerealklla.audyble.npc.NpcReplyInterceptor;
import com.github.cerealklla.audyble.npc.NpcReplyInterceptors;
import com.github.cerealklla.audyble.npc.PlayerPronunciationStore;
import com.github.cerealklla.audyble.voice.MurmurPlaylistStore;
import com.github.cerealklla.audyble.voice.MusicLibrary;
import com.github.cerealklla.audyble.voice.SongRequestFile;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

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

    /**
     * Permanently binds {@code entity} as a conversational NPC -- same real mechanism the
     * {@code /audyble npc bind} debug command uses, exposed here for a caller that wants to set this
     * up structurally in code (e.g. Arryval's tutorial guide) rather than as an admin one-off.
     * {@code systemPrompt} drives the character's actual behavior/content -- see {@code
     * npc.NpcConversationEngine#buildSystemPrompt} for everything else automatically prepended
     * (spoken-dialogue-only instruction, world lore, any resolved Settlemynts identity).
     */
    public static void bindConversationalNpc(Entity entity, String characterName, String systemPrompt) {
        entity.setData(NpcAttachments.CONVERSATIONAL_NPC, new ConversationalNpcConfig(characterName, systemPrompt));
    }

    /**
     * Feeds {@code message} directly into the conversation pipeline as if {@code player} had said it
     * out loud to whichever conversational NPC is currently nearest them (same proximity resolution
     * voice capture uses) -- the no-microphone text fallback. Never touches Whisper (it's already
     * text) or public chat (never broadcast anywhere) -- the caller is responsible for not having
     * broadcast {@code message} itself, this method only ever sends the NPC's own reply back to
     * {@code player} privately.
     *
     * @return {@code false} if no conversational NPC is currently in range at all.
     */
    public static boolean sayToNearestNpc(ServerPlayer player, String message) {
        return NpcConversationEngine.instance() != null && NpcConversationEngine.instance().sayToNearestNpc(player, message);
    }

    /** See {@link NpcReplyInterceptor}'s own doc -- lets a caller supply guaranteed, non-improvised replies for specific turns of its own NPC. */
    public static void registerReplyInterceptor(NpcReplyInterceptor interceptor) {
        NpcReplyInterceptors.register(interceptor);
    }

    /**
     * Makes {@code npc} say {@code line} to {@code player} right now -- not in response to anything
     * the player said (see {@code NpcConversationEngine#speakScriptedLine}'s own doc). For a caller's
     * own proactive, guaranteed line (e.g. a greeting the instant a player comes into range), not a
     * reply to anything. {@code npc} must already be bound conversational ({@link
     * #bindConversationalNpc}) for this to do anything.
     */
    public static void speakScriptedLine(ServerPlayer player, Entity npc, String line) {
        if (NpcConversationEngine.instance() != null) {
            NpcConversationEngine.instance().speakScriptedLine(player.getUUID(), npc.getUUID(), line);
        }
    }

    /** See {@code npc.NpcConversationEngine#isInConversation}'s own doc. {@code false} if the pipeline hasn't started at all (e.g. the engine not yet initialized). */
    public static boolean isInConversation(UUID playerId) {
        return NpcConversationEngine.instance() != null && NpcConversationEngine.instance().isInConversation(playerId);
    }
}
