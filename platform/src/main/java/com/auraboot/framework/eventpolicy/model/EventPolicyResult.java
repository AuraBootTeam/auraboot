package com.auraboot.framework.eventpolicy.model;

import java.util.List;

/**
 * Outcome of running an event policy (docs/2.md §X.5): which rules matched and the resolved,
 * ordered, deduped, conflict-checked action plans ready for the executor.
 */
public record EventPolicyResult(
        String policyCode,
        Status status,
        List<String> matchedRuleCodes,
        List<String> skippedRuleCodes,
        List<ResolvedActionPlan> actionPlans,
        List<String> errors,
        String correlationId,
        List<String> decisionTraceIds
) {
    public EventPolicyResult(
            String policyCode,
            Status status,
            List<String> matchedRuleCodes,
            List<String> skippedRuleCodes,
            List<ResolvedActionPlan> actionPlans,
            List<String> errors) {
        this(policyCode, status, matchedRuleCodes, skippedRuleCodes, actionPlans, errors, null, List.of());
    }

    public EventPolicyResult {
        matchedRuleCodes = matchedRuleCodes == null ? List.of() : List.copyOf(matchedRuleCodes);
        skippedRuleCodes = skippedRuleCodes == null ? List.of() : List.copyOf(skippedRuleCodes);
        actionPlans = actionPlans == null ? List.of() : List.copyOf(actionPlans);
        errors = errors == null ? List.of() : List.copyOf(errors);
        decisionTraceIds = decisionTraceIds == null ? List.of() : List.copyOf(decisionTraceIds);
    }

    public EventPolicyResult withRuntimeTrace(String nextCorrelationId, List<String> nextDecisionTraceIds) {
        return new EventPolicyResult(
                policyCode,
                status,
                matchedRuleCodes,
                skippedRuleCodes,
                actionPlans,
                errors,
                nextCorrelationId,
                nextDecisionTraceIds);
    }

    public String primaryDecisionTraceId() {
        return decisionTraceIds.isEmpty() ? null : decisionTraceIds.get(0);
    }

    public enum Status {
        /** At least one rule matched and action plans were resolved. */
        MATCHED,
        /** Ran cleanly but no rule matched. */
        NOT_MATCHED,
        /** Conflicting record mutations under REJECT_ON_CONFLICT. */
        CONFLICT,
        /** UNIQUE match mode saw more than one match, or another resolve error. */
        ERROR
    }
}
