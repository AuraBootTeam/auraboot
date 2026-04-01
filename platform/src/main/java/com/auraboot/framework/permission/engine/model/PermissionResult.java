package com.auraboot.framework.permission.engine.model;

import java.util.List;

/**
 * Final result of the full permission evaluation pipeline.
 *
 * @param granted whether the action is allowed
 * @param reason  human-readable summary reason
 * @param steps   ordered list of evaluation steps executed
 */
public record PermissionResult(
        boolean granted,
        String reason,
        List<EvaluationStep> steps
) {

    /**
     * Create an ALLOW result with the given evaluation steps.
     */
    public static PermissionResult allow(List<EvaluationStep> steps) {
        return new PermissionResult(true, "Granted", steps);
    }

    /**
     * Create a DENY result with the given reason and evaluation steps.
     */
    public static PermissionResult deny(String reason, List<EvaluationStep> steps) {
        return new PermissionResult(false, reason, steps);
    }
}
