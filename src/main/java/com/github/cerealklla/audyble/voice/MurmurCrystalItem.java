package com.github.cerealklla.audyble.voice;

import com.github.cerealklla.audyble.registration.ModDataComponents;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Picks up a nearby settlement's Murmur Tower signal (see {@code MurmurCrystalEngine}) -- audio is
 * only ever heard by the holder, with on/off + volume controlled through this item's own GUI. Only
 * main-hand use opens the GUI (matches most vanilla GUI-opening items) -- no hand needs to round-
 * trip over the network as a result, see {@link OpenMurmurCrystalScreenPayload}'s own doc.
 */
public class MurmurCrystalItem extends Item {

    public MurmurCrystalItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide() || hand != InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        ItemStack held = player.getItemInHand(hand);
        MurmurCrystalState state = held.getOrDefault(ModDataComponents.MURMUR_CRYSTAL_STATE.get(), MurmurCrystalState.DEFAULT);
        PacketDistributor.sendToPlayer(serverPlayer, new OpenMurmurCrystalScreenPayload(state));
        return InteractionResult.SUCCESS;
    }
}
