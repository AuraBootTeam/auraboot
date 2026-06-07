package com.auraboot.framework.eventpolicy.executor;

/** Per-action execution outcome (docs/2.md §X.5 actionPlans[].status). */
public record ActionExecutionResult(
        String ruleCode,
        String type,
        String idempotencyKey,
        ActionExecutionStatus status,
        String error
) {
    public static ActionExecutionResult of(String ruleCode, String type, String key, ActionExecutionStatus status) {
        return new ActionExecutionResult(ruleCode, type, key, status, null);
    }

    public boolean isFailure() {
        return status == ActionExecutionStatus.FAILED || status == ActionExecutionStatus.NO_HANDLER;
    }
}
