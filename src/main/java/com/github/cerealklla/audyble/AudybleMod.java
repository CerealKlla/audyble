package com.github.cerealklla.audyble;

import com.github.cerealklla.audyble.config.AudybleConfig;
import com.github.cerealklla.audyble.content.AudybleEffects;
import com.github.cerealklla.audyble.content.AudybleItems;
import com.github.cerealklla.audyble.npc.NpcAttachments;
import com.github.cerealklla.audyble.npc.NpcConversationEngine;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/**
 * Phases 1-4 (SVC integration, per-listener acoustic voice treatment, gameplay-driven hearing) are
 * implemented -- see {@link com.github.cerealklla.audyble.voice.AudyblePlugin}, the real SVC plugin
 * entry point. Phase 5 (NPC verbal conversation) is scaffolded in {@link NpcConversationEngine}, off
 * by default pending the external LLM/TTS/STT services it depends on being configured. See
 * context/design-document.md for the full phased plan and context/decisions.md for open items.
 */
@Mod(AudybleMod.MODID)
public class AudybleMod {

    public static final String MODID = "audyble";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AudybleMod(IEventBus modEventBus, ModContainer modContainer) {
        AudybleEffects.MOB_EFFECTS.register(modEventBus);
        AudybleItems.ITEMS.register(modEventBus);
        NpcAttachments.ATTACHMENT_TYPES.register(modEventBus);

        modContainer.registerConfig(ModConfig.Type.SERVER, AudybleConfig.SPEC);

        NpcConversationEngine.init();

        modEventBus.addListener(this::commonSetup);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Audyble common setup");
    }
}
