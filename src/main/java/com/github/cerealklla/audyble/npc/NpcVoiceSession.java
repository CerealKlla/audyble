package com.github.cerealklla.audyble.npc;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

import de.maxhenkel.voicechat.api.opus.OpusDecoder;

/** One in-progress utterance: a player speaking to one specific conversational NPC. */
final class NpcVoiceSession {

    final UUID playerId;
    final UUID npcEntityId;
    final OpusDecoder decoder;
    private final ByteArrayOutputStream pcmBuffer = new ByteArrayOutputStream();
    volatile long lastPacketAtMillis;

    NpcVoiceSession(UUID playerId, UUID npcEntityId, OpusDecoder decoder) {
        this.playerId = playerId;
        this.npcEntityId = npcEntityId;
        this.decoder = decoder;
        this.lastPacketAtMillis = System.currentTimeMillis();
    }

    void append(byte[] opusData) {
        short[] pcm = decoder.decode(opusData);
        ByteBuffer buffer = ByteBuffer.allocate(pcm.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short sample : pcm) {
            buffer.putShort(sample);
        }
        pcmBuffer.writeBytes(buffer.array());
        lastPacketAtMillis = System.currentTimeMillis();
    }

    short[] finish() {
        decoder.close();
        byte[] bytes = pcmBuffer.toByteArray();
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        short[] pcm = new short[bytes.length / 2];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = buffer.getShort();
        }
        return pcm;
    }
}
