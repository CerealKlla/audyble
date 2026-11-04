package com.github.cerealklla.audyble.npc;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.github.cerealklla.nuclyus.server.PersistedServerStore;

import net.minecraft.server.MinecraftServer;

/** Persisted store of every player's own pronunciation hint -- Phase 5b. Empty until a player sets one. */
public final class PlayerPronunciationStore {

    private static final PersistedServerStore<UUID, PronunciationEntry> STORE =
            new PersistedServerStore<>("audyble_player_pronunciation.json", PronunciationEntry.CODEC, PronunciationEntry::playerId) {
                @Override
                protected Map<UUID, PronunciationEntry> generate() {
                    return Map.of();
                }
            };

    private PlayerPronunciationStore() {
    }

    public static void loadOrGenerate(MinecraftServer server) {
        STORE.loadOrGenerate(server);
    }

    public static Optional<String> get(UUID playerId) {
        return STORE.get(playerId).map(PronunciationEntry::hint);
    }

    public static void set(UUID playerId, String hint) {
        STORE.put(playerId, new PronunciationEntry(playerId, hint));
    }
}
