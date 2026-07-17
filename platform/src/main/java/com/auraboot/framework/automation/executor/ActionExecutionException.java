package com.auraboot.framework.automation.executor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Automation action failure that still carries user-facing runtime evidence.
 */
public class ActionExecutionException extends RuntimeException {

    private final Map<String, Object> resultPayload;

    public ActionExecutionException(String message, Map<String, Object> resultPayload, Throwable cause) {
        super(message, cause);
        this.resultPayload = resultPayload != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(resultPayload))
                : Map.of();
    }

    public Map<String, Object> resultPayload() {
        return resultPayload;
    }
}
