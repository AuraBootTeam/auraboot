package com.auraboot.framework.eventpolicy.executor;

/** Outcome of executing a single action plan (docs/2.md §8.5). */
public enum ActionExecutionStatus {
    SUCCESS,
    FAILED,
    /** Idempotency key already executed successfully — not re-run. */
    SKIPPED,
    /** No registered handler supports the action type. */
    NO_HANDLER,
    /** Failed but queued for async retry (FailureStrategy.RETRY_ASYNC). */
    RETRY_PENDING,
    /** Retries exhausted / routed to dead letter (FailureStrategy.DEAD_LETTER). */
    DEAD_LETTER,
    /** Not executed because an earlier action failed under FAIL_FAST / ALL_OR_NOTHING. */
    NOT_EXECUTED
}
