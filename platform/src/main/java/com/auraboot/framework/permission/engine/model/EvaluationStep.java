package com.auraboot.framework.permission.engine.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * A single step in the permission evaluation pipeline.
 *
 * @param evaluatorName name of the evaluator (e.g. "RolePermission", "DataScope")
 * @param verdict       the verdict returned by this evaluator
 * @param reason        human-readable reason for the verdict
 * @param details       structured machine-readable trace details for UI/audit drill-down
 */
public record EvaluationStep(
        String evaluatorName,
        EvaluationVerdict verdict,
        String reason,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        Map<String, Object> details
) {
    public EvaluationStep(String evaluatorName, EvaluationVerdict verdict, String reason) {
        this(evaluatorName, verdict, reason, Map.of());
    }

    public EvaluationStep {
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}
