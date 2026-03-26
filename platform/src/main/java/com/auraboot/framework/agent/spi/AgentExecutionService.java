package com.auraboot.framework.agent.spi;

import java.util.Map;

/**
 * SPI for agent task execution. Community edition provides a no-op default.
 * Enterprise edition overrides with full 20-round tool-loop orchestration.
 */
public interface AgentExecutionService {

    AgentExecutionResult execute(String agentPid, String taskPid, Map<String, Object> input);

    AgentExecutionResult resume(String runPid);

    default boolean isAvailable() {
        return false;
    }

    record AgentExecutionResult(
        boolean success,
        String runPid,
        String status,
        String message
    ) {
        public static AgentExecutionResult unavailable(String message) {
            return new AgentExecutionResult(false, null, "unavailable", message);
        }

        public static AgentExecutionResult started(String runPid) {
            return new AgentExecutionResult(true, runPid, "started", null);
        }
    }
}
