package com.auraboot.framework.eventpolicy.executor.handler;

import com.auraboot.framework.eventpolicy.executor.ActionExecutionException;
import com.auraboot.framework.eventpolicy.model.ResolvedActionPlan;

import java.util.LinkedHashMap;
import java.util.Map;

final class ActionFailurePayload {

    private ActionFailurePayload() {
    }

    static Builder builder(ResolvedActionPlan plan, String failureReason) {
        return new Builder()
                .with("failureReason", failureReason)
                .with("actionType", plan != null ? plan.type() : null)
                .with("ruleCode", plan != null ? plan.ruleCode() : null);
    }

    static ActionExecutionException exception(
            ResolvedActionPlan plan,
            String message,
            String failureReason,
            Throwable cause) {
        return new ActionExecutionException(message, builder(plan, failureReason).build(), cause);
    }

    static String messageOf(Throwable error) {
        return error.getMessage() != null && !error.getMessage().isBlank()
                ? error.getMessage()
                : error.getClass().getSimpleName();
    }

    static final class Builder {
        private final Map<String, Object> payload = new LinkedHashMap<>();

        Builder with(String key, Object value) {
            if (value != null) {
                payload.put(key, value);
            }
            return this;
        }

        Builder merge(Map<String, Object> values) {
            if (values != null) {
                values.forEach(this::with);
            }
            return this;
        }

        Map<String, Object> build() {
            return payload;
        }

        ActionExecutionException exception(String message, Throwable cause) {
            return new ActionExecutionException(message, build(), cause);
        }
    }
}
