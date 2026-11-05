package com.github.cerealklla.audyble.npc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;

import com.github.cerealklla.audyble.api.Audyble;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Two commands for Phase 5b (context/design-document.md): a real player self-service command
 * ({@code /audyble pronunciation set}) and a dev/test debug command ({@code /audyble npc
 * debugidentity}) used to exercise {@link Audyble}'s store-backed facade -- including a real
 * persistence round trip across a server restart -- before any spawn ticker or conversation code
 * depends on it. Unguarded (no permission check), same precedent as {@code
 * NpcConversationEngine#register}'s existing {@code /audyble npc bind} in this same mod.
 */
public final class NpcDebugCommands {

    private NpcDebugCommands() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> register(event.getDispatcher()));
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var pronunciationSet = Commands.literal("set")
                .then(Commands.argument("hint", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String hint = StringArgumentType.getString(ctx, "hint");
                            UUID playerId = ctx.getSource().getPlayerOrException().getUUID();
                            Audyble.setPronunciation(playerId, hint);
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "Pronunciation hint set to \"" + hint + "\"."), false);
                            return 1;
                        }));
        var pronunciation = Commands.literal("pronunciation").then(pronunciationSet);

        var debugSetNode = Commands.literal("set")
                .then(Commands.argument("role", StringArgumentType.word())
                        .then(Commands.argument("ownerId", StringArgumentType.word())
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .then(Commands.argument("backstory", StringArgumentType.greedyString())
                                                .executes(NpcDebugCommands::debugSet)))));
        var debugGetNode = Commands.literal("get")
                .then(Commands.argument("role", StringArgumentType.word())
                        .then(Commands.argument("ownerId", StringArgumentType.word())
                                .executes(NpcDebugCommands::debugGet)));
        var debugDeceasedNode = Commands.literal("deceased").executes(NpcDebugCommands::debugDeceased);
        var debugIdentity = Commands.literal("debugidentity")
                .then(debugSetNode).then(debugGetNode).then(debugDeceasedNode);
        var npc = Commands.literal("npc").then(debugIdentity);

        dispatcher.register(Commands.literal("audyble").then(pronunciation).then(npc));
    }

    private static NpcIdentityKey parseKey(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        PlotRole role = PlotRole.valueOf(StringArgumentType.getString(ctx, "role").toUpperCase(java.util.Locale.ROOT));
        UUID ownerId = UUID.fromString(StringArgumentType.getString(ctx, "ownerId"));
        return new NpcIdentityKey(ownerId, role);
    }

    private static int debugSet(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        NpcIdentityKey key = parseKey(ctx);
        String name = StringArgumentType.getString(ctx, "name");
        String backstory = StringArgumentType.getString(ctx, "backstory");
        Optional<Long> existingSettlementPlaceId = Audyble.findIdentity(key).flatMap(NpcIdentity::settlementPlaceId);
        Audyble.putIdentity(new NpcIdentity(key, name, backstory, List.of(), true, Optional.empty(), existingSettlementPlaceId));
        ctx.getSource().sendSuccess(() -> Component.literal("Set identity " + key + " -> \"" + name + "\"."), false);
        return 1;
    }

    private static int debugGet(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        NpcIdentityKey key = parseKey(ctx);
        Optional<NpcIdentity> found = Audyble.findIdentity(key);
        ctx.getSource().sendSuccess(() -> Component.literal(found.isPresent()
                ? "Identity " + key + " = " + found.get() : "No identity found for " + key + "."), false);
        return 1;
    }

    private static int debugDeceased(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        List<NpcIdentity> deceased = Audyble.deceasedIdentities();
        ctx.getSource().sendSuccess(() -> Component.literal(deceased.isEmpty()
                ? "No deceased identities." : deceased.size() + " deceased: " + deceased), false);
        return 1;
    }
}
