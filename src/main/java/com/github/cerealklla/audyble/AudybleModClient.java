package com.github.cerealklla.audyble;

import com.github.cerealklla.audyble.voice.client.ClientMurmurCrystalRequest;
import com.github.cerealklla.audyble.voice.client.MurmurCrystalScreen;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Audyble's first client-dist-gated class -- this mod was server-only (voice processing) until the
 * Murmur Crystal item needed a real GUI. Polls the zero-Settlemynts/Lyfe-refs bridge (see {@link
 * ClientMurmurCrystalRequest}'s own class doc) for a pending "open the Murmur Crystal screen"
 * request -- this is the one place allowed to touch {@code Minecraft}/{@code Screen} for it, since
 * this whole class is already client-dist-gated.
 */
@Mod(value = AudybleMod.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = AudybleMod.MODID, value = Dist.CLIENT)
public class AudybleModClient {

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        ClientMurmurCrystalRequest.takePending().ifPresent(state -> {
            if (Minecraft.getInstance().screen == null) {
                Minecraft.getInstance().setScreen(new MurmurCrystalScreen(state));
            }
        });
    }
}
