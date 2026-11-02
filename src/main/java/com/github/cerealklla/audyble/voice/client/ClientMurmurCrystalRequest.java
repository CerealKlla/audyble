package com.github.cerealklla.audyble.voice.client;

import java.util.Optional;

import com.github.cerealklla.audyble.voice.MurmurCrystalState;
import com.github.cerealklla.nuclyus.request.ClientRequestSlot;

/**
 * Client-side bridge for "the Murmur Crystal screen should open" -- deliberately zero client-only
 * imports, same reasoning as Lyfe's {@code knowledge.ClientWritingRequest}: written from the common
 * {@code AudybleMod#registerPayloads} handler (which must stay harmless to class-load on a
 * dedicated server), and read/cleared only from a genuinely client-only tick listener in {@code
 * AudybleModClient} that's the only place actually allowed to touch {@code Minecraft}/{@code
 * Screen}.
 */
public final class ClientMurmurCrystalRequest {

    private static final ClientRequestSlot<MurmurCrystalState> SLOT = new ClientRequestSlot<>();

    private ClientMurmurCrystalRequest() {
    }

    public static void request(MurmurCrystalState state) {
        SLOT.request(state);
    }

    public static Optional<MurmurCrystalState> takePending() {
        return SLOT.takePending();
    }
}
