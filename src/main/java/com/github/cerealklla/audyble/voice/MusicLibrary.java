package com.github.cerealklla.audyble.voice;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import com.github.cerealklla.audyble.config.AudybleConfig;
import com.github.cerealklla.audyble.npc.PcmAudio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scans {@link AudybleConfig#MUSIC_SOURCE_DIRECTORY} for real music to play through the Murmur
 * Crystal (replacing {@link MurmurCrystalEngine}'s Phase 1 placeholder tone when configured),
 * and decodes a chosen Ogg Vorbis or MP3 file to mono 48kHz PCM via the bundled {@code vorbisspi}/
 * {@code mp3spi} {@code javax.sound.sampled} SPI plugins (jarJar-embedded, see build.gradle --
 * plain Java libraries with no other way to reach a player's install; MP3 support added
 * 2026-11-02 once a real test file turned out not to be Ogg).
 *
 * <p>Built around the Clone Hero "Songs" folder convention (each song is its own subfolder
 * containing several isolated stem files -- {@code guitar.ogg}, {@code drums_1.ogg},
 * {@code vocals.ogg}, etc. -- alongside one {@code song.ogg}, the backing track for everything
 * not separately playable in-game). Playing every stem mixed together would need real audio
 * mixing this class deliberately doesn't do; {@code song.ogg} alone is a reasonable
 * approximation of "the song" without it. A source directory that isn't laid out this way still
 * works, just less precisely -- see {@link #scan} for the exact fallback rule.
 */
public final class MusicLibrary {

    private static final Logger LOGGER = LoggerFactory.getLogger("audyble-music");
    private static final int TARGET_SAMPLE_RATE = 48000;

    private static final AtomicReference<List<Path>> CACHE = new AtomicReference<>();
    private static volatile String cachedForDirectory;

    private MusicLibrary() {
    }

    /**
     * Artist/song, parsed from a track's parent folder name -- the Clone Hero chart convention
     * names every song's own folder {@code "<Artist> - <Song>"} (confirmed directly against a real
     * library: "+44 - Lycanthrope", "3 Doors Down - Kryptonite", etc.), which is far more reliable
     * for this library than reading the Ogg file's own Vorbis comment tags would be (most chart
     * downloads don't bother filling those in). Falls back to the bare filename as the song with an
     * "Unknown Artist" placeholder for a library that isn't laid out this way.
     */
    public record TrackInfo(String artist, String song) {
    }

    public static TrackInfo trackInfoFor(Path track) {
        Path parent = track.getParent();
        String label = parent != null ? parent.getFileName().toString() : track.getFileName().toString();
        int separator = label.indexOf(" - ");
        if (separator > 0) {
            return new TrackInfo(label.substring(0, separator).trim(), label.substring(separator + 3).trim());
        }
        String fileName = track.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        return new TrackInfo("Unknown Artist", dot > 0 ? fileName.substring(0, dot) : fileName);
    }

    /**
     * A stable id for {@code track}, used both as the HTTP path clients request it by (see {@code
     * voice.MusicHttpServer}) and the id sent over the network to identify it -- the file's path
     * relative to the configured music source directory, forward-slash-normalized so it round-trips
     * identically regardless of the host OS. Inverse of {@link #resolveTrackId}.
     */
    public static String trackIdFor(Path track) {
        String configured = AudybleConfig.MUSIC_SOURCE_DIRECTORY.get();
        Path root = Paths.get(configured);
        return root.relativize(track).toString().replace('\\', '/');
    }

    /**
     * Resolves a client-supplied {@code trackId} back to a real file under the configured music
     * source directory, or empty if it doesn't exist -- used by {@code voice.MusicHttpServer} to
     * serve a request. Deliberately re-validates that the resolved path is still actually inside the
     * configured root after normalization (rejects any {@code trackId} containing {@code ..} segments
     * that would otherwise escape it) -- the one real security boundary in this whole feature, since
     * this id ultimately comes from a network request.
     */
    public static java.util.Optional<Path> resolveTrackId(String trackId) {
        String configured = AudybleConfig.MUSIC_SOURCE_DIRECTORY.get();
        if (configured == null || configured.isBlank() || trackId == null || trackId.isBlank()) {
            return java.util.Optional.empty();
        }
        Path root = Paths.get(configured).normalize();
        Path resolved = root.resolve(trackId).normalize();
        if (!resolved.startsWith(root) || !Files.isRegularFile(resolved)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(resolved);
    }

    /**
     * The track's real duration in seconds, read from the file's own header/VBR-index metadata via
     * {@link AudioSystem#getAudioFileFormat} -- confirmed directly (not assumed) that both vorbisspi
     * and mp3spi report an accurate frame length/frame rate this way without decoding any actual
     * audio data, in ~30ms even for a 5-minute file. Needed so {@code MurmurStation} can track when
     * a track ends and advance its queue without ever decoding anything server-side anymore
     * (2026-11-02 rework -- playback moved entirely client-side).
     */
    public static double probeDurationSeconds(Path file) throws Exception {
        var format = AudioSystem.getAudioFileFormat(file.toFile());
        float frameRate = format.getFormat().getFrameRate();
        int frameLength = format.getFrameLength();
        if (frameLength <= 0 || frameRate <= 0) {
            throw new IllegalStateException("Unknown duration (no frame length/rate in file header) for " + file);
        }
        return frameLength / (double) frameRate;
    }

    /**
     * Picks one track for the configured source directory, or empty if none is configured/found.
     * Normally random; but see {@link AudybleConfig#DEBUG_FORCE_TRACK_FILENAME} -- when set, this
     * always returns the matching file instead (or empty, logging a warning, if no file under the
     * source directory has that filename), so a specific known-good file can be played on demand to
     * isolate a decode/pipeline issue from a source-file issue.
     */
    static java.util.Optional<Path> pickRandomTrack() {
        String configured = AudybleConfig.MUSIC_SOURCE_DIRECTORY.get();
        if (configured == null || configured.isBlank()) {
            return java.util.Optional.empty();
        }
        List<Path> tracks = tracksFor(configured);
        if (tracks.isEmpty()) {
            return java.util.Optional.empty();
        }
        String forced = AudybleConfig.DEBUG_FORCE_TRACK_FILENAME.get();
        if (forced != null && !forced.isBlank()) {
            java.util.Optional<Path> match = tracks.stream()
                    .filter(p -> p.getFileName().toString().equalsIgnoreCase(forced))
                    .findFirst();
            if (match.isEmpty()) {
                LOGGER.warn("Murmur Crystal debugForceTrackFilename '{}' was not found under the music "
                        + "source directory -- falling back to the placeholder tone instead of silently "
                        + "picking a random track, so this isn't mistaken for the forced file", forced);
            }
            return match;
        }
        return java.util.Optional.of(tracks.get(ThreadLocalRandom.current().nextInt(tracks.size())));
    }

    /** Cached per-directory -- rescanning a large real music library (thousands of songs) on every pick would be wasteful. */
    private static List<Path> tracksFor(String configured) {
        List<Path> cached = CACHE.get();
        if (cached != null && configured.equals(cachedForDirectory)) {
            return cached;
        }
        List<Path> scanned = scan(Paths.get(configured));
        cachedForDirectory = configured;
        CACHE.set(scanned);
        return scanned;
    }

    /** Extensions this library can actually decode -- {@link #decodeToMono48k}'s SPI plugins (vorbisspi, mp3spi). */
    private static final List<String> SUPPORTED_EXTENSIONS = List.of(".ogg", ".mp3");

    /**
     * Recursively finds playable tracks: within any folder that contains a file literally named
     * {@code song.ogg} or {@code song.mp3} (case-insensitive), that one file is the folder's track
     * and its sibling stems are skipped; any other folder contributes every supported-extension
     * file it directly contains. Not a recursive merge across subfolders beyond that -- a folder
     * with its own {@code song.*} doesn't also contribute its parent's unrelated files, and vice
     * versa.
     */
    private static List<Path> scan(Path root) {
        List<Path> result = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            LOGGER.warn("Murmur Crystal music source directory does not exist or is not a directory: {}", root);
            return result;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    Path songFile = findSongFile(dir);
                    if (songFile != null) {
                        result.add(songFile);
                        return FileVisitResult.CONTINUE;
                    }
                    try (var stream = Files.list(dir)) {
                        stream.filter(p -> !Files.isDirectory(p))
                                .filter(p -> hasSupportedExtension(p.getFileName().toString()))
                                .forEach(result::add);
                    } catch (IOException e) {
                        LOGGER.warn("Failed to list music directory {}", dir, e);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOGGER.warn("Failed to scan Murmur Crystal music source directory {}", root, e);
        }
        LOGGER.info("Murmur Crystal music library: found {} track(s) under {}", result.size(), root);
        return result;
    }

    private static boolean hasSupportedExtension(String fileName) {
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        return SUPPORTED_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    private static Path findSongFile(Path dir) {
        try (var stream = Files.list(dir)) {
            return stream.filter(p -> !Files.isDirectory(p))
                    .filter(p -> {
                        String lower = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                        return SUPPORTED_EXTENSIONS.stream().anyMatch(ext -> lower.equals("song" + ext));
                    })
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Fully decodes one Ogg Vorbis or MP3 file to mono 48kHz 16-bit PCM. Slow (real file I/O +
     * decode) -- always call off whichever thread needs the result immediately. Public since the
     * 2026-11-02 rework moved the only real caller client-side ({@code voice.client
     * .ClientMurmurPlayback}) -- nothing in this method references any client-only type, so it's
     * still safe to keep in this common-sourceset class rather than duplicating it.
     */
    public static short[] decodeToMono48k(Path file) throws Exception {
        try (AudioInputStream rawIn = AudioSystem.getAudioInputStream(file.toFile())) {
            AudioFormat baseFormat = rawIn.getFormat();
            AudioFormat pcmFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    baseFormat.getSampleRate(),
                    16,
                    baseFormat.getChannels(),
                    baseFormat.getChannels() * 2,
                    baseFormat.getSampleRate(),
                    false);
            try (AudioInputStream pcmIn = AudioSystem.getAudioInputStream(pcmFormat, rawIn)) {
                byte[] bytes = pcmIn.readAllBytes();
                int channels = pcmFormat.getChannels();
                int frameCount = bytes.length / (2 * channels);
                short[] mono = new short[frameCount];
                int pos = 0;
                for (int i = 0; i < frameCount; i++) {
                    int sum = 0;
                    for (int c = 0; c < channels; c++) {
                        int sample = (short) ((bytes[pos] & 0xFF) | (bytes[pos + 1] << 8));
                        sum += sample;
                        pos += 2;
                    }
                    mono[i] = (short) (sum / channels);
                }
                int sourceRate = (int) pcmFormat.getSampleRate();
                return sourceRate == TARGET_SAMPLE_RATE ? mono : PcmAudio.resample(mono, sourceRate, TARGET_SAMPLE_RATE);
            }
        }
    }
}
