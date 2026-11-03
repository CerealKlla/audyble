package com.github.cerealklla.audyble.voice.client;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.minecraft.client.Minecraft;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Downloads and caches Murmur Tower track files on disk for {@link ClientMurmurPlayback}
 * (2026-11-02 rework -- see that class's own doc for why playback moved entirely client-side).
 * Genuinely client-only (reads {@link Minecraft#gameDirectory}) -- only ever reached through
 * {@code AudybleModClient}'s tick listener via {@link ClientMurmurRequests}, never from a
 * common-sourceset class.
 *
 * <p>A track id (see {@code voice.MusicLibrary#trackIdFor}) can contain slashes/spaces/parens from
 * the real file's own path, none of which are safe as a single Windows/Linux filename -- {@link
 * #sanitize} flattens it to one safe local filename per track, used both on disk and as the
 * in-memory download-state key.
 */
final class ClientMusicCache {

    private static final Logger LOGGER = LoggerFactory.getLogger("audyble-music");

    private enum State { IN_PROGRESS, FAILED }

    private final Path cacheDir;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ExecutorService downloader = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "Audyble-Music-Download");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentHashMap<String, State> inProgressOrFailed = new ConcurrentHashMap<>();

    ClientMusicCache() {
        cacheDir = Minecraft.getInstance().gameDirectory.toPath().resolve("audyble").resolve("music-cache");
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            LOGGER.warn("Failed to create Murmur Crystal music cache directory {}", cacheDir, e);
        }
    }

    /** The already-downloaded local file for {@code trackId}, or empty if it's missing/still downloading/failed. */
    Path localPathIfPresent(String trackId) {
        Path path = cacheDir.resolve(sanitize(trackId));
        return Files.isRegularFile(path) ? path : null;
    }

    /** Kicks off a background download if {@code trackId} isn't already present/in flight -- safe to call redundantly every tick/scan. */
    void ensureDownloading(String trackId, String host, int port) {
        if (localPathIfPresent(trackId) != null) {
            return;
        }
        String key = sanitize(trackId);
        if (inProgressOrFailed.putIfAbsent(key, State.IN_PROGRESS) != null) {
            return;
        }
        downloader.submit(() -> download(trackId, host, port, key));
    }

    private void download(String trackId, String host, int port, String key) {
        Path target = cacheDir.resolve(key);
        Path tempFile = cacheDir.resolve(key + ".part");
        try {
            String encodedId = URLEncoder.encode(trackId, StandardCharsets.UTF_8).replace("+", "%20");
            URI uri = URI.create("http://" + host + ":" + port + "/track/" + encodedId);
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build();
            HttpResponse<Path> response = http.send(request, HttpResponse.BodyHandlers.ofFile(tempFile));
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " downloading track " + trackId);
            }
            Files.move(tempFile, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            inProgressOrFailed.remove(key);
            LOGGER.info("Murmur Crystal cached track {} ({} bytes)", trackId, Files.size(target));
        } catch (Exception e) {
            LOGGER.warn("Failed to download Murmur Crystal track {}", trackId, e);
            inProgressOrFailed.put(key, State.FAILED);
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {
                // Best effort cleanup only.
            }
        }
    }

    /** Deletes any cached file not in {@code keepTrackIds} -- called whenever the station's current+queue changes, so the cache never grows past what's actually needed. */
    void evictExcept(Set<String> keepTrackIds) {
        Set<String> keepKeys = keepTrackIds.stream().map(ClientMusicCache::sanitize).collect(java.util.stream.Collectors.toSet());
        try (var stream = Files.list(cacheDir)) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                String name = path.getFileName().toString();
                if (name.endsWith(".part") || keepKeys.contains(name)) {
                    return;
                }
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    LOGGER.warn("Failed to evict stale Murmur Crystal cache file {}", path, e);
                }
            });
        } catch (IOException e) {
            LOGGER.warn("Failed to list Murmur Crystal music cache directory {}", cacheDir, e);
        }
    }

    private static String sanitize(String trackId) {
        return trackId.replaceAll("[<>:\"/\\\\|?*]", "_");
    }
}
