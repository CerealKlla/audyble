package com.github.cerealklla.audyble.npc;

import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;

/**
 * The stable identity key for one NPC "character" -- Phase 5b (context/design-document.md). For a
 * plot-bound role (Farmer/Lumberjack/Miner/Resident), {@code ownerId} is that worker's owning plot's
 * own real {@code zone.PlotRecord#plotId}; confirmed directly against Settlemynts' source that this
 * is already a stable, globally-unique UUID in hand at every spawn ticker's call site, so no new
 * field on {@code PlotRecord} is needed at all to carry this. For {@link PlotRole#GUARD}, {@code
 * ownerId} is the *Guard Kit Stand's* own UUID instead -- a single plot can have multiple stands
 * (multiple guards), and a stand's UUID is already this suite's own de facto "slot identity"
 * ({@code guardhouse.GuardKitStandIndex}'s map key, {@code assignedGuardId}), so reusing it here needs
 * no new concept, just a different UUID source than the other four roles. For {@link
 * PlotRole#WANDERING}, {@code ownerId} is the NPC entity's own UUID (see {@link #wandering}) --
 * identity survives only as long as that specific entity does.
 */
public record NpcIdentityKey(UUID ownerId, PlotRole role) {

    public static final Codec<NpcIdentityKey> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("owner_id").forGetter(NpcIdentityKey::ownerId),
            PlotRole.CODEC.fieldOf("role").forGetter(NpcIdentityKey::role)
    ).apply(i, NpcIdentityKey::new));

    public static NpcIdentityKey wandering(UUID entityId) {
        return new NpcIdentityKey(entityId, PlotRole.WANDERING);
    }
}
