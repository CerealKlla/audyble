package com.github.cerealklla.audyble.npc;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Phase 5 -- talks to a local Kokoro-82M TTS server (context/decisions.md, 2026-10-14: chosen over
 * Piper for voice naturalness, over Coqui XTTS-v2 for speed and its permissive Apache 2.0 license).
 *
 * <p><b>Assumption flagged for verification</b>: Kokoro itself is a model, not a server -- there are
 * several different community HTTP wrappers around it. This targets the <a
 * href="https://github.com/remsky/Kokoro-FastAPI">kokoro-fastapi</a> project's OpenAI-compatible
 * {@code /v1/audio/speech} endpoint specifically, since it's the most widely used wrapper. If the
 * user runs a different Kokoro server, this request/response shape will need adjusting.</p>
 */
public final class KokoroTtsClient {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final Gson GSON = new Gson();

    private final String baseUrl;
    private final String voice;

    public KokoroTtsClient(String baseUrl, String voice) {
        this.baseUrl = baseUrl;
        this.voice = voice;
    }

    /** @return raw WAV file bytes (24kHz mono, Kokoro's native output rate) */
    public byte[] synthesize(String text) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("model", "kokoro");
        body.addProperty("input", text);
        body.addProperty("voice", voice);
        body.addProperty("response_format", "wav");

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/audio/speech"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .build();

        HttpResponse<byte[]> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("Kokoro TTS returned HTTP " + response.statusCode());
        }
        return response.body();
    }
}
