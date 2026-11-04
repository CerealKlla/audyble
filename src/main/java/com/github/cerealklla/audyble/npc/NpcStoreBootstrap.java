package com.github.cerealklla.audyble.npc;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

/** Loads (or, on first boot, generates) Phase 5b's four persisted NPC-identity stores -- mirrors {@code Lyfe/socket/RunewordBootstrap} exactly. */
public final class NpcStoreBootstrap {

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        NpcIdentityStore.loadOrGenerate(event.getServer());
        PlayerRelationshipStore.loadOrGenerate(event.getServer());
        SettlementLoreStore.loadOrGenerate(event.getServer());
        PlayerPronunciationStore.loadOrGenerate(event.getServer());
    }
}
