package com.auraboot.framework.behavior.service;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import java.util.Map;
import java.util.Set;

/** Version-bound task goal; contains no authority, runtime identity, or execution grant. */
public record AnalyticsExecutionIntent(String type, String goal) {
    public static AnalyticsExecutionIntent parse(Object value) {
        if (!(value instanceof Map<?, ?> fields)
                || !fields.keySet().equals(Set.of("type", "goal"))
                || !"agent_task".equals(fields.get("type"))
                || !(fields.get("goal") instanceof String goal)
                || goal.isBlank() || goal.length() > 4000) {
            throw new BusinessException(ResponseCode.BadParam, "Invalid analytics execution intent");
        }
        return new AnalyticsExecutionIntent("agent_task", goal);
    }
    public Map<String, String> toMap() {
        return Map.of("type", type, "goal", goal);
    }
}
