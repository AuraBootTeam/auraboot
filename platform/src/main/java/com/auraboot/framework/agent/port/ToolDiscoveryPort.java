package com.auraboot.framework.agent.port;

import java.util.List;
import java.util.Map;

/**
 * Port interface for tool discovery and execution via ToolProviderRegistry.
 * Defined in core module, implemented in enterprise-ai module.
 * <p>
 * Allows AuraBotChatService to discover and execute tools from the provider-aware
 * registry without a compile-time dependency on the enterprise-ai module.
 * <p>
 * When enterprise-ai module is loaded, the registry implementation registers as a Spring bean.
 * When not loaded, AuraBotChatService falls back to ChatToolExecutor.
 */
public interface ToolDiscoveryPort {

    /**
     * Discover available tools matching the given criteria.
     *
     * @param tenantId        current tenant ID
     * @param candidateSkills list of candidate skill codes from grounding (may be empty)
     * @param modelHint       target model code hint (nullable)
     * @param intentHint      resolved intent hint (nullable, e.g., "create", "query")
     * @param maxTools        maximum number of tools to return
     * @return list of matching tool definitions, ordered by relevance
     */
    List<ToolDef> discoverTools(Long tenantId, List<String> candidateSkills, String modelHint, String intentHint, int maxTools);

    /**
     * Execute a tool by code with the given parameters.
     *
     * @param tenantId current tenant ID
     * @param toolCode tool code (e.g., "cmd:crm_lead_create", "platform.list_models")
     * @param params   tool input parameters
     * @return execution result as map (success, data, message/error)
     */
    Map<String, Object> executeTool(Long tenantId, String toolCode, Map<String, Object> params);

    /**
     * Definition of a discoverable tool.
     *
     * @param code        tool code including provider prefix (e.g., "cmd:crm_lead_create")
     * @param name        human-readable tool name
     * @param description tool description for LLM function-calling
     * @param inputSchema JSON Schema describing the tool's input parameters
     * @param readOnly    true if the tool only reads data (no side effects)
     */
    record ToolDef(
            String code,
            String name,
            String description,
            Map<String, Object> inputSchema,
            boolean readOnly
    ) {}
}
