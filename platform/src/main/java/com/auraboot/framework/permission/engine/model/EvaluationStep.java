package com.auraboot.framework.permission.engine.model;

/**
 * A single step in the permission evaluation pipeline.
 *
 * @param evaluatorName name of the evaluator (e.g. "RolePermission", "DataScope")
 * @param verdict       the verdict returned by this evaluator
 * @param reason        human-readable reason for the verdict
 */
public record EvaluationStep(
        String evaluatorName,
        EvaluationVerdict verdict,
        String reason
) {
}
