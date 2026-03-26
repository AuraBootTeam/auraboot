package com.auraboot.framework.meta.validation;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Aggregated result from CrossFieldRuleEngine evaluation.
 */
public record RuleEvaluationResult(
    List<RuleViolation> errors,
    List<RuleViolation> warnings
) {
    public boolean hasErrors() {
        return errors != null && !errors.isEmpty();
    }

    public boolean hasWarnings() {
        return warnings != null && !warnings.isEmpty();
    }

    public String formatErrorMessages() {
        if (errors == null || errors.isEmpty()) return "";
        return errors.stream()
            .map(RuleViolation::message)
            .collect(Collectors.joining("; "));
    }
}
