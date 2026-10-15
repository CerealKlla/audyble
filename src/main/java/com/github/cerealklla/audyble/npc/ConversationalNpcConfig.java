package com.github.cerealklla.audyble.npc;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Phase 5 (lowest priority, see design-document.md's own Phase 5 section) -- the persisted, minimal
 * identity of a conversational NPC: who they are and how they should behave, attached to any entity
 * via {@link NpcAttachments#CONVERSATIONAL_NPC}. Conversation history is deliberately NOT part of
 * this (kept in {@link NpcConversationEngine}'s own in-memory map instead) -- losing rolling chat
 * history on a server restart is an acceptable simplification for a first pass; losing who an NPC
 * even is would not be.
 */
public record ConversationalNpcConfig(String characterName, String systemPrompt) {

    public static final Codec<ConversationalNpcConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("character_name").forGetter(ConversationalNpcConfig::characterName),
            Codec.STRING.fieldOf("system_prompt").forGetter(ConversationalNpcConfig::systemPrompt)
    ).apply(instance, ConversationalNpcConfig::new));
}
