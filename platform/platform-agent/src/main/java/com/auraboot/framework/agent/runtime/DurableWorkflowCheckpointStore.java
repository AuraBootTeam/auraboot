package com.auraboot.framework.agent.runtime;

import com.auraboot.framework.agent.dto.AgentPlanStep;

import java.util.List;

/**
 * Append-only checkpoint history for durable workflow plan execution.
 */
public interface DurableWorkflowCheckpointStore {

    void recordPlanCheckpoint(Long tenantId,
                              String runPid,
                              int currentStep,
                              List<AgentPlanStep> plan,
                              String reason);
}
