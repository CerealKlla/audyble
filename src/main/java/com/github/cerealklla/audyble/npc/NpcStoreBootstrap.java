package com.github.cerealklla.audyble.npc;

import com.github.cerealklla.audyble.voice.MurmurPlaylistStore;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

/**
 * Loads (or, on first boot, generates) this mod's persisted stores -- mirrors {@code Lyfe/socket/
 * RunewordBootstrap} exactly. Lost its {@code NpcIdentityStore}/{@code PlayerRelationshipStore}/
 * {@code SettlementLoreStore} calls, 2026-11-05 -- all three moved to Settlemynts, which now owns NPC
 * identity/memory storage (see Settlemynts' {@code npc.NpcIdentityStore}'s own class doc for the full
 * ownership-move writeup). {@link PlayerPronunciationStore} stays here -- a player-keyed TTS setting,
 * not NPC history.
 */
public final class NpcStoreBootstrap {

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        PlayerPronunciationStore.loadOrGenerate(event.getServer());
        MurmurPlaylistStore.loadOrGenerate(event.getServer());
    }
}
