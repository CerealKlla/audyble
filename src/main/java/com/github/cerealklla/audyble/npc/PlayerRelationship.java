package com.github.cerealklla.audyble.npc;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * One player's running history with one NPC -- Phase 5b. {@code summary} is always a periodically
 * LLM-re-summarized account (never a raw transcript, design doc's own explicit requirement), bounded
 * in size regardless of how long the player and NPC have known each other -- re-summarized in place
 * by {@code npc.NpcConversationEngine}, not appended to forever.
 */
public record PlayerRelationship(RelationshipKey key, String summary, long lastUpdatedGameTime) {

    public static final Codec<PlayerRelationship> CODEC = RecordCodecBuilder.create(i -> i.group(
            RelationshipKey.CODEC.fieldOf("key").forGetter(PlayerRelationship::key),
            Codec.STRING.fieldOf("summary").forGetter(PlayerRelationship::summary),
            Codec.LONG.fieldOf("last_updated_game_time").forGetter(PlayerRelationship::lastUpdatedGameTime)
    ).apply(i, PlayerRelationship::new));

    public static PlayerRelationship blank(RelationshipKey key) {
        return new PlayerRelationship(key, "", 0L);
    }
}
