package com.auraboot.framework.aurabot.service;

/**
 * Port for persisting AuraBot chat runs to ab_agent_run.
 * Implemented by enterprise-ai module when loaded.
 *
 * @since 6.5.0
 */
public interface ChatRunPersistencePort {

    /**
     * Create a new run record. Returns runPid (null if persistence fails).
     */
    String createRun(Long tenantId, String sessionId, String model, String userMessage);

    /**
     * Append a tool call record to the run.
     */
    void recordToolCall(String runPid, String toolName, Object input, Object output, boolean success);

    /**
     * Mark run as completed with token counts.
     */
    void completeRun(String runPid, boolean success, int inputTokens, int outputTokens,
                     double cost, String finalResponse, String errorMessage, String traceId);
}
