package com.auraboot.framework.plugin.extension;

import java.util.function.Function;

/**
 * Host-owned transaction boundary for bounded plugin checkpoints.
 *
 * <p>Plugin command handlers normally participate in the command pipeline's outer transaction.
 * Long-running background handlers sometimes need a small, internally consistent checkpoint to
 * become visible before that outer command finishes (for example a five-row import chunk). This
 * bridge suspends the outer transaction, executes the callback in {@code REQUIRES_NEW}, and then
 * resumes the caller transaction.
 *
 * <p>The callback is synchronous and must not escape to another thread. External side effects do
 * not become transactional merely because they are invoked inside this callback.
 */
public interface IndependentTransactionAccessor {

    String SETTINGS_KEY = "__independentTransactionAccessor";

    /** Executes one bounded unit of dynamic-data work in an independent transaction. */
    <T> T requiresNew(Function<DataAccessor, T> work);
}
