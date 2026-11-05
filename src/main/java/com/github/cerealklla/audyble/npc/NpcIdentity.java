package com.github.cerealklla.audyble.npc;

import java.util.List;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * One NPC character's persistent identity -- Phase 5b. {@code keyFacts} is a short list (design doc:
 * "3-5 discrete key facts"), one plain clause each (e.g. "Lost a brother to bandits five years ago"),
 * meant to be injected into an LLM prompt verbatim -- no structured schema, matching the design doc's
 * explicit "no RAG, pass everything in full" choice. {@code alive}/{@code deathContext} together are
 * the "deceased characters registry" the design doc describes: a dead identity is never deleted, just
 * flipped to {@code alive = false} with a short death context other NPCs can reference -- see {@link
 * NpcIdentityStore#deceased()}.
 */
public record NpcIdentity(NpcIdentityKey key, String name, String backstory, List<String> keyFacts,
                           boolean alive, Optional<String> deathContext, Optional<Long> settlementPlaceId) {

    public static final Codec<NpcIdentity> CODEC = RecordCodecBuilder.create(i -> i.group(
            NpcIdentityKey.CODEC.fieldOf("key").forGetter(NpcIdentity::key),
            Codec.STRING.fieldOf("name").forGetter(NpcIdentity::name),
            Codec.STRING.fieldOf("backstory").forGetter(NpcIdentity::backstory),
            Codec.STRING.listOf().fieldOf("key_facts").forGetter(NpcIdentity::keyFacts),
            Codec.BOOL.fieldOf("alive").forGetter(NpcIdentity::alive),
            Codec.STRING.optionalFieldOf("death_context").forGetter(NpcIdentity::deathContext),
            Codec.LONG.optionalFieldOf("settlement_place_id").forGetter(NpcIdentity::settlementPlaceId)
    ).apply(i, NpcIdentity::new));

    public NpcIdentity withDeceased(String deathContext) {
        return new NpcIdentity(key, name, backstory, keyFacts, false, Optional.of(deathContext), settlementPlaceId);
    }

    /** Lazily backfills a settlement onto an identity born before this field existed -- see {@code api.Audyble#resolveOrBeginBirth}. */
    public NpcIdentity withSettlementPlaceId(long settlementPlaceId) {
        return new NpcIdentity(key, name, backstory, keyFacts, alive, deathContext, Optional.of(settlementPlaceId));
    }

    /** Joins {@link #keyFacts} into a simple bullet list for direct concatenation into an LLM prompt. */
    public String keyFactsAsPromptText() {
        return keyFacts.isEmpty() ? "" : "- " + String.join("\n- ", keyFacts);
    }
}
