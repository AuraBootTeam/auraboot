package com.auraboot.framework.agent.port;

import java.util.Map;

/**
 * Port interface for tool execution — defined in core module, implemented in enterprise-ai module.
 * Allows AuraBotChatService to delegate tool execution to ToolLoopService without compile-time dependency.
 *
 * When enterprise-ai module is loaded, ToolLoopService implements this interface.
 * When not loaded, AuraBotChatService falls back to ChatToolExecutor.
 */
public interface ToolExecutionPort {

    /**
     * Execute a DSL command with Action recording.
     * @param tenantId    current tenant
     * @param runId       run identifier ("aurabot_chat" for chat context)
     * @param commandCode command code (e.g., "crm:create_lead")
     * @param input       command input parameters
     * @return execution result as map (success, data, message)
     */
    Map<String, Object> executeDslCommand(Long tenantId, String runId, String commandCode, Map<String, Object> input);

    /**
     * Execute a DSL query with Action recording.
     * @param tenantId   current tenant
     * @param runId      run identifier
     * @param queryCode  named query code
     * @param input      query parameters
     * @return query result as map (total, records)
     */
    Map<String, Object> executeDslQuery(Long tenantId, String runId, String queryCode, Map<String, Object> input);

    /**
     * Execute a tool via the provider-aware routing system.
     * Routes to the appropriate ToolProvider based on toolCode prefix.
     *
     * @param tenantId   tenant context
     * @param runId      agent run ID for tracing
     * @param toolCode   provider-prefixed tool code (e.g., "cmd:crm_account_create", "platform.list_models")
     * @param input      tool parameters
     * @return execution result
     */
    default Map<String, Object> executeTool(Long tenantId, String runId, String toolCode, Map<String, Object> input) {
        // Default: try to route via old methods for backward compat
        if (toolCode.startsWith("cmd:")) {
            return executeDslCommand(tenantId, runId, toolCode.substring(4), input);
        } else if (toolCode.startsWith("nq:")) {
            return executeDslQuery(tenantId, runId, toolCode.substring(3), input);
        }
        return Map.of("success", false, "error", "Tool not supported via legacy port: " + toolCode);
    }
}
