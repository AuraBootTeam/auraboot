package com.auraboot.framework.eventpolicy.model;

/** How conflicting record mutations across matched rules are handled (docs/2.md §8.4). */
public enum ConflictStrategy {
    /** Reject the whole batch on any conflict (default — auditable). */
    REJECT_ON_CONFLICT,
    /** Higher rule priority wins. */
    PRIORITY_WINS,
    /** Last write wins (discouraged — hard to audit). */
    LAST_WRITE_WINS,
    /** Merge when compatible, else reject. */
    MERGE_IF_COMPATIBLE
}
