package com.auraboot.framework.eventpolicy.executor;

import java.util.List;

/** Aggregate outcome of executing a policy's resolved action plans (docs/2.md §X.5). */
public record PolicyExecutionResult(
        String policyCode,
        OverallStatus overallStatus,
        List<ActionExecutionResult> actions
) {
    public enum OverallStatus { ALL_SUCCESS, PARTIAL_SUCCESS, FAILED, NOTHING_TO_DO }

    public long successCount() {
        return actions.stream().filter(a -> a.status() == ActionExecutionStatus.SUCCESS).count();
    }
}
