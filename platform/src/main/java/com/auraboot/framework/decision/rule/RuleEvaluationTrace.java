package com.auraboot.framework.decision.rule;

import com.auraboot.framework.decision.ast.Truth;
import com.auraboot.framework.decision.model.DecisionStatus;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Unified trace envelope for platform rule-center evaluations.
 */
public record RuleEvaluationTrace(
        String traceId,
        String consumerType,
        String consumerCode,
        String consumerNodeId,
        RuleBindingKind bindingKind,
        String decisionCode,
        Integer decisionVersion,
        DecisionVersionPolicy versionPolicy,
        Truth conditionResult,
        DecisionStatus decisionStatus,
        boolean matched,
        Map<String, Object> inputSnapshot,
        Map<String, Object> outputSnapshot,
        boolean fallbackApplied,
        long durationMs,
        String errorCode,
        List<String> errors,
        List<String> unknownReasons,
        List<String> fieldRefs,
        List<String> decisionRefs
) {
    public RuleEvaluationTrace {
        inputSnapshot = copyMap(inputSnapshot);
        outputSnapshot = copyMap(outputSnapshot);
        errors = errors == null ? List.of() : List.copyOf(errors);
        unknownReasons = unknownReasons == null ? List.of() : List.copyOf(unknownReasons);
        fieldRefs = fieldRefs == null ? List.of() : List.copyOf(fieldRefs);
        decisionRefs = decisionRefs == null ? List.of() : List.copyOf(decisionRefs);
    }

    private static Map<String, Object> copyMap(Map<String, Object> input) {
        return input == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(input));
    }
}
