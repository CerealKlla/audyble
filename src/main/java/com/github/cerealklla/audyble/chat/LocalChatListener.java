package com.github.cerealklla.audyble.chat;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;

import com.github.cerealklla.audyble.config.AudybleConfig;
import com.github.cerealklla.audyble.npc.NpcConversationEngine;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;

/**
 * Plain chat is local by default, matching this mod's whole positional-communication theme --
 * explicit user direction, 2026-11-06: "intercept all chat and only send it locally by default,
 * add a /all to talk to the entire world." Every real {@link ServerChatEvent} is cancelled (vanilla
 * would otherwise broadcast it server-wide) and instead delivered to every player within {@link
 * AudybleConfig#LOCAL_CHAT_RANGE} of the sender, including the sender themselves. The same message
 * is also fed to the nearest conversational NPC, if any is in its own (much tighter) {@link
 * AudybleConfig#NPC_INTERACTION_RANGE} -- so simply typing near a bound NPC (e.g. Arryval's
 * tutorial guide) talks to it, with no special command needed; {@code /audyble say} still exists
 * for a player who wants to guarantee reaching the NPC specifically without the local broadcast.
 * {@code /all} is the deliberate, explicit opt-out back to a real server-wide broadcast.
 */
public final class LocalChatListener {

    private LocalChatListener() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.register(new LocalChatListener());
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> register(event.getDispatcher()));
    }

    @SubscribeEvent
    public void onServerChat(ServerChatEvent event) {
        ServerPlayer sender = event.getPlayer();
        String text = event.getRawText();
        event.setCanceled(true);

        if (!(sender.level() instanceof ServerLevel level)) {
            return;
        }
        double rangeSqr = AudybleConfig.LOCAL_CHAT_RANGE.get() * AudybleConfig.LOCAL_CHAT_RANGE.get();
        Component localMessage = Component.literal("<" + sender.getGameProfile().name() + "> " + text);
        for (ServerPlayer nearby : level.players()) {
            if (nearby.distanceToSqr(sender) <= rangeSqr) {
                nearby.sendSystemMessage(localMessage);
            }
        }

        if (NpcConversationEngine.instance() != null) {
            NpcConversationEngine.instance().sayToNearestNpc(sender, text);
        }
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("all")
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
                                ctx.getSource().sendFailure(Component.literal("Only a player can use this."));
                                return 0;
                            }
                            String message = StringArgumentType.getString(ctx, "message");
                            Component broadcast = Component.literal("[All] <" + player.getGameProfile().name() + "> " + message)
                                    .withStyle(ChatFormatting.GOLD);
                            for (ServerPlayer p : ((ServerLevel) player.level()).getServer().getPlayerList().getPlayers()) {
                                p.sendSystemMessage(broadcast);
                            }
                            return 1;
                        })));
    }
}
