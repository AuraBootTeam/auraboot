package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.plugin.extension.RecordReadAccessor;
import org.springframework.security.access.AccessDeniedException;

import java.util.Map;
import java.util.function.Supplier;

/** Required dependency reads consume the same short-lived business resource contract. */
public final class BusinessResourceReadScope {
    private record State(RecordReadAccessor reader, Runnable verify, Thread thread, Object identity) {}
    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();
    private BusinessResourceReadScope() {}

    public static <T> T withReader(Object identity, RecordReadAccessor reader, Runnable verify, Supplier<T> operation) {
        var previous = CURRENT.get();
        var state = new State(java.util.Objects.requireNonNull(reader), java.util.Objects.requireNonNull(verify), Thread.currentThread(), java.util.Objects.requireNonNull(identity));
        CURRENT.set(state);
        try {
            verify.run();
            T result = operation.get();
            if (CURRENT.get() != state) throw new AccessDeniedException("Business dependency scope changed");
            verify.run();
            return result;
        }
        finally {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }

    static boolean active() { return CURRENT.get() != null; }
    static Object identity() { return CURRENT.get() == null ? null : CURRENT.get().identity(); }
    static Map<String, Object> read(String model, String pid) {
        var state = CURRENT.get();
        if (state == null || state.thread() != Thread.currentThread()) throw new AccessDeniedException("Business dependency authority expired");
        state.verify().run();
        Map<String, Object> result = state.reader().getById(model, pid);
        if (CURRENT.get() != state) throw new AccessDeniedException("Business dependency scope changed");
        state.verify().run();
        return result;
    }
}
