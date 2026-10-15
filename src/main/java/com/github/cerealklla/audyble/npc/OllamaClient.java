package com.github.cerealklla.audyble.npc;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Phase 5 -- talks to a local Ollama instance (context/decisions.md, 2026-10-14: chosen for free,
 * self-hosted, no-API-cost NPC dialogue generation). Targets Ollama's own {@code /api/chat}
 * endpoint (non-streaming, {@code "stream": false}) -- simpler to consume than the streaming form
 * for a single request/response turn, at the cost of waiting for the full reply before TTS can
 * start; revisit if round-trip latency (see design-document.md Phase 5's own open question) turns
 * out to need streaming to feel conversational.
 *
 * <p><b>Assumption flagged for verification</b>: this targets a stock Ollama install's default API
 * shape as of this writing. If the user's actual Ollama version/config differs, the request/response
 * JSON shape below may need adjusting.</p>
 */
public final class OllamaClient {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final Gson GSON = new Gson();

    private final String baseUrl;
    private final String model;

    public OllamaClient(String baseUrl, String model) {
        this.baseUrl = baseUrl;
        this.model = model;
    }

    public record Turn(String role, String content) {
    }

    /**
     * @param systemPrompt the NPC's character/behavior definition
     * @param history      prior turns in the conversation (oldest first), excluding the system prompt
     * @param userMessage  the player's latest (transcribed) utterance
     * @return the NPC's reply text
     */
    public String chat(String systemPrompt, List<Turn> history, String userMessage) throws IOException, InterruptedException {
        JsonArray messages = new JsonArray();
        messages.add(message("system", systemPrompt));
        for (Turn turn : history) {
            messages.add(message(turn.role(), turn.content()));
        }
        messages.add(message("user", userMessage));

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", messages);
        body.addProperty("stream", false);

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/chat"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Ollama returned HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        return json.getAsJsonObject("message").get("content").getAsString();
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }
}
