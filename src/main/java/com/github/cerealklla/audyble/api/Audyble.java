package com.github.cerealklla.audyble.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.github.cerealklla.audyble.npc.LoreEntry;
import com.github.cerealklla.audyble.npc.NpcIdentity;
import com.github.cerealklla.audyble.npc.NpcIdentityKey;
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
 *
 * <p>This milestone (2026-11-03) covers the pure data layer only -- plain CRUD over the four Phase 5b
 * stores. The higher-level "claim a key and asynchronously generate a backstory via Ollama if this is
 * a genuinely new NPC" orchestration (the design doc's "born" concept) is deliberately NOT here yet --
 * that's Settlemynts spawn-ticker wiring, a later milestone, layered on top of this facade rather than
 * baked into it.
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
