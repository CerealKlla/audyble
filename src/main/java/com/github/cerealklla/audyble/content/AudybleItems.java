package com.github.cerealklla.audyble.content;

import com.github.cerealklla.audyble.AudybleMod;
import com.github.cerealklla.audyble.voice.MurmurCrystalItem;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Phase 4 "equipment" hook (design-document.md section 6) -- a radio allows communication at
 * arbitrary distance with its own filtering, deliberately covering both the "equipment" and "radio"
 * examples from the design doc with one item. No custom item behavior is needed for v1: simply
 * holding one in either hand is the trigger, checked by
 * {@link com.github.cerealklla.audyble.voice.GameplayHearing#isHoldingRadio}.
 */
public final class AudybleItems {

    private AudybleItems() {
    }

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(AudybleMod.MODID);

    public static final DeferredItem<Item> RADIO = ITEMS.register(
            "radio",
            id -> new Item(new Item.Properties().stacksTo(1).setId(ResourceKey.create(Registries.ITEM, id))));

    /**
     * Picks up a nearby settlement's Murmur Tower signal (design-document.md Phase 6, 2026-11-02) --
     * a genuinely separate mechanic from {@link #RADIO} above (that's Phase 4's player-to-player
     * walkie-talkie hook), not a rename/repurpose of it.
     */
    public static final DeferredItem<MurmurCrystalItem> MURMUR_CRYSTAL = ITEMS.register(
            "murmur_crystal",
            id -> new MurmurCrystalItem(new Item.Properties().stacksTo(1).setId(ResourceKey.create(Registries.ITEM, id))));
}
