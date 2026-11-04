package com.github.cerealklla.audyble.api;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.github.cerealklla.audyble.npc.LoreEntry;
import com.github.cerealklla.audyble.npc.NpcIdentity;
import com.github.cerealklla.audyble.npc.NpcIdentityKey;
import com.github.cerealklla.audyble.npc.NpcIdentityPrompting;
import com.github.cerealklla.audyble.npc.NpcIdentityStore;
import com.github.cerealklla.audyble.npc.PlayerRelationship;
import com.github.cerealklla.audyble.npc.PlayerRelationshipStore;
import com.github.cerealklla.audyble.npc.PlayerPronunciationStore;
import com.github.cerealklla.audyble.npc.RelationshipKey;
import com.github.cerealklla.audyble.npc.SettlementLoreStore;

/**
 * Public, stable cross-mod facade for Audyble's Phase 5b NPC identity/memory system
 * (context/design-document.md) -- named {@code Audyble} to match this suite's own established
 * per-mod facade convention ({@code api.Lyfe}, {@code api.Settlemynts}, etc.), distinct from {@link
 * AudybleApi} (a narrower, pre-existing extension point just for the voice-treatment-modifier
 * pipeline, unrelated to NPC identity).
 */
public final class Audyble {

    private Audyble() {
    }

    public static Optional<NpcIdentity> findIdentity(NpcIdentityKey key) {
        return NpcIdentityStore.find(key);
    }

    public static NpcIdentity putIdentity(NpcIdentity identity) {
        return NpcIdentityStore.put(identity);
    }

    public static void markDeceased(NpcIdentityKey key, String deathContext) {
        NpcIdentityStore.find(key).ifPresent(identity -> NpcIdentityStore.put(identity.withDeceased(deathContext)));
    }

    public static List<NpcIdentity> deceasedIdentities() {
        return NpcIdentityStore.deceased();
    }

    /**
     * The "born" concept, Phase 5b Milestone 2 -- returns {@code key}'s existing identity if one
     * already exists (every respawn after the first), otherwise synchronously claims the key with a
     * placeholder identity (so a second concurrent caller this same tick can't double-trigger
     * generation) and kicks off an async Ollama call to fill in a real name/backstory/key facts (see
     * {@link NpcIdentityPrompting}), returning the placeholder immediately either way -- callers
     * should never block on this, and anything that needs the final name should read it live from
     * {@link #findIdentity} later rather than hold onto this call's own return value.
     *
     * @param settlementPlaceId the owning settlement's real Cartographyr place id, used to pull
     *                          generation context from {@link #getOrAssignLore} -- {@code null} for a
     *                          wandering NPC or any case with no real settlement to anchor to, in
     *                          which case a generic, unassigned lore stand-in is used instead.
     */
    public static NpcIdentity resolveOrBeginBirth(NpcIdentityKey key, Long settlementPlaceId) {
        Optional<NpcIdentity> existing = NpcIdentityStore.find(key);
        if (existing.isPresent()) {
            return existing.get();
        }
        String roleName = key.role().name();
        String displayRole = roleName.charAt(0) + roleName.substring(1).toLowerCase(Locale.ROOT);
        NpcIdentity placeholder = new NpcIdentity(key, "Unnamed " + displayRole, "", List.of(), true, Optional.empty());
        NpcIdentityStore.put(placeholder);
        LoreEntry lore = settlementPlaceId != null
                ? SettlementLoreStore.getOrAssign(settlementPlaceId)
                : new LoreEntry(-1, "An unremarkable settlement, like many others along the road.", Optional.empty());
        NpcIdentityPrompting.generateAsync(placeholder, lore);
        return placeholder;
    }

    /** The given player's current relationship with the given NPC, or a blank one if they've never spoken before -- never {@code null}. */
    public static PlayerRelationship getRelationship(NpcIdentityKey npcId, UUID playerId) {
        RelationshipKey key = new RelationshipKey(npcId, playerId);
        return PlayerRelationshipStore.find(key).orElseGet(() -> PlayerRelationship.blank(key));
    }

    public static PlayerRelationship putRelationship(PlayerRelationship relationship) {
        return PlayerRelationshipStore.put(relationship);
    }

    /** The given real Cartographyr place id's already-assigned lore entry, or permanently assigns and returns the first free pool entry. */
    public static LoreEntry getOrAssignLore(long placeId) {
        return SettlementLoreStore.getOrAssign(placeId);
    }

    public static Optional<String> getPronunciation(UUID playerId) {
        return PlayerPronunciationStore.get(playerId);
    }

    public static void setPronunciation(UUID playerId, String hint) {
        PlayerPronunciationStore.set(playerId, hint);
    }
}
