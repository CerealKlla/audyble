package com.github.cerealklla.audyble.npc;

/**
 * The one short, hand-authored (not LLM-generated) World Lore document injected into every NPC's
 * system prompt alongside its own personal backstory -- Phase 5b's "Cross-NPC consistency" mechanic,
 * a shared baseline setting with zero runtime cross-NPC coordination needed. Milestone 5 of the
 * approved implementation plan, pulled forward into Milestone 2 since the birth-prompt construction
 * needs real text here to be meaningfully testable -- flagged placeholder/early-draft content, same
 * footing as every other first-pass flavor text in this codebase (e.g. {@code tavern
 * .TavernGearRoller}), worth a real editorial pass later rather than treated as final.
 */
public final class WorldLore {

    public static final String TEXT = """
            This is a world of small, hard-won settlements spread across wild, mostly-unmapped land --
            forests, rivers, mountains, and the occasional ruin of something older that nobody fully
            remembers. Most people live and die within a day's walk of where they were born. Travel
            between settlements is real but not casual: roads are a settlement's own investment, not a
            given, and a trip to the next town is worth talking about afterward.

            Settlements govern themselves -- a Town Planner or two, a handful of tradespeople, hired
            or volunteer guards. There is no overarching kingdom or central authority anyone defers
            to; a settlement's reputation is built locally, one dealing at a time. Money is Gold
            Nuggets, trade is common and expected, and most people are plainly practical about it --
            a fair price matters more than charity.

            Monsters are a real, ordinary hazard, not a myth -- the usual kinds come out at night or
            in the wild places, and most settlements keep some kind of defense against them. Magic
            exists but is uncommon and mostly practical (a staff that mends wounds, a stone that
            remembers a place) rather than spectacular. Most people have never seen real magic
            up close and are a little wary of those who use it.

            People here are generally plainspoken, a little guarded with strangers until they've
            earned trust, and quick to help a neighbor in a real emergency. Humor tends dry rather
            than broad. Nobody here has ever heard of "the internet," modern technology, or any
            real-world country -- keep references grounded in this world only.
            """;

    private WorldLore() {
    }
}
