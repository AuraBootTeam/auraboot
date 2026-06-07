package com.auraboot.framework.decision.runtime;

import com.auraboot.framework.decision.model.DecisionKind;
import com.auraboot.framework.decision.model.RuntimeAdapter;
import com.auraboot.framework.decision.model.VersionStatus;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * An executable decision version resolved for evaluation (the in-memory form of an
 * {@code ab_drt_version} row, docs/1.md §13.3). The version resolver produces this from a
 * {@code decisionRef} + binding; adapters consume {@link #content()} according to {@link #kind()}.
 */
public record ResolvedDecision(
        String decisionCode,
        Integer version,
        String versionTag,
        VersionStatus status,
        DecisionKind kind,
        RuntimeAdapter runtimeAdapter,
        JsonNode content
) {
    /** Convenience for a draft/test SIMPLE_CONDITION whose content is a Condition AST node. */
    public static ResolvedDecision simpleCondition(String code, Integer version, VersionStatus status, JsonNode ast) {
        return new ResolvedDecision(code, version, null, status, DecisionKind.SIMPLE_CONDITION,
                RuntimeAdapter.AST_EVALUATOR, ast);
    }
}
