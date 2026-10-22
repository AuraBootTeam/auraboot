package com.auraboot.framework.agent.runtime;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record PendingToolExecutionRecord(
        String executionKey,
        PendingToolExecutionStatus status,
        Map<String, Object> result,
        String errorMessage,
        long updatedAt) {

    public PendingToolExecutionRecord {
        result = result == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(result));
        status = status == null ? PendingToolExecutionStatus.RUNNING : status;
        updatedAt = updatedAt > 0 ? updatedAt : Instant.now().toEpochMilli();
    }

    public static PendingToolExecutionRecord running(String executionKey) {
        return new PendingToolExecutionRecord(
                executionKey,
                PendingToolExecutionStatus.RUNNING,
                Map.of(),
                null,
                Instant.now().toEpochMilli());
    }

    public static PendingToolExecutionRecord succeeded(String executionKey, Map<String, Object> result) {
        return new PendingToolExecutionRecord(
                executionKey,
                PendingToolExecutionStatus.SUCCEEDED,
                result,
                null,
                Instant.now().toEpochMilli());
    }

    public static PendingToolExecutionRecord failed(String executionKey,
                                                    Map<String, Object> result,
                                                    String errorMessage) {
        return new PendingToolExecutionRecord(
                executionKey,
                PendingToolExecutionStatus.FAILED,
                result,
                errorMessage,
                Instant.now().toEpochMilli());
    }
}
