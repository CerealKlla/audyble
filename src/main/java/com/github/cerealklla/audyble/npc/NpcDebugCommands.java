package com.github.cerealklla.audyble.npc;

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
 * A real player self-service command ({@code /audyble pronunciation set}) -- {@link Audyble}'s store-
 * backed pronunciation facade, the one piece of Phase 5b that stayed in this mod (a player-keyed TTS
 * setting, not NPC history -- see Settlemynts' {@code npc.NpcIdentityStore}'s own class doc for the
 * full ownership-move writeup of everything else). Lost its {@code npc debugidentity} subcommand,
 * 2026-11-05 -- moved to Settlemynts' own {@code /settlemynts npc debugidentity}, since that mod now
 * owns the store it was exercising. Unguarded (no permission check), same precedent as {@code
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

        dispatcher.register(Commands.literal("audyble").then(pronunciation));
    }
}
