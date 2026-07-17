package com.auraboot.framework.decision.rule;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Normalized references extracted from rule-center contracts.
 */
public record RuleReferenceSet(
        List<String> fieldRefs,
        List<String> decisionRefs,
        List<String> conditionFragmentRefs
) {
    public static RuleReferenceSet of(Set<String> fieldRefs, Set<String> decisionRefs) {
        return of(fieldRefs, decisionRefs, Set.of());
    }

    public static RuleReferenceSet of(Set<String> fieldRefs, Set<String> decisionRefs,
                                      Set<String> conditionFragmentRefs) {
        return new RuleReferenceSet(
                List.copyOf(fieldRefs == null ? Set.of() : new LinkedHashSet<>(fieldRefs)),
                List.copyOf(decisionRefs == null ? Set.of() : new LinkedHashSet<>(decisionRefs)),
                List.copyOf(conditionFragmentRefs == null
                        ? Set.of()
                        : new LinkedHashSet<>(conditionFragmentRefs)));
    }

    public static RuleReferenceSet empty() {
        return new RuleReferenceSet(List.of(), List.of(), List.of());
    }
}
