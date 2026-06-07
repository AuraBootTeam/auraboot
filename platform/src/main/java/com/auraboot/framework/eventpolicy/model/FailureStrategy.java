package com.auraboot.framework.eventpolicy.model;

/** What happens when an action plan fails to execute (docs/2.md §8.5). Consumed by the executor. */
public enum FailureStrategy {
    FAIL_FAST,
    CONTINUE_ON_ERROR,
    ALL_OR_NOTHING,
    RETRY_ASYNC,
    DEAD_LETTER
}
