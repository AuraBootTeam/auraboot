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
        List<String> errors
) {
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
