package com.github.cerealklla.audyble.voice;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Persisted on/off + volume state for a Murmur Crystal item stack -- set via the item's own GUI
 * ({@code client.MurmurCrystalScreen}), read every scan cycle by {@link MurmurCrystalEngine} to
 * decide whether/how loud to play. {@code volume} is a plain gain multiplier (0.0-1.0), applied to
 * the PCM samples before encoding -- the item's own GUI is meant to own volume, not SVC's separate
 * native {@code VolumeCategory} settings slider.
 */
public record MurmurCrystalState(boolean active, float volume) {

    public static final MurmurCrystalState DEFAULT = new MurmurCrystalState(false, 1.0f);

    public static final Codec<MurmurCrystalState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.BOOL.fieldOf("active").forGetter(MurmurCrystalState::active),
            Codec.FLOAT.fieldOf("volume").forGetter(MurmurCrystalState::volume)
    ).apply(instance, MurmurCrystalState::new));

    public MurmurCrystalState withActive(boolean active) {
        return new MurmurCrystalState(active, volume);
    }

    public MurmurCrystalState withVolume(float volume) {
        return new MurmurCrystalState(active, Math.max(0.0f, Math.min(1.0f, volume)));
    }
}
