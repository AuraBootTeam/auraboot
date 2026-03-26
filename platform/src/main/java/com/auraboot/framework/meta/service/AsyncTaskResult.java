package com.auraboot.framework.meta.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * Result returned by an {@link AsyncTaskExecutor} after execution.
 */
@Data
public class AsyncTaskResult {

    private boolean success;
    private JsonNode data;
    private String errorMessage;

    /**
     * Create a successful result.
     */
    public static AsyncTaskResult ok(JsonNode data) {
        AsyncTaskResult result = new AsyncTaskResult();
        result.setSuccess(true);
        result.setData(data);
        return result;
    }

    /**
     * Create a failed result.
     */
    public static AsyncTaskResult fail(String errorMessage) {
        AsyncTaskResult result = new AsyncTaskResult();
        result.setSuccess(false);
        result.setErrorMessage(errorMessage);
        return result;
    }
}
