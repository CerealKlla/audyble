package com.github.cerealklla.audyble.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server-administrator-facing tuning knobs (design-document.md section 17 -- "Configuration"). */
public final class AudybleConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue OCCLUSION_ENABLED = BUILDER
            .comment("Phase 3 -- whether walls/blocks between speaker and listener muffle voice at all.")
            .define("occlusionEnabled", true);

    public static final ModConfigSpec.DoubleValue MUFFLE_VOLUME_PER_BLOCK = BUILDER
            .comment("Volume reduction per solid block between speaker and listener (0.15 = -15% per block).")
            .defineInRange("muffleVolumePerBlock", 0.15, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue BASE_MUFFLE_CUTOFF_HZ = BUILDER
            .comment("Low-pass filter cutoff (Hz) applied once any occlusion is present; lower = more muffled.")
            .defineInRange("baseMuffleCutoffHz", 2200.0, 300.0, 8000.0);

    public static final ModConfigSpec.IntValue KEEN_HEARING_BLOCK_BONUS = BUILDER
            .comment("How many fewer occluding blocks count against a listener with the Keen Hearing effect.")
            .defineInRange("keenHearingBlockBonus", 2, 0, 10);

    public static final ModConfigSpec.DoubleValue RADIO_VOLUME = BUILDER
            .comment("Volume multiplier applied to a radio transmission.")
            .defineInRange("radioVolume", 0.9, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue RADIO_CUTOFF_HZ = BUILDER
            .comment("Low-pass filter cutoff (Hz) for the radio's own band-limited sound.")
            .defineInRange("radioCutoffHz", 3000.0, 300.0, 8000.0);

    public static final ModConfigSpec.DoubleValue RADIO_RANGE = BUILDER
            .comment("The distance value sent to the client for a radio transmission, bypassing real "
                    + "in-world distance/occlusion -- large enough that normal client-side distance "
                    + "falloff doesn't reduce volume further.")
            .defineInRange("radioRange", 512.0, 16.0, 100000.0);

    // --- Phase 5 (NPC verbal conversation) -- lowest priority, see design-document.md Phase 5.
    // All external services are local/self-hosted; disabled by default until an administrator
    // actually stands them up and points these at them (see context/decisions.md's open items).

    public static final ModConfigSpec.BooleanValue NPC_CONVERSATION_ENABLED = BUILDER
            .comment("Master switch for Phase 5 NPC verbal conversation. Requires Ollama, a Kokoro TTS "
                    + "server, and a Whisper STT server all reachable at the URLs below.")
            .define("npcConversationEnabled", false);

    public static final ModConfigSpec.ConfigValue<String> OLLAMA_BASE_URL = BUILDER
            .comment("Base URL of a local Ollama instance, e.g. http://localhost:11434")
            .define("ollamaBaseUrl", "http://localhost:11434");

    public static final ModConfigSpec.ConfigValue<String> OLLAMA_MODEL = BUILDER
            .comment("Ollama model name to use for NPC dialogue, e.g. llama3.1:8b, gemma2:9b, qwen2.5:7b, phi4")
            .define("ollamaModel", "llama3.1:8b");

    public static final ModConfigSpec.ConfigValue<String> KOKORO_BASE_URL = BUILDER
            .comment("Base URL of a local Kokoro-82M TTS server exposing an OpenAI-compatible "
                    + "/v1/audio/speech endpoint (e.g. the kokoro-fastapi project), e.g. http://localhost:8880")
            .define("kokoroBaseUrl", "http://localhost:8880");

    public static final ModConfigSpec.ConfigValue<String> KOKORO_VOICE = BUILDER
            .comment("Kokoro voice name, e.g. af_heart, am_michael -- see the Kokoro project's own voice list.")
            .define("kokoroVoice", "af_heart");

    public static final ModConfigSpec.ConfigValue<String> WHISPER_BASE_URL = BUILDER
            .comment("Base URL of a local whisper.cpp server (the `server` example binary), e.g. http://localhost:8081")
            .define("whisperBaseUrl", "http://localhost:8081");

    public static final ModConfigSpec.DoubleValue NPC_INTERACTION_RANGE = BUILDER
            .comment("How close a player must be to a conversational NPC for their speech to be captured at all.")
            .defineInRange("npcInteractionRange", 6.0, 1.0, 32.0);

    public static final ModConfigSpec.IntValue NPC_SILENCE_TIMEOUT_MILLIS = BUILDER
            .comment("How long (ms) of silence ends a player's utterance and triggers STT -> LLM -> TTS.")
            .defineInRange("npcSilenceTimeoutMillis", 700, 200, 5000);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private AudybleConfig() {
    }
}
