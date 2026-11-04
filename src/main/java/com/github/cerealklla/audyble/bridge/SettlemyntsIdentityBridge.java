package com.github.cerealklla.audyble.bridge;

import java.util.Optional;
import java.util.UUID;

import com.github.cerealklla.audyble.npc.NpcIdentityKey;
import com.github.cerealklla.audyble.npc.PlotRole;

import net.minecraft.world.entity.Entity;
import net.neoforged.fml.ModList;

/**
 * Audyble's third bridge class, same isolation convention as {@link SettlemyntsMurmurBridge}/
 * {@link LyfeKnowledgeBridge} -- the only place allowed to reference Settlemynts' types directly for
 * this purpose. Resolves an arbitrary entity Audyble's {@code npc.NpcConversationEngine} is holding a
 * conversation with back to its persistent {@link NpcIdentityKey}, for a real plot-bound worker or
 * Guard (see {@code api.Settlemynts#resolvePlotWorkerIdentity}/{@code #resolveGuardStandId}) -- the
 * conversation engine falls back to {@link NpcIdentityKey#wandering} itself when this returns empty
 * (not a real worker/guard: a Wandering Trader or a manually {@code /audyble npc bind}-bound test
 * entity), so this class never needs to know about that fallback case at all.
 */
public final class SettlemyntsIdentityBridge {

    private SettlemyntsIdentityBridge() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded("settlemynts");
    }

    public static Optional<NpcIdentityKey> resolveIdentityKey(Entity entity) {
        Optional<com.github.cerealklla.settlemynts.api.Settlemynts.PlotWorkerIdentity> worker =
                com.github.cerealklla.settlemynts.api.Settlemynts.resolvePlotWorkerIdentity(entity);
        if (worker.isPresent()) {
            PlotRole role = switch (worker.get().kind()) {
                case FARMER -> PlotRole.FARMER;
                case LUMBERJACK -> PlotRole.LUMBERJACK;
                case MINER -> PlotRole.MINER;
                case RESIDENT -> PlotRole.RESIDENT;
                case NONE -> null;
            };
            if (role != null) {
                return Optional.of(new NpcIdentityKey(worker.get().plotId(), role));
            }
        }
        Optional<UUID> standId = com.github.cerealklla.settlemynts.api.Settlemynts.resolveGuardStandId(entity);
        return standId.map(id -> new NpcIdentityKey(id, PlotRole.GUARD));
    }
}
