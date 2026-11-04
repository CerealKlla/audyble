package com.github.cerealklla.audyble.npc;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.github.cerealklla.nuclyus.server.PersistedServerStore;

import net.minecraft.server.MinecraftServer;

/**
 * Persisted store of every {@link NpcIdentity} ever born -- Phase 5b. Thin static facade over
 * {@link PersistedServerStore}, same shape as {@code Lyfe/socket/ServerRunewordStore}.
 * {@link #generate()} returns nothing -- identities are born lazily (see {@link
 * com.github.cerealklla.audyble.api.Audyble}), never pre-seeded, so a fresh server starts with an
 * empty file.
 */
public final class NpcIdentityStore {

    private static final PersistedServerStore<NpcIdentityKey, NpcIdentity> STORE =
            new PersistedServerStore<>("audyble_npc_identities.json", NpcIdentity.CODEC, NpcIdentity::key) {
                @Override
                protected Map<NpcIdentityKey, NpcIdentity> generate() {
                    return Map.of();
                }
            };

    private NpcIdentityStore() {
    }

    public static void loadOrGenerate(MinecraftServer server) {
        STORE.loadOrGenerate(server);
    }

    public static Optional<NpcIdentity> find(NpcIdentityKey key) {
        return STORE.get(key);
    }

    public static NpcIdentity put(NpcIdentity identity) {
        STORE.put(identity.key(), identity);
        return identity;
    }

    /** Every identity whose body has permanently died -- the design doc's "deceased characters registry," a plain filter over this one store rather than a separate store. */
    public static List<NpcIdentity> deceased() {
        return STORE.all().values().stream().filter(id -> !id.alive()).toList();
    }
}
