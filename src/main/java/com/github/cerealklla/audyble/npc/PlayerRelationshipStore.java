package com.github.cerealklla.audyble.npc;

import java.util.Map;
import java.util.Optional;

import com.github.cerealklla.nuclyus.server.PersistedServerStore;

import net.minecraft.server.MinecraftServer;

/** Persisted store of every {@link PlayerRelationship} -- Phase 5b. Empty until the first conversation ever ends; see {@link PlayerRelationship#blank}. */
public final class PlayerRelationshipStore {

    private static final PersistedServerStore<RelationshipKey, PlayerRelationship> STORE =
            new PersistedServerStore<>("audyble_npc_relationships.json", PlayerRelationship.CODEC, PlayerRelationship::key) {
                @Override
                protected Map<RelationshipKey, PlayerRelationship> generate() {
                    return Map.of();
                }
            };

    private PlayerRelationshipStore() {
    }

    public static void loadOrGenerate(MinecraftServer server) {
        STORE.loadOrGenerate(server);
    }

    public static Optional<PlayerRelationship> find(RelationshipKey key) {
        return STORE.get(key);
    }

    public static PlayerRelationship put(PlayerRelationship relationship) {
        STORE.put(relationship.key(), relationship);
        return relationship;
    }
}
