package com.github.cerealklla.audyble.bridge;

import java.util.Optional;
import java.util.UUID;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.fml.ModList;

/**
 * Audyble's only reference point into Settlemynts, which now owns all persistent NPC identity/
 * relationship-memory storage -- see Settlemynts' own {@code npc.NpcIdentityStore} class doc for the
 * full 2026-11-05 ownership-move writeup (moved here from this mod's own now-deleted {@code
 * npc.NpcIdentityStore}/{@code PlayerRelationshipStore}, per explicit user direction: "Audyble is for
 * conveying sound/text from player to npc and back. Settlemynt spawns the NPCs, moves the NPCs, and
 * should therefore be managing their history"). Every public method here returns/accepts only
 * vanilla or this-class-local types ({@link IdentitySnapshot} is declared in this file, not
 * Settlemynts') -- no Settlemynts type (e.g. its own {@code npc.NpcIdentityKey}) is ever allowed to
 * leak out into a general Audyble class like {@code npc.NpcConversationEngine}, same isolation
 * discipline the root project's own CLAUDE.md documents (a class touched unconditionally, like this
 * mod's conversation engine always is, must never reference an optional dependency's type directly,
 * even behind a runtime guard -- only a dedicated bridge class like this one may).
 */
public final class SettlemyntsIdentityBridge {

    private SettlemyntsIdentityBridge() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded("settlemynts");
    }

    /**
     * A plain, Audyble-local copy of Settlemynts' {@code api.Settlemynts.NpcIdentitySnapshot} -- see
     * this class's own doc for why the real Settlemynts type never crosses this boundary. {@code
     * ownerId}/{@code roleName} together are the opaque handle a caller holds onto for later
     * relationship reads/writes, without needing the live entity again. {@code genderName} is {@code
     * npc.NpcGender}'s plain enum name ("MALE"/"FEMALE"), added 2026-11-05 so the conversation engine
     * can pick a matching Kokoro voice -- see {@code npc.NpcConversationEngine}.
     */
    public record IdentitySnapshot(UUID ownerId, String roleName, String name, String backstory, String keyFactsPromptText, String genderName) {
    }

    /**
     * Resolves {@code entity}'s persistent NPC identity -- a real plot-bound worker/Guard if
     * Settlemynts recognizes it, otherwise a generic per-entity-UUID "wandering" identity (covers a
     * real Wandering Trader, or a manually {@code /audyble npc bind}-bound test entity) -- creating
     * one (and kicking off async name/backstory generation) if this is the first time. Empty only if
     * Settlemynts isn't loaded at all -- in that case the caller should fall back to an ephemeral,
     * non-persistent identity (no NPC-history system exists to consult without Settlemynts present).
     */
    public static Optional<IdentitySnapshot> resolveOrBeginBirth(ServerLevel level, Entity entity) {
        if (!isLoaded()) {
            return Optional.empty();
        }
        com.github.cerealklla.settlemynts.api.Settlemynts.NpcIdentitySnapshot snapshot =
                com.github.cerealklla.settlemynts.api.Settlemynts.resolveOrBeginNpcIdentity(level, entity);
        return Optional.of(new IdentitySnapshot(snapshot.ownerId(), snapshot.roleName(), snapshot.name(),
                snapshot.backstory(), snapshot.keyFactsPromptText(), snapshot.genderName()));
    }

    /** {@code playerId}'s current relationship summary with the NPC identified by {@code handle}, or blank if they've never spoken before, Settlemynts isn't loaded, or {@code handle} is {@code null} (no identity was ever resolved for this conversation). */
    public static String getRelationshipSummary(IdentitySnapshot handle, UUID playerId) {
        if (!isLoaded() || handle == null) {
            return "";
        }
        return com.github.cerealklla.settlemynts.api.Settlemynts.getNpcRelationshipSummary(handle.ownerId(), handle.roleName(), playerId);
    }

    /** Persists a freshly LLM-summarized relationship between {@code playerId} and the NPC identified by {@code handle} -- a no-op if Settlemynts isn't loaded or {@code handle} is {@code null}. */
    public static void putRelationshipSummary(ServerLevel level, IdentitySnapshot handle, UUID playerId, String newSummary) {
        if (!isLoaded() || handle == null) {
            return;
        }
        com.github.cerealklla.settlemynts.api.Settlemynts.putNpcRelationshipSummary(level, handle.ownerId(), handle.roleName(), playerId, newSummary);
    }

    /**
     * {@code entity}'s real conversational *eligibility* as a Settlemynts-spawned worker/Guard --
     * deliberately narrower than {@link #resolveOrBeginBirth} (which also assigns a generic
     * "wandering" identity to anything else) -- this is what gates whether an arbitrary entity is
     * conversational at all, so it must only ever match a real worker/Guard, never fall back to
     * "everything is resolvable."
     */
    public static boolean isRecognizedWorkerOrGuard(Entity entity) {
        return isLoaded()
                && (com.github.cerealklla.settlemynts.api.Settlemynts.resolvePlotWorkerIdentity(entity).isPresent()
                        || com.github.cerealklla.settlemynts.api.Settlemynts.resolveGuardStandId(entity).isPresent());
    }

    /** {@code entity}'s real settlement name, if it's a Settlemynts plot-bound worker/guard with one -- see {@code npc.NpcConversationEngine#buildSystemPrompt}. */
    public static Optional<String> resolveSettlementName(Entity entity) {
        if (!isLoaded()) {
            return Optional.empty();
        }
        return com.github.cerealklla.settlemynts.api.Settlemynts.resolveSettlementName(entity);
    }
}
