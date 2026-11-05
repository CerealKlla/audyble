package com.github.cerealklla.audyble.voice;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * One narrowly-scoped command, {@code /audyble murmur assign <settlementPlaceId> <trackId>} -- the
 * live-apply step the external nightly song-request-processing PowerShell job triggers over RCON
 * (this suite's established mechanism for a script outside the JVM to make an already-running
 * server apply a change immediately, see {@code tools\run-rcon-command.ps1}) right after it drops a
 * freshly-downloaded track into the music source directory. Invalidates {@link MusicLibrary}'s
 * directory-scan cache so the new file is visible immediately, then records it against the
 * settlement's playlist -- the station itself picks it up on its own next natural refill (no
 * explicit per-station invalidation needed, see {@code MurmurStation}).
 */
public final class MurmurAdminCommands {

    private MurmurAdminCommands() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> register(event.getDispatcher()));
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var assign = Commands.literal("assign")
                .then(Commands.argument("settlementPlaceId", LongArgumentType.longArg())
                        .then(Commands.argument("trackId", StringArgumentType.greedyString())
                                .executes(MurmurAdminCommands::assign)));
        dispatcher.register(Commands.literal("audyble")
                .then(Commands.literal("murmur").then(assign)));
    }

    private static int assign(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        long settlementPlaceId = LongArgumentType.getLong(ctx, "settlementPlaceId");
        String trackId = StringArgumentType.getString(ctx, "trackId");
        MusicLibrary.invalidateCache();
        MurmurPlaylistStore.appendIfAbsent(settlementPlaceId, trackId);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Assigned \"" + trackId + "\" to settlement " + settlementPlaceId + "."), false);
        return 1;
    }
}
