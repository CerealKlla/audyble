package com.github.cerealklla.audyble;

import com.github.cerealklla.audyble.bridge.SettlemyntsMurmurBridge;
import com.github.cerealklla.audyble.config.AudybleConfig;
import com.github.cerealklla.audyble.content.AudybleEffects;
import com.github.cerealklla.audyble.content.AudybleItems;
import com.github.cerealklla.audyble.npc.NpcAttachments;
import com.github.cerealklla.audyble.npc.NpcConversationEngine;
import com.github.cerealklla.audyble.npc.NpcDebugCommands;
import com.github.cerealklla.audyble.npc.NpcStoreBootstrap;
import com.github.cerealklla.audyble.npc.WanderingDeathListener;
import com.github.cerealklla.audyble.npc.WanderingIdentityListener;
import com.github.cerealklla.audyble.registration.ModDataComponents;
import com.github.cerealklla.audyble.voice.MurmurAdminCommands;
import com.github.cerealklla.audyble.voice.MurmurCrystalEngine;
import com.github.cerealklla.audyble.voice.MurmurPrefetchHintPayload;
import com.github.cerealklla.audyble.voice.MurmurStopPlaybackPayload;
import com.github.cerealklla.audyble.voice.MurmurTunedInPayload;
import com.github.cerealklla.audyble.voice.OpenMurmurCrystalScreenPayload;
import com.github.cerealklla.audyble.voice.SetMurmurCrystalStatePayload;
import com.github.cerealklla.audyble.voice.client.ClientMurmurCrystalRequest;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

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
        ModDataComponents.DATA_COMPONENTS.register(modEventBus);

        modContainer.registerConfig(ModConfig.Type.SERVER, AudybleConfig.SPEC);

        NpcConversationEngine.init();
        MurmurCrystalEngine.init();
        NeoForge.EVENT_BUS.register(new NpcStoreBootstrap());
        NeoForge.EVENT_BUS.register(new WanderingIdentityListener());
        NeoForge.EVENT_BUS.register(new WanderingDeathListener());
        NpcDebugCommands.init();
        MurmurAdminCommands.init();

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::registerPayloads);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Audyble common setup");
        if (SettlemyntsMurmurBridge.isLoaded()) {
            SettlemyntsMurmurBridge.registerShopCatalog();
        }
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");

        // Guarded so a missing/malformed payload could never open a stray screen -- the real
        // Screen-opening itself is deferred to a genuinely client-only tick listener (see
        // AudybleModClient), never referenced from this common-sourceset handler.
        registrar.playToClient(OpenMurmurCrystalScreenPayload.TYPE, OpenMurmurCrystalScreenPayload.STREAM_CODEC,
                (payload, context) -> ClientMurmurCrystalRequest.request(payload.state()));

        registrar.playToClient(MurmurPrefetchHintPayload.TYPE, MurmurPrefetchHintPayload.STREAM_CODEC,
                (payload, context) -> com.github.cerealklla.audyble.voice.client.ClientMurmurRequests.requestPrefetch(payload));

        registrar.playToClient(MurmurTunedInPayload.TYPE, MurmurTunedInPayload.STREAM_CODEC,
                (payload, context) -> com.github.cerealklla.audyble.voice.client.ClientMurmurRequests.requestTunedIn(payload));

        registrar.playToClient(MurmurStopPlaybackPayload.TYPE, MurmurStopPlaybackPayload.STREAM_CODEC,
                (payload, context) -> com.github.cerealklla.audyble.voice.client.ClientMurmurRequests.requestStop());

        registrar.playToServer(SetMurmurCrystalStatePayload.TYPE, SetMurmurCrystalStatePayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer serverPlayer) {
                        var held = serverPlayer.getMainHandItem();
                        if (!held.isEmpty() && held.getItem() instanceof com.github.cerealklla.audyble.voice.MurmurCrystalItem) {
                            held.set(ModDataComponents.MURMUR_CRYSTAL_STATE.get(), payload.state());
                            // Apply the toggle/volume change immediately instead of waiting on the
                            // engine's own periodic scan (up to 5 real seconds) to notice it.
                            MurmurCrystalEngine.instance().refreshPlayer(serverPlayer);
                        }
                    }
                });
    }
}
