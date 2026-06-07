package com.auraboot.framework.eventpolicy.model;

/** How duplicate action plans across matched rules are collapsed (docs/2.md §8.3). */
public enum DedupStrategy {
    /** No dedup — emit every action plan. */
    NONE,
    /** Collapse action plans sharing the same idempotency key. */
    BY_IDEMPOTENCY_KEY,
    /** Collapse action plans with the same action type and target. */
    BY_ACTION_TYPE_AND_TARGET
}
