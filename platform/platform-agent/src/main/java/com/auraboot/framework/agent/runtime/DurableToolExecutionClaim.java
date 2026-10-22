package com.auraboot.framework.agent.runtime;

/**
 * Claim result for a direct side-effecting tool execution.
 */
public record DurableToolExecutionClaim(
        boolean acquired,
        String executionKey,
        DurableToolExecutionRecord record) {

    public static DurableToolExecutionClaim acquired(String executionKey) {
        return new DurableToolExecutionClaim(true, executionKey, null);
    }

    public static DurableToolExecutionClaim replay(DurableToolExecutionRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("record is required for replay");
        }
        return new DurableToolExecutionClaim(false, record.executionKey(), record);
    }
}
