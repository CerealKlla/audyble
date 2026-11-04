package com.github.cerealklla.audyble.npc;

import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;

/** A player's own phonetic spelling hint for TTS, set via {@code /audyble pronunciation set} -- Phase 5b. */
public record PronunciationEntry(UUID playerId, String hint) {

    public static final Codec<PronunciationEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("player_id").forGetter(PronunciationEntry::playerId),
            Codec.STRING.fieldOf("hint").forGetter(PronunciationEntry::hint)
    ).apply(i, PronunciationEntry::new));
}
