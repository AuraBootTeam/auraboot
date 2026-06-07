package com.auraboot.framework.decision.model;

/** Definition form of a decision (docs/1.md §13.4). Separate from {@link RuntimeAdapter}. */
public enum DecisionKind {
    SIMPLE_CONDITION,
    CROSS_FIELD,
    DECISION_TABLE,
    DMN,
    DRL,
    TEMPLATE_MAPPING,
    CUSTOM
}
