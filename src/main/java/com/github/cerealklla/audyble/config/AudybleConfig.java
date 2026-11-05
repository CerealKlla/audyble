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

    public static final ModConfigSpec.BooleanValue MURMUR_CRYSTAL_ENABLED = BUILDER
            .comment("Master switch for the Murmur Crystal item (design-document.md Phase 6).")
            .define("murmurCrystalEnabled", true);

    public static final ModConfigSpec.ConfigValue<String> MUSIC_SOURCE_DIRECTORY = BUILDER
            .comment("Absolute path to a directory of music to play through the Murmur Crystal, "
                    + "searched recursively -- a random track is picked each time a listener starts, "
                    + "and again whenever one finishes. Supports .ogg (Vorbis) and .mp3 files; "
                    + "subfolders containing a file literally named 'song.ogg' or 'song.mp3' (the "
                    + "Clone Hero chart convention) play that file specifically rather than any other "
                    + "stem in the same folder. Leave blank to fall back to Phase 1's placeholder test "
                    + "tone instead.")
            .define("musicSourceDirectory", "");

    public static final ModConfigSpec.BooleanValue SHOW_ARTIST_AND_SONG = BUILDER
            .comment("When true, show the artist and song name above the health/XP bars for a few "
                    + "seconds whenever the Murmur Tower a listener is tuned to starts a new song.")
            .define("showArtistAndSong", true);

    public static final ModConfigSpec.IntValue MUSIC_HTTP_PORT = BUILDER
            .comment("Port for the small embedded HTTP server (2026-11-02 rework) that lets clients "
                    + "download Murmur Tower station tracks directly, for fully local client-side "
                    + "playback instead of relaying through Simple Voice Chat's lossy Opus codec. "
                    + "Read-only, serves only files under musicSourceDirectory. Clients connect to "
                    + "this port on the same host they used for the Minecraft connection itself.")
            .defineInRange("musicHttpPort", 25690, 1, 65535);

    public static final ModConfigSpec.DoubleValue MUSIC_PREFETCH_RANGE_MULTIPLIER = BUILDER
            .comment("How far past a Murmur Tower's real audible range (maxRangeBlocks) a listener "
                    + "should start pre-downloading that station's current/upcoming tracks, so "
                    + "there's no fetch delay once they actually walk into audible range.")
            .defineInRange("musicPrefetchRangeMultiplier", 1.5, 1.0, 5.0);

    public static final ModConfigSpec.ConfigValue<String> DEBUG_FORCE_TRACK_FILENAME = BUILDER
            .comment("Debug/testing only: when set, every Murmur Tower always plays this one file "
                    + "instead of picking randomly -- matched by filename only (not a full path, e.g. "
                    + "'song.ogg' or 'My Band - My Song.ogg'), case-insensitive, searched recursively "
                    + "under musicSourceDirectory. Takes effect immediately, even on an already-playing "
                    + "station. Leave blank for normal random playback.")
            .define("debugForceTrackFilename", "");

    public static final ModConfigSpec.ConfigValue<String> MURMUR_SONG_REQUEST_FILE = BUILDER
            .comment("Absolute path to a plain-text file that Mayor/Town Planner song requests (pasted "
                    + "YouTube links, submitted from the Plot Config Sign's Radio tab) are appended to "
                    + "-- one request per line, pipe-delimited. A separate external job (not part of "
                    + "this mod/JVM) reads and processes this file on its own schedule, downloading each "
                    + "request and assigning it to the requesting station via the /audyble murmur assign "
                    + "command. Leave blank to disable song requests entirely (the request box is hidden "
                    + "client-side).")
            .define("murmurSongRequestFile", "");

    // --- Phase 5 (NPC verbal conversation) -- lowest priority, see design-document.md Phase 5.
    // All external services are local/self-hosted; disabled by default until an administrator
    // actually stands them up and points these at them (see context/decisions.md's open items).

    public static final ModConfigSpec.BooleanValue NPC_CONVERSATION_ENABLED = BUILDER
            .comment("Master switch for Phase 5 NPC verbal conversation. Requires Ollama, a Kokoro TTS "
                    + "server, and a Whisper STT server all reachable at the URLs below.")
            .define("npcConversationEnabled", false);

    public static final ModConfigSpec.ConfigValue<String> OLLAMA_BASE_URL = BUILDER
            .comment("Base URL of a local Ollama instance, e.g. http://127.0.0.1:11434 -- use a literal "
                    + "IPv4 address, not 'localhost': confirmed live 2026-11-04 that Java's HttpClient can "
                    + "fail to connect via the 'localhost' hostname when a local service binds IPv4-only "
                    + "(the loopback IPv6 ::1 resolves first and has nothing listening, and unlike curl's "
                    + "own fallback behavior, HttpClient doesn't reliably retry the IPv4 address after that).")
            .define("ollamaBaseUrl", "http://127.0.0.1:11434");

    public static final ModConfigSpec.ConfigValue<String> OLLAMA_MODEL = BUILDER
            .comment("Ollama model name to use for NPC dialogue, e.g. llama3.1:8b, gemma2:9b, qwen2.5:7b, phi4")
            .define("ollamaModel", "llama3.1:8b");

    public static final ModConfigSpec.ConfigValue<String> KOKORO_BASE_URL = BUILDER
            .comment("Base URL of a local Kokoro-82M TTS server exposing an OpenAI-compatible "
                    + "/v1/audio/speech endpoint (e.g. the kokoro-fastapi project), e.g. http://127.0.0.1:8880 "
                    + "-- use a literal IPv4 address, not 'localhost' (see ollamaBaseUrl's own comment for why).")
            .define("kokoroBaseUrl", "http://127.0.0.1:8880");

    public static final ModConfigSpec.ConfigValue<String> KOKORO_VOICE = BUILDER
            .comment("Kokoro voice name, e.g. af_heart, am_michael -- see the Kokoro project's own voice list.")
            .define("kokoroVoice", "af_heart");

    public static final ModConfigSpec.ConfigValue<String> WHISPER_BASE_URL = BUILDER
            .comment("Base URL of a local whisper.cpp server (the `server` example binary), e.g. http://127.0.0.1:8081 "
                    + "-- use a literal IPv4 address, not 'localhost' (see ollamaBaseUrl's own comment for why).")
            .define("whisperBaseUrl", "http://127.0.0.1:8081");

    public static final ModConfigSpec.DoubleValue NPC_INTERACTION_RANGE = BUILDER
            .comment("How close a player must be to a conversational NPC for their speech to be captured at all.")
            .defineInRange("npcInteractionRange", 6.0, 1.0, 32.0);

    public static final ModConfigSpec.IntValue NPC_SILENCE_TIMEOUT_MILLIS = BUILDER
            .comment("How long (ms) of silence ends a player's utterance and triggers STT -> LLM -> TTS.")
            .defineInRange("npcSilenceTimeoutMillis", 700, 200, 5000);

    public static final ModConfigSpec.IntValue NPC_MIN_SPEECH_RMS = BUILDER
            .comment("Minimum RMS amplitude (out of a 16-bit PCM sample's max 32767) a captured utterance "
                    + "must have before it's even sent to Whisper at all -- confirmed live 2026-11-04 that "
                    + "ambient mic noise/silence reaching Whisper gets hallucinated into real-looking short "
                    + "words (not just Whisper's own bracketed non-speech tags, which are filtered "
                    + "separately), triggering unwanted NPC replies and piling up overlapping voice "
                    + "channels. Raise this if ambient noise still triggers replies; lower it if quiet "
                    + "real speech is being missed entirely.")
            .defineInRange("npcMinSpeechRms", 400, 0, 10000);

    public static final ModConfigSpec.IntValue NPC_RELATIONSHIP_SUMMARY_IDLE_MILLIS = BUILDER
            .comment("How long (ms) a (player, NPC) conversation must go quiet before it's considered "
                    + "genuinely over -- at which point it's re-summarized into PlayerRelationshipStore "
                    + "(never a raw transcript) and the in-memory rolling history is cleared. Deliberately "
                    + "much longer than npcSilenceTimeoutMillis, which only ends one utterance, not the "
                    + "whole conversation.")
            .defineInRange("npcRelationshipSummaryIdleMillis", 120000, 5000, 1800000);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private AudybleConfig() {
    }
}
