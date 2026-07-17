package com.auraboot.framework.eventpolicy.executor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Per-action execution outcome (docs/2.md §X.5 actionPlans[].status). */
public record ActionExecutionResult(
        String ruleCode,
        String type,
        String idempotencyKey,
        ActionExecutionStatus status,
        String error,
        Map<String, Object> resultPayload
) {
    public ActionExecutionResult(String ruleCode, String type, String idempotencyKey,
                                 ActionExecutionStatus status, String error) {
        this(ruleCode, type, idempotencyKey, status, error, Map.of());
    }

    public static ActionExecutionResult of(String ruleCode, String type, String key, ActionExecutionStatus status) {
        return new ActionExecutionResult(ruleCode, type, key, status, null, Map.of());
    }

    public static ActionExecutionResult success(String ruleCode, String type, String key,
                                                Map<String, Object> resultPayload) {
        return new ActionExecutionResult(ruleCode, type, key, ActionExecutionStatus.SUCCESS, null,
                resultPayload != null
                        ? Collections.unmodifiableMap(new LinkedHashMap<>(resultPayload))
                        : Map.of());
    }

    public boolean isFailure() {
        return status == ActionExecutionStatus.FAILED || status == ActionExecutionStatus.NO_HANDLER;
    }
}
