package com.github.cerealklla.audyble.bridge;

import java.util.EnumSet;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

/**
 * Audyble's first bridge class -- the only place allowed to reference Lyfe's types directly (optional
 * dependency, suite-wide isolation convention: a general-purpose class must never itself import an
 * optional dependency's type, only a dedicated bridge may, confirmed real after a past
 * {@code NoClassDefFoundError} incident documented in the root project's own CLAUDE.md).
 *
 * <p>Tier 3 grants {@code DIRECTION}, Tier 4 adds {@code DISTANCE}, Tier 5 adds {@code POSITION} --
 * Lyfe's own {@code api.Lyfe#grantPartialLocationKnowledge} only ever grants DIRECTION/DISTANCE (plus
 * a name flag), confirmed from its real signature -- POSITION has to be granted separately via the
 * more generic {@code api.Lyfe#grantKnowledgeFactors}, which is additive/union-merge with whatever
 * the first call already granted.
 */
public final class LyfeKnowledgeBridge {

    private LyfeKnowledgeBridge() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded("lyfe");
    }

    public static void grantTowerKnowledge(ServerPlayer player, long placeId, Level level, BlockPos towerPos, int tier) {
        if (tier < 3) {
            return;
        }
        boolean grantsDistance = tier >= 4;
        com.github.cerealklla.lyfe.api.Lyfe.grantPartialLocationKnowledge(player, placeId, towerPos,
                level.dimension().identifier().toString(), "a distant signal", true, true, grantsDistance);
        if (tier >= 5) {
            com.github.cerealklla.lyfe.api.Lyfe.grantKnowledgeFactors(player, placeId,
                    EnumSet.of(com.github.cerealklla.lyfe.knowledge.KnowledgeFactor.POSITION));
        }
    }

    /** {@code true} if {@code player} already has any {@code knowledge.KnowledgeFactor} for {@code placeId} -- used to detect a brand-new grant (call before {@link #grantTowerKnowledge}) vs. re-confirming something already known. */
    public static boolean hasAnyKnowledge(ServerPlayer player, long placeId) {
        return com.github.cerealklla.lyfe.api.Lyfe.getKnownFactorCount(player, placeId) > 0;
    }

    /**
     * Points {@code player}'s minimap/Map-screen waypoint at {@code pos} -- same shared single-slot
     * system Myssions' own quest hints use ({@code api.Lyfe#setQuestWaypoint}), so setting one here
     * will replace an active Myssions quest waypoint and vice versa (a known, accepted limitation --
     * there's only ever one such slot per player in Lyfe today).
     */
    public static void setSettlementWaypoint(ServerPlayer player, Level level, BlockPos pos, double radiusBlocks) {
        com.github.cerealklla.lyfe.api.Lyfe.setQuestWaypoint(player, level.dimension(), pos, radiusBlocks);
    }

    public static void clearWaypoint(ServerPlayer player) {
        com.github.cerealklla.lyfe.api.Lyfe.clearQuestWaypoint(player);
    }
}
