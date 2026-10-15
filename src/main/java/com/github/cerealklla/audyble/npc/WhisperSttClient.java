package com.github.cerealklla.audyble.npc;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Phase 5 -- talks to a local Whisper-family STT server (context/decisions.md, 2026-10-14: no
 * specific STT choice was pinned down, "a local Whisper-family model is the obvious free/local
 * fit"). Targets <a href="https://github.com/ggml-org/whisper.cpp">whisper.cpp</a>'s own bundled
 * {@code server} example's {@code /inference} endpoint, since it's the most common lightweight
 * self-hosted option matching the design doc's "keep raw audio local" principle (section 16).
 *
 * <p><b>Assumption flagged for verification</b>: if the user runs a different Whisper server (e.g.
 * faster-whisper-server, which has a different, OpenAI-compatible API shape), this multipart request
 * will need adjusting to match.</p>
 */
public final class WhisperSttClient {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final String baseUrl;

    public WhisperSttClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** @param wavBytes a 16-bit PCM WAV file (whisper.cpp's server expects 16kHz mono) */
    public String transcribe(byte[] wavBytes) throws IOException, InterruptedException {
        String boundary = "----AudybleBoundary" + System.nanoTime();

        List<byte[]> parts = new ArrayList<>();
        parts.add(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        parts.add(wavBytes);
        parts.add(("\r\n--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"response_format\"\r\n\r\n"
                + "json\r\n"
                + "--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        int totalLength = parts.stream().mapToInt(p -> p.length).sum();
        byte[] multipartBody = new byte[totalLength];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, multipartBody, offset, part.length);
            offset += part.length;
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/inference"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipartBody))
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Whisper STT returned HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        return json.get("text").getAsString().trim();
    }
}
