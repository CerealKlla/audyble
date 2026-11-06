package com.github.cerealklla.audyble.npc;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Backing registry for {@link NpcReplyInterceptor} -- see that interface's own doc. */
public final class NpcReplyInterceptors {

    private static final List<NpcReplyInterceptor> INTERCEPTORS = new CopyOnWriteArrayList<>();

    private NpcReplyInterceptors() {
    }

    public static void register(NpcReplyInterceptor interceptor) {
        INTERCEPTORS.add(interceptor);
    }

    static List<NpcReplyInterceptor> all() {
        return INTERCEPTORS;
    }
}
