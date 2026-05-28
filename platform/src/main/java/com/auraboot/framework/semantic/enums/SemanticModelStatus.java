package com.auraboot.framework.semantic.enums;

/**
 * Lifecycle of a semantic model / metric.
 *
 * <p>State transitions (see PRD 16 §3 state machine):
 * <pre>
 * DRAFT → VALIDATE → DEV → STAGED → ACTIVE → DEPRECATED → REMOVED
 * </pre>
 * Backwards transitions are restricted; see SemanticPublishService.
 */
public enum SemanticModelStatus {
    DRAFT,
    VALIDATE,
    DEV,
    STAGED,
    ACTIVE,
    DEPRECATED,
    REMOVED
}
