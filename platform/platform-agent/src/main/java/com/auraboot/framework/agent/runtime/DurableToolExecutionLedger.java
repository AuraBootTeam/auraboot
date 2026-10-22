package com.auraboot.framework.agent.runtime;

import java.util.List;

/**
 * Durable claim/replay boundary for direct tool executions with external side effects.
 */
public interface DurableToolExecutionLedger {

    DurableToolExecutionClaim claim(DurableToolExecutionRequest request);

    void complete(DurableToolExecutionRequest request, String executionKey, String rawResult);

    void fail(DurableToolExecutionRequest request, String executionKey, String rawResult, String errorMessage);

    List<DurableToolExecutionRecord> findRecoverable(int limit);

    boolean claimRetry(DurableToolExecutionRecord record);

    void markCompensationRequired(DurableToolExecutionRecord record, String reason);

    List<DurableToolExecutionRecord> findCompensationRequired(int limit);

    void markCompensated(DurableToolExecutionRecord record, String rawResult);
}
