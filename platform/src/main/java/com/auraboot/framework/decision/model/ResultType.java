package com.auraboot.framework.decision.model;

/** Shape of a decision's outputs (docs/1.md §12.4). */
public enum ResultType {
    BOOLEAN,
    ROUTE,
    ASSIGNEE,
    VALIDATION,
    DEADLINE,
    ACTION_PLAN,
    MAP,
    DECISION_TABLE,
    CUSTOM
}
