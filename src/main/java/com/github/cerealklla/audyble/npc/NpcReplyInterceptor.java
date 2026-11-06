package com.github.cerealklla.audyble.npc;

import java.util.Optional;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Open registration, no pairwise dependency needed -- same "open registry" shape as Nuclyus's own
 * {@code menu.client.MenuContributor}/{@code ping.Pingable} and TinyWyrlds' own {@code
 * instance.WyrldExitHandler}. Registered via {@code api.Audyble#registerReplyInterceptor}, checked
 * by {@code NpcConversationEngine} before every real LLM call -- lets a caller (e.g. Arryval's
 * staged tutorial intro) supply a guaranteed, non-improvised reply for specific turns of its own
 * NPC without needing its own copy of the Whisper/TTS/history plumbing. Takes the live, already-
 * resolved {@code player}/{@code npc} directly (not bare ids) since every real implementation needs
 * to inspect at least one of them (e.g. "is this my own entity type, and what stage is it in").
 */
public interface NpcReplyInterceptor {

    /**
     * @return a literal reply to speak/show instead of calling the LLM, or empty to let this turn
     * fall through to the real conversation (checked in registration order -- the first interceptor
     * to return a value wins, and no later one or the LLM itself is consulted).
     */
    Optional<String> tryIntercept(ServerPlayer player, Entity npc, String transcript);
}
