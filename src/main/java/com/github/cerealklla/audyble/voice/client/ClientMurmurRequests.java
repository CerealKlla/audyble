package com.github.cerealklla.audyble.voice.client;

import java.util.Optional;

import com.github.cerealklla.audyble.voice.MurmurPrefetchHintPayload;
import com.github.cerealklla.audyble.voice.MurmurTunedInPayload;
import com.github.cerealklla.nuclyus.request.ClientRequestSlot;

/**
 * Client-side bridge for the three Murmur Crystal playback payloads -- deliberately zero
 * client-only imports, same reasoning as {@link ClientMurmurCrystalRequest}: written from the
 * common {@code AudybleMod#registerPayloads} handlers (which must stay harmless to class-load on
 * a dedicated server), and read/cleared only from a genuinely client-only tick listener in {@code
 * AudybleModClient} that's the only place allowed to touch {@code ClientMurmurPlayback} (which
 * freely references {@code Minecraft}/local audio output, since it's only ever reached from there).
 */
public final class ClientMurmurRequests {

    private static final ClientRequestSlot<MurmurPrefetchHintPayload> PREFETCH = new ClientRequestSlot<>();
    private static final ClientRequestSlot<MurmurTunedInPayload> TUNED_IN = new ClientRequestSlot<>();
    private static final ClientRequestSlot<Boolean> STOP = new ClientRequestSlot<>();

    private ClientMurmurRequests() {
    }

    public static void requestPrefetch(MurmurPrefetchHintPayload payload) {
        PREFETCH.request(payload);
    }

    public static Optional<MurmurPrefetchHintPayload> takePendingPrefetch() {
        return PREFETCH.takePending();
    }

    public static void requestTunedIn(MurmurTunedInPayload payload) {
        TUNED_IN.request(payload);
    }

    public static Optional<MurmurTunedInPayload> takePendingTunedIn() {
        return TUNED_IN.takePending();
    }

    public static void requestStop() {
        STOP.request(Boolean.TRUE);
    }

    public static boolean takePendingStop() {
        return STOP.takePending().isPresent();
    }
}
