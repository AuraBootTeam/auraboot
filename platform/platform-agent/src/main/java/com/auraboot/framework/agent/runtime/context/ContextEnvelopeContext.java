package com.auraboot.framework.agent.runtime.context;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Thread-bound immutable context envelope for runtime, checkpoint and audit writers.
 */
public final class ContextEnvelopeContext {

    private static final ThreadLocal<ContextEnvelope> CURRENT = new ThreadLocal<>();

    private ContextEnvelopeContext() {
    }

    public static Optional<ContextEnvelope> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static void restore(ContextEnvelope envelope) {
        if (envelope == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(envelope);
        }
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static <T> T callWith(ContextEnvelope envelope, Supplier<T> action) {
        if (envelope == null) {
            throw new IllegalArgumentException("context envelope is required");
        }
        ContextEnvelope previous = CURRENT.get();
        CURRENT.set(envelope);
        try {
            return action.get();
        } finally {
            CURRENT.remove();
            if (previous != null) {
                CURRENT.set(previous);
            }
        }
    }
}
