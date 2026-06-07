package com.auraboot.framework.decision.model;

/** Actual runtime adapter that executes a decision (docs/1.md §13.5). */
public enum RuntimeAdapter {
    AST_EVALUATOR,
    SAFE_SPEL,
    CROSS_FIELD_ENGINE,
    PLATFORM_DECISION_TABLE,
    DROOLS_DMN,
    DROOLS_DRL
}
