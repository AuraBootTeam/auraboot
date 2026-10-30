package com.auraboot.framework.meta.service;

/**
 * Optional boundary for a Spring handler that owns its aggregate's persistence and visibility.
 * Invoked after command permission checks and before any idempotency replay. Implementations must
 * recheck current membership, action permission and stored target ownership, including tombstones
 * when their operation contract permits replay. The original request payload is available for binding
 * validation; caller payload is never authority and must not be mutated by this read-only boundary.
 */
public interface CommandTargetAuthorizer {
    /**
     * Return the authorized stored revision; throw on denial, missing identity or lookup failure.
     */
    long authorizeTarget(CommandHandlerContext context);
}
