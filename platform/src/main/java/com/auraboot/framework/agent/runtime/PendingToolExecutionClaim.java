package com.auraboot.framework.agent.runtime;

public record PendingToolExecutionClaim(boolean acquired, PendingToolExecutionRecord record) {

    public static PendingToolExecutionClaim acquired(String executionKey) {
        return new PendingToolExecutionClaim(true, PendingToolExecutionRecord.running(executionKey));
    }

    public static PendingToolExecutionClaim replay(PendingToolExecutionRecord record) {
        return new PendingToolExecutionClaim(false, record);
    }
}
