package com.github.cerealklla.audyble.npc;

import com.github.cerealklla.audyble.AudybleMod;

import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public final class NpcAttachments {

    private NpcAttachments() {
    }

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, AudybleMod.MODID);

    // Server-only (no .sync()) -- this drives server-side LLM/TTS orchestration only, the client
    // never needs to know an entity is conversational.
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<ConversationalNpcConfig>> CONVERSATIONAL_NPC =
            ATTACHMENT_TYPES.register(
                    "conversational_npc",
                    () -> AttachmentType.builder(() -> new ConversationalNpcConfig("", ""))
                            .serialize(ConversationalNpcConfig.CODEC.fieldOf("value"))
                            .build());
}
