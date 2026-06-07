package com.auraboot.framework.decision.model;

/** Outcome status of a decision evaluation (docs/1.md §12.3). */
public enum DecisionStatus {
    /** Decision matched. */
    MATCHED,
    /** Executed successfully but did not match. */
    NOT_MATCHED,
    /** Missing fields / indeterminate logic — no definite result. */
    UNKNOWN,
    /** A validation-type decision found violations. */
    VIOLATED,
    /** Rule execution failed. */
    ERROR,
    /** Skipped (disabled / version unavailable / caller policy). */
    SKIPPED
}
