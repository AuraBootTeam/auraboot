package com.auraboot.framework.eventpolicy.model;

/** How resolved action plans are executed (docs/2.md §X.3). */
public enum ExecutionMode {
    /** Execute in resolved order (rule.priority then action.order). */
    ORDERED,
    /** Execution order not significant. */
    UNORDERED
}
