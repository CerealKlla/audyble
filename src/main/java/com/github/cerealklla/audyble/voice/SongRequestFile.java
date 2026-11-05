package com.github.cerealklla.audyble.voice;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

import com.github.cerealklla.audyble.config.AudybleConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plain-text, append-only song-request queue -- deliberately **not** one of this mod's JSON {@code
 * PersistedServerStore}s, since a separate external PowerShell job (not part of this mod/JVM at all)
 * needs to read, process, and rewrite this file on its own nightly schedule with no Java/JSON
 * tooling available to it. This mod only ever appends; the external job owns removing/rewriting
 * processed lines.
 *
 * <p>Format: one request per line, pipe-delimited, no header --
 * {@code <settlementPlaceId>|<url>|<requesterName>|<unixEpochSeconds>}. Pipe was chosen since none
 * of the four fields can ever legitimately contain one (a YouTube URL doesn't, a Minecraft username
 * is alphanumeric/underscore only, and the other two fields are numeric) -- no escaping needed.
 */
public final class SongRequestFile {

    private static final Logger LOGGER = LoggerFactory.getLogger("audyble-music");

    private SongRequestFile() {
    }

    /** Appends one request line. A no-op (logged) if {@link AudybleConfig#MURMUR_SONG_REQUEST_FILE} isn't configured. */
    public static synchronized void append(long settlementPlaceId, String url, String requesterName) {
        Path path = resolvePath();
        if (path == null) {
            LOGGER.warn("Song request from {} ignored -- murmurSongRequestFile is not configured", requesterName);
            return;
        }
        String line = settlementPlaceId + "|" + sanitize(url) + "|" + sanitize(requesterName) + "|" + Instant.now().getEpochSecond()
                + System.lineSeparator();
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.warn("Failed to append Murmur song request to {}", path, e);
        }
    }

    /** {@link AudybleConfig#MURMUR_SONG_REQUEST_FILE} configured and non-blank -- used to hide the request box client-side when it isn't. */
    public static boolean enabled() {
        return resolvePath() != null;
    }

    private static String sanitize(String s) {
        return s == null ? "" : s.replace("|", "").replace("\n", "").replace("\r", "").trim();
    }

    private static Path resolvePath() {
        String configured = AudybleConfig.MURMUR_SONG_REQUEST_FILE.get();
        return (configured == null || configured.isBlank()) ? null : Paths.get(configured);
    }
}
