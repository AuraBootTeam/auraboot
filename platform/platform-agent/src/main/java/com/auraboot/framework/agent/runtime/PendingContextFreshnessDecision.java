package com.auraboot.framework.agent.runtime;

public record PendingContextFreshnessDecision(
        boolean fresh,
        ContextConflictPolicy conflictPolicy,
        String reasonCode,
        String message) {

    public static PendingContextFreshnessDecision freshDecision() {
        return new PendingContextFreshnessDecision(true, null, "fresh", null);
    }

    public static PendingContextFreshnessDecision stale(ContextConflictPolicy conflictPolicy,
                                                       String reasonCode,
                                                       String message) {
        return new PendingContextFreshnessDecision(
                false,
                conflictPolicy != null ? conflictPolicy : ContextConflictPolicy.REJECT_AND_REPLAN,
                reasonCode,
                message);
    }
}
