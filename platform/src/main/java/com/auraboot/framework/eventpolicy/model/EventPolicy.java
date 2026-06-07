package com.auraboot.framework.eventpolicy.model;

import java.util.List;

/**
 * An event policy (docs/2.md §X.3): when {@code eventType} fires for {@code targetType/targetKey},
 * its rules are evaluated under {@code matchMode}, and matched rules' action plans are resolved.
 */
public record EventPolicy(
        String policyCode,
        String policyName,
        String eventType,
        String targetType,
        String targetKey,
        PolicyPhase phase,
        MatchMode matchMode,
        ExecutionMode executionMode,
        FailureStrategy failureStrategy,
        ConflictStrategy conflictStrategy,
        DedupStrategy dedupStrategy,
        boolean enabled,
        List<PolicyRule> rules
) {
    public EventPolicy {
        rules = rules == null ? List.of() : rules;
        matchMode = matchMode == null ? MatchMode.COLLECT_ALL : matchMode;
        conflictStrategy = conflictStrategy == null ? ConflictStrategy.REJECT_ON_CONFLICT : conflictStrategy;
        dedupStrategy = dedupStrategy == null ? DedupStrategy.BY_IDEMPOTENCY_KEY : dedupStrategy;
    }
}
