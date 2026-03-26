package com.auraboot.framework.meta.constant;

/**
 * Retry strategy semantics for command execution.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
public enum RetrySemantics {

    /**
     * Fail immediately on error, no retry
     */
    NO_RETRY,

    /**
     * Retry with backoff on transient errors
     */
    RETRY_ON_FAILURE,

    /**
     * Safe to replay; uses IdempotencyService to deduplicate
     */
    IDEMPOTENT
}
