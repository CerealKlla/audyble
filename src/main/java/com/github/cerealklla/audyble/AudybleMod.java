package com.github.cerealklla.audyble;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/**
 * Planning-stage scaffold only -- no SVC integration, registries, or feature code yet.
 * See context/design-document.md for the full scope and phased development plan.
 */
@Mod(AudybleMod.MODID)
public class AudybleMod {

    public static final String MODID = "audyble";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AudybleMod(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Audyble common setup");
    }
}
