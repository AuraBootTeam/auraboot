package com.auraboot.framework.permission.engine.model;

/**
 * Verdict returned by each evaluator in the permission pipeline.
 *
 * <ul>
 *   <li>{@link #ALLOW} — evaluator explicitly grants access</li>
 *   <li>{@link #DENY} — evaluator explicitly denies access</li>
 *   <li>{@link #NOT_APPLICABLE} — evaluator has no opinion (stub / layer not active)</li>
 * </ul>
 */
public enum EvaluationVerdict {
    ALLOW,
    DENY,
    NOT_APPLICABLE
}
