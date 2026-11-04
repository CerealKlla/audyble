package com.github.cerealklla.audyble.npc;

import com.github.cerealklla.audyble.api.Audyble;

import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * Marks a {@link WanderingTrader}'s {@link PlotRole#WANDERING} identity permanently deceased the
 * instant it dies -- Phase 5b Milestone 3, the real body of the "deceased characters registry" the
 * design doc describes (see {@link NpcIdentityStore#deceased()}). Unlike a plot-bound worker, this
 * identity is never reassigned to a replacement body -- a wandering NPC's death is final.
 */
public final class WanderingDeathListener {

    @SubscribeEvent
    public void onDeath(LivingDeathEvent event) {
        if (event.getEntity().level().isClientSide() || !(event.getEntity() instanceof WanderingTrader trader)) {
            return;
        }
        String deathContext = trader.getCombatTracker().getDeathMessage().getString();
        Audyble.markDeceased(NpcIdentityKey.wandering(trader.getUUID()), deathContext);
    }
}
