package com.github.cerealklla.audyble.voice;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;

import com.github.cerealklla.audyble.config.AudybleConfig;

import com.sun.net.httpserver.HttpServer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Small embedded, read-only HTTP file server (2026-11-02 rework) letting clients download a Murmur
 * Tower station's actual track files for local playback -- plain {@code com.sun.net.httpserver}
 * (ships with every JDK, no new dependency), not a general-purpose web server. Serves exactly one
 * route, {@code GET /track/<url-encoded trackId>}, resolved through {@link
 * MusicLibrary#resolveTrackId} -- the one real security boundary here, since a request's path
 * ultimately comes from an untrusted client. Explicit, discussed tradeoff: this opens a new,
 * unauthenticated, read-only port on Production, scoped to nothing but files already placed in the
 * configured {@link AudybleConfig#MUSIC_SOURCE_DIRECTORY}.
 *
 * <p>Started/stopped with the Minecraft server's own lifecycle (see {@code AudybleMod}'s {@code
 * ServerStartedEvent}/{@code ServerStoppingEvent} listeners), independent of Simple Voice Chat --
 * this feature no longer touches SVC at all.
 */
final class MusicHttpServer {

    private static final Logger LOGGER = LoggerFactory.getLogger("audyble-music");

    private static volatile HttpServer server;

    private MusicHttpServer() {
    }

    static synchronized void start() {
        if (server != null) {
            return;
        }
        int port = AudybleConfig.MUSIC_HTTP_PORT.get();
        try {
            HttpServer created = HttpServer.create(new InetSocketAddress(port), 0);
            created.createContext("/track/", MusicHttpServer::handle);
            created.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "Audyble-Music-Http");
                t.setDaemon(true);
                return t;
            }));
            created.start();
            server = created;
            LOGGER.info("Murmur Tower music HTTP server listening on port {}", port);
        } catch (IOException e) {
            LOGGER.error("Failed to start Murmur Tower music HTTP server on port {} -- "
                    + "Murmur Crystal playback will not work until this is resolved", port, e);
        }
    }

    static synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private static void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String requestPath = exchange.getRequestURI().getPath();
            String prefix = "/track/";
            if (!requestPath.startsWith(prefix)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            String trackId = URLDecoder.decode(requestPath.substring(prefix.length()), StandardCharsets.UTF_8);
            var resolved = MusicLibrary.resolveTrackId(trackId);
            if (resolved.isEmpty()) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            Path file = resolved.get();
            long length = Files.size(file);
            exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, length);
            try (OutputStream out = exchange.getResponseBody()) {
                Files.copy(file, out);
            }
        } finally {
            exchange.close();
        }
    }
}
