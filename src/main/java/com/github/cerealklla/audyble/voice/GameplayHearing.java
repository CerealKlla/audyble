package com.github.cerealklla.audyble.voice;

import com.github.cerealklla.audyble.content.AudybleEffects;
import com.github.cerealklla.audyble.content.AudybleItems;

import net.minecraft.server.level.ServerPlayer;

/**
 * Phase 4 -- gameplay-driven hearing hooks (design-document.md section 6): equipment (the Radio
 * item) and player state (the Deafened/Keen Hearing status effects). World-state effects (doors,
 * walls, rooms) are handled by {@link AcousticOcclusion} instead -- this class only covers the two
 * hooks that aren't pure world geometry.
 */
public final class GameplayHearing {

    private GameplayHearing() {
    }

    public static boolean isDeafened(ServerPlayer listener) {
        return listener.hasEffect(AudybleEffects.DEAFENED);
    }

    public static boolean hasKeenHearing(ServerPlayer listener) {
        return listener.hasEffect(AudybleEffects.KEEN_HEARING);
    }

    public static boolean isHoldingRadio(ServerPlayer player) {
        return player.getMainHandItem().getItem() == AudybleItems.RADIO.get()
                || player.getOffhandItem().getItem() == AudybleItems.RADIO.get();
    }
}
