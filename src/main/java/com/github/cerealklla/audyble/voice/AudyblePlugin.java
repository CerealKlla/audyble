package com.github.cerealklla.audyble.voice;

import com.github.cerealklla.audyble.AudybleMod;
import com.github.cerealklla.audyble.npc.NpcConversationEngine;

import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EntitySoundPacketEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent;
import de.maxhenkel.voicechat.api.events.SoundPacketEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.packets.EntitySoundPacket;

import net.minecraft.server.level.ServerPlayer;

/**
 * Phase 1 -- the Simple Voice Chat integration point. Discovered automatically by SVC at startup via
 * {@link ForgeVoicechatPlugin} annotation scanning (confirmed against SVC's own 26.1 branch source,
 * {@code NeoForgeCommonCompatibilityManager#loadPlugins} -- reused Forge-era annotation name, works
 * identically on NeoForge). Requires a public no-arg constructor.
 *
 * <p>Only real player-to-player proximity voice ({@link SoundPacketEvent#SOURCE_PROXIMITY}) gets
 * Audyble's per-listener treatment. Group chat and spectator audio are deliberately left alone --
 * players join a group specifically to talk regardless of in-world position, so world-geometry/
 * gameplay treatment would work against the player's own intent there. Plugin-sourced audio
 * ({@link SoundPacketEvent#SOURCE_PLUGIN}, e.g. a future Phase 5 NPC voice played through its own
 * {@code EntityAudioChannel}) is also left alone here -- re-cancelling and re-sending a
 * plugin-originated packet through this same handler risks a re-entrant event loop, not something to
 * risk for Audyble's own lowest-priority phase. NPC voice gets SVC's native distance-based 3D audio
 * only, not wall occlusion/radio/deafened treatment, until that's deliberately designed -- see
 * context/decisions.md's open items.</p>
 */
@ForgeVoicechatPlugin
public final class AudyblePlugin implements VoicechatPlugin {

    private final TreatmentEngine engine = new TreatmentEngine();
    private volatile AudioProcessor audioProcessor;

    @Override
    public String getPluginId() {
        return AudybleMod.MODID;
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, this::onServerStarted);
        registration.registerEvent(EntitySoundPacketEvent.class, this::onEntitySound);
        registration.registerEvent(PlayerDisconnectedEvent.class, this::onPlayerDisconnected);
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophonePacket);
    }

    private void onServerStarted(VoicechatServerStartedEvent event) {
        audioProcessor = new AudioProcessor(event.getVoicechat());
        NpcConversationEngine npcEngine = NpcConversationEngine.instance();
        if (npcEngine != null) {
            npcEngine.onServerStarted(event.getVoicechat());
        }
        AudybleMod.LOGGER.info("Audyble voice engine ready");
    }

    private void onMicrophonePacket(MicrophonePacketEvent event) {
        NpcConversationEngine npcEngine = NpcConversationEngine.instance();
        if (npcEngine != null) {
            npcEngine.onMicrophonePacket(event);
        }
    }

    private void onEntitySound(EntitySoundPacketEvent event) {
        // Temporary instrumentation (2026-10-21) -- see AudioDiagnostics' own doc. Recorded before
        // the SOURCE_PROXIMITY filter below so this captures literally every packet SVC hands us,
        // regardless of outcome -- the real question right now is "is SVC sending packets at all
        // while nobody is meaningfully talking," not just how the proximity ones are handled.
        AudioDiagnostics.recordPacketSeen();
        if (!SoundPacketEvent.SOURCE_PROXIMITY.equals(event.getSource())) {
            return;
        }
        VoicechatConnection receiverConnection = event.getReceiverConnection();
        VoicechatConnection senderConnection = event.getSenderConnection();
        if (receiverConnection == null || senderConnection == null) {
            return;
        }
        if (!(receiverConnection.getPlayer().getPlayer() instanceof ServerPlayer listener)
                || !(senderConnection.getPlayer().getPlayer() instanceof ServerPlayer speaker)) {
            return;
        }

        VoiceTreatment treatment = engine.resolve(speaker, listener);
        if (treatment.isPassthrough()) {
            return;
        }

        event.cancel();
        if (!treatment.audible()) {
            return;
        }

        AudioProcessor processor = audioProcessor;
        if (processor == null) {
            return;
        }

        EntitySoundPacket original = event.getPacket();
        long processStart = System.nanoTime();
        byte[] processedOpus = processor.process(speaker.getUUID(), listener.getUUID(),
                original.getOpusEncodedData(), treatment);
        AudioDiagnostics.recordProcess(System.nanoTime() - processStart);

        float distance = treatment.distanceOverride() == VoiceTreatment.NO_DISTANCE_OVERRIDE
                ? original.getDistance()
                : treatment.distanceOverride();

        EntitySoundPacket replacement = original.entitySoundPacketBuilder()
                .channelId(original.getChannelId())
                .category(original.getCategory())
                .opusEncodedData(processedOpus)
                .entityUuid(original.getEntityUuid())
                .whispering(original.isWhispering())
                .distance(distance)
                .build();

        event.getVoicechat().sendEntitySoundPacketTo(receiverConnection, replacement);
    }

    private void onPlayerDisconnected(PlayerDisconnectedEvent event) {
        engine.forgetPlayer(event.getPlayerUuid());
        AudioProcessor processor = audioProcessor;
        if (processor != null) {
            processor.forgetPlayer(event.getPlayerUuid());
        }
    }
}
