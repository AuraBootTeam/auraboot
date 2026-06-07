package com.auraboot.framework.decision.model;

import java.util.List;

/**
 * Result of validating a decision definition (docs/1.md §17.1). {@code errors} block publish;
 * {@code warnings} are advisory. {@code fieldRefs}/{@code functionRefs} feed impact analysis.
 */
public record DecisionValidateResult(
        boolean valid,
        List<Issue> errors,
        List<Issue> warnings,
        List<String> fieldRefs,
        List<String> functionRefs
) {
    public record Issue(String code, String message) {}

    public static DecisionValidateResult ok(List<String> fieldRefs, List<String> functionRefs) {
        return new DecisionValidateResult(true, List.of(), List.of(), fieldRefs, functionRefs);
    }

    public static DecisionValidateResult invalid(List<Issue> errors) {
        return new DecisionValidateResult(false, errors, List.of(), List.of(), List.of());
    }
}
