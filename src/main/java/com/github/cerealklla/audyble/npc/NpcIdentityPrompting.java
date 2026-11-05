package com.github.cerealklla.audyble.npc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.github.cerealklla.audyble.AudybleMod;
import com.github.cerealklla.audyble.config.AudybleConfig;

/**
 * Builds the one-shot "a brand-new NPC is born" prompt and parses Ollama's reply -- Phase 5b
 * Milestone 2. Deliberately separate from {@link NpcConversationEngine}'s own conversation-turn
 * prompt building, which is a different shape (ongoing back-and-forth vs. one structured generation).
 * A dedicated single-thread executor, same shape as {@code NpcConversationEngine#pipelineExecutor},
 * so a slow/unreachable Ollama can never stall the server tick thread a birth was triggered from.
 */
public final class NpcIdentityPrompting {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "audyble-npc-birth"));

    private NpcIdentityPrompting() {
    }

    /** Called from {@link NpcConversationEngine#onServerStopping} -- see that method's own doc for why this matters. */
    public static void shutdown() {
        EXECUTOR.shutdownNow();
    }

    /**
     * Asynchronously generates a real name/backstory/key facts for {@code placeholder} via Ollama,
     * persisting the result (overwriting the placeholder) once it returns. A no-op beyond logging a
     * warning if Ollama is unreachable or the response can't be parsed -- the placeholder identity
     * (set by the caller before this was ever invoked) stays in place either way, so a bad/missing
     * response never leaves the NPC without SOME identity.
     */
    public static void generateAsync(NpcIdentity placeholder, LoreEntry lore, String settlementName) {
        EXECUTOR.submit(() -> {
            try {
                // Real settlement name (2026-11-04) -- without this, nothing in the birth prompt ever
                // names the actual town, so Ollama would invent/hallucinate its own place name instead
                // of using the real one. Blank/null (settlement name unresolvable, or a wandering NPC
                // with no settlement at all) just omits the sentence rather than naming a fake one.
                String settlementLine = (settlementName != null && !settlementName.isBlank())
                        ? "This settlement is called " + settlementName + ". "
                        : "";
                String systemPrompt = WorldLore.TEXT + "\n\nThis settlement: " + settlementLine + lore.text();
                String roleName = placeholder.key().role().name().toLowerCase(Locale.ROOT);
                String userMessage = "Generate a brand-new " + roleName + " character for this settlement. "
                        + "Respond in EXACTLY this format and nothing else -- no preamble, no extra commentary:\n"
                        + "NAME: <a short first and last name fitting this world>\n"
                        + "BACKSTORY: <2 to 4 sentences of personal backstory>\n"
                        + "FACT: <one short fact about them>\n"
                        + "FACT: <one short fact about them>\n"
                        + "FACT: <one short fact about them>";

                OllamaClient ollama = new OllamaClient(AudybleConfig.OLLAMA_BASE_URL.get(), AudybleConfig.OLLAMA_MODEL.get());
                String reply = ollama.chat(systemPrompt, List.of(), userMessage);
                NpcIdentity generated = parse(placeholder, reply);
                if (generated != null) {
                    NpcIdentityStore.put(generated);
                } else {
                    AudybleMod.LOGGER.warn("Audyble NPC birth generation returned an unparseable reply for {} -- leaving placeholder identity in place: {}",
                            placeholder.key(), reply);
                }
            } catch (Exception e) {
                AudybleMod.LOGGER.warn("Audyble NPC birth generation failed for {} -- leaving placeholder identity in place", placeholder.key(), e);
            }
        });
    }

    private static NpcIdentity parse(NpcIdentity placeholder, String reply) {
        String name = null;
        String backstory = null;
        List<String> facts = new ArrayList<>();
        for (String line : reply.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.regionMatches(true, 0, "NAME:", 0, 5)) {
                name = trimmed.substring(5).trim();
            } else if (trimmed.regionMatches(true, 0, "BACKSTORY:", 0, 10)) {
                backstory = trimmed.substring(10).trim();
            } else if (trimmed.regionMatches(true, 0, "FACT:", 0, 5)) {
                facts.add(trimmed.substring(5).trim());
            }
        }
        if (name == null || name.isBlank() || backstory == null || backstory.isBlank()) {
            return null;
        }
        return new NpcIdentity(placeholder.key(), name, backstory, List.copyOf(facts), true, Optional.empty());
    }
}
