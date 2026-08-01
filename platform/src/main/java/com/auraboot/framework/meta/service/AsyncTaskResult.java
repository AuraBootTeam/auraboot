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
     * Whether the framework may execute the entire task again.
     *
     * <p>The legacy {@link #fail(String)} factory remains retryable so existing executors keep
     * their behavior. Executors that know the failure class use the explicit factories.</p>
     */
    private boolean retryable;

    /**
     * Create a successful result.
     */
    public static AsyncTaskResult ok(JsonNode data) {
        AsyncTaskResult result = new AsyncTaskResult();
        result.setSuccess(true);
        result.setData(data);
        result.setRetryable(false);
        return result;
    }

    /**
     * Create a failed result.
     */
    public static AsyncTaskResult fail(String errorMessage) {
        return retryableFailure(errorMessage);
    }

    public static AsyncTaskResult retryableFailure(String errorMessage) {
        AsyncTaskResult result = new AsyncTaskResult();
        result.setSuccess(false);
        result.setErrorMessage(errorMessage);
        result.setRetryable(true);
        return result;
    }

    public static AsyncTaskResult nonRetryableFailure(String errorMessage) {
        AsyncTaskResult result = new AsyncTaskResult();
        result.setSuccess(false);
        result.setErrorMessage(errorMessage);
        result.setRetryable(false);
        return result;
    }
}
