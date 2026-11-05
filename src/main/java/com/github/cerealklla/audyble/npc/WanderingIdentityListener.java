package com.github.cerealklla.audyble.npc;

import com.github.cerealklla.audyble.api.Audyble;

import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Births a {@link PlotRole#WANDERING} identity for every real vanilla {@link WanderingTrader} the
 * instant it's first seen -- Phase 5b Milestone 3. Keying by the trader's own stable entity UUID
 * means "first sighting of this UUID" is a correct, side-effect-free birth trigger regardless of
 * whether this is a genuinely fresh spawn or just a chunk reload of a trader that's existed for days
 * -- {@link Audyble#resolveOrBeginBirth} is itself idempotent (a fast-path lookup on every call after
 * the first), so no separate "is this really new" check is needed here at all.
 */
public final class WanderingIdentityListener {

    @SubscribeEvent
    public void onJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof WanderingTrader trader)) {
            return;
        }
        Audyble.resolveOrBeginBirth(NpcIdentityKey.wandering(trader.getUUID()), null, "");
    }
}
