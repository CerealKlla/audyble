package com.github.cerealklla.audyble.voice.client;

import com.github.cerealklla.audyble.voice.MurmurCrystalState;
import com.github.cerealklla.audyble.voice.SetMurmurCrystalStatePayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

/**
 * Murmur Crystal's own GUI (right-click to open) -- on/off toggle + volume control, menu-less,
 * mirroring Lyfe's {@code knowledge.WritingScreen} shape exactly (plain {@code init()}-added
 * {@code Button}s, no {@code AbstractContainerMenu}). Every change sends a fresh {@link
 * SetMurmurCrystalStatePayload} immediately -- there's no separate "Confirm" step, since on/off
 * and volume are both cheap, idempotent, instantly-reversible settings.
 */
public final class MurmurCrystalScreen extends Screen {

    private static final float VOLUME_STEP = 0.1f;

    private MurmurCrystalState state;
    private Button toggleButton;
    private Button volumeButton;

    public MurmurCrystalScreen(MurmurCrystalState state) {
        super(Component.literal("Murmur Crystal"));
        this.state = state;
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int rowY = height / 2 - 30;

        toggleButton = addRenderableWidget(Button.builder(toggleLabel(), b -> toggleActive())
                .bounds(centerX - 100, rowY, 200, 20).build());

        rowY += 26;
        addRenderableWidget(Button.builder(Component.literal("- Volume"), b -> adjustVolume(-VOLUME_STEP))
                .bounds(centerX - 100, rowY, 95, 20).build());
        volumeButton = addRenderableWidget(Button.builder(volumeLabel(), b -> {})
                .bounds(centerX - 100, rowY, 95, 20).build());
        volumeButton.active = false;
        addRenderableWidget(Button.builder(Component.literal("+ Volume"), b -> adjustVolume(VOLUME_STEP))
                .bounds(centerX + 5, rowY, 95, 20).build());

        rowY += 30;
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(centerX - 50, rowY, 100, 20).build());
    }

    private Component toggleLabel() {
        return Component.literal(state.active() ? "Turn Off" : "Turn On");
    }

    private Component volumeLabel() {
        return Component.literal("Volume: " + Math.round(state.volume() * 100) + "%");
    }

    private void toggleActive() {
        state = state.withActive(!state.active());
        toggleButton.setMessage(toggleLabel());
        send();
    }

    private void adjustVolume(float delta) {
        state = state.withVolume(state.volume() + delta);
        volumeButton.setMessage(volumeLabel());
        send();
    }

    private void send() {
        Minecraft.getInstance().getConnection().send(new ServerboundCustomPayloadPacket(new SetMurmurCrystalStatePayload(state)));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int titleWidth = font.width(title);
        graphics.text(font, title, width / 2 - titleWidth / 2, height / 2 - 55, 0xFFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
