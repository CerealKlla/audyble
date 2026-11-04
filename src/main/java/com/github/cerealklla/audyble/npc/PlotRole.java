package com.github.cerealklla.audyble.npc;

import com.mojang.serialization.Codec;

/**
 * What kind of NPC an {@link NpcIdentityKey} identifies -- Phase 5b (context/design-document.md).
 * The five plot-bound roles (keyed by plot id, or by Guard Kit Stand id for {@link #GUARD} -- see
 * {@link NpcIdentityKey}'s own doc for why Guard needs a different key source) plus {@link #WANDERING}
 * for an entity-UUID-keyed wandering NPC (Wandering Trader, or a manually {@code /audyble npc bind}-
 * bound standalone test entity with no plot at all).
 *
 * <p>Deliberately not importing or depending on Settlemynts' own {@code zone.PlotWorkerKind.Kind} --
 * Audyble's dependency direction stays clean (it already depends on Settlemynts for Murmur Tower
 * signal lookup; a new Settlemynts -> Audyble dependency for identity wiring must not create a cycle
 * through a shared type). Settlemynts' own bridge class translates its role concept into this enum at
 * the call site instead.
 */
public enum PlotRole {
    FARMER, LUMBERJACK, MINER, RESIDENT, GUARD, WANDERING;

    public static final Codec<PlotRole> CODEC = Codec.STRING.xmap(
            s -> PlotRole.valueOf(s.toUpperCase(java.util.Locale.ROOT)), Enum::name);
}
