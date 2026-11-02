package com.github.cerealklla.audyble.bridge;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;

/**
 * Audyble's second bridge class -- the only place allowed to reference Settlemynts' types directly
 * (same isolation convention as {@link LyfeKnowledgeBridge}). Wraps {@code
 * api.Settlemynts#findNearestMurmurSignal} in a local record so nothing outside this class ever
 * needs to import Settlemynts' own {@code MurmurSignal} type.
 */
public final class SettlemyntsMurmurBridge {

    private SettlemyntsMurmurBridge() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded("settlemynts");
    }

    public record MurmurSignalInfo(BlockPos towerPos, int tier, int maxRangeBlocks, long settlementPlaceId) {
    }

    public static Optional<MurmurSignalInfo> findNearestMurmurSignal(ServerLevel level, BlockPos pos) {
        return com.github.cerealklla.settlemynts.api.Settlemynts.findNearestMurmurSignal(level, pos)
                .map(signal -> new MurmurSignalInfo(signal.towerPos(), signal.tier(), signal.maxRangeBlocks(), signal.settlementPlaceId()));
    }

    /**
     * Registers a 100-gold Murmur Crystal listing on the Murmur Tower zone type's shop seed catalog
     * -- called from Audyble's own common setup, gated by {@link #isLoaded()}, per this suite's
     * established convention that a Zone Type's own catalog is registered by whichever mod actually
     * understands that type's goods (Settlemynts itself never hardcodes what any business sells).
     * Uses {@code zone.ShopResource#ofItem}, keyed by raw {@link net.minecraft.resources.Identifier}
     * rather than a compile-time {@code Item} type -- confirmed safe for an optional item: it simply
     * won't resolve to anything if this mod (Audyble) isn't installed.
     */
    public static void registerShopCatalog() {
        net.minecraft.resources.Identifier zoneTypeId = net.minecraft.resources.Identifier.fromNamespaceAndPath("blueprynts", "murmur_tower");
        net.minecraft.resources.Identifier crystalId = net.minecraft.resources.Identifier.fromNamespaceAndPath("audyble", "murmur_crystal");
        com.github.cerealklla.settlemynts.zone.ShopResource resource = com.github.cerealklla.settlemynts.zone.ShopResource.ofItem(crystalId);
        com.github.cerealklla.settlemynts.api.Settlemynts.registerShopSeedCatalog(zoneTypeId,
                (level, plotAnchor, plotTier) -> java.util.List.of(
                        com.github.cerealklla.settlemynts.zone.SeedListing.ofResource(resource, 100, 5)));
    }
}
