package com.auraboot.framework.aurabot.service;

import com.auraboot.framework.agent.port.ToolDiscoveryPort;
import com.auraboot.framework.application.tenant.MetaContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Routes LLM tool calls to the appropriate backend service via ToolDiscoveryPort.
 *
 * <p>Converts sanitized LLM tool names back to ToolProvider codes,
 * then delegates execution to ToolProviderRegistry through ToolDiscoveryPort.
 *
 * <p>Tool name de-sanitization (from {@link ChatToolResolver}):
 * <ul>
 *   <li>{@code platform_*} → {@code platform.*}</li>
 *   <li>{@code cmd_*} → {@code cmd:*}</li>
 *   <li>{@code nq_*} → {@code nq:*}</li>
 *   <li>{@code list_*} → {@code list:*}</li>
 *   <li>{@code get_*} → {@code get:*}</li>
 * </ul>
 *
 * @author AuraBoot Team
 * @since 3.1.0
 */
@Slf4j
@Service
public class ChatToolExecutor {

    private final ToolDiscoveryPort toolDiscoveryPort;

    public ChatToolExecutor(
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            ToolDiscoveryPort toolDiscoveryPort) {
        this.toolDiscoveryPort = toolDiscoveryPort;
    }

    /**
     * Execute a tool call and return the result as a map.
     * <p>
     * Routes through ToolProviderRegistry via ToolDiscoveryPort (enterprise-ai module).
     *
     * @param toolName  the tool name (e.g., "cmd_crm_update_lead")
     * @param input     the tool input parameters from the LLM
     * @param modelCode the current model context code
     * @return result map with either "success" data or "error" details
     */
    public Map<String, Object> execute(String toolName, Map<String, Object> input, String modelCode) {
        if (toolName == null || toolName.isBlank()) {
            return errorResult("Tool name is required");
        }
        if (input == null) {
            input = Map.of();
        }

        if (toolDiscoveryPort == null) {
            return errorResult("ToolDiscoveryPort is not available. Ensure enterprise-ai module is loaded.");
        }

        try {
            Long tenantId = MetaContext.getCurrentTenantId();
            String providerToolCode = toProviderToolCode(toolName, modelCode);
            log.debug("Routing tool {} -> provider code {}", toolName, providerToolCode);
            return toolDiscoveryPort.executeTool(tenantId, providerToolCode, input);
        } catch (Exception e) {
            log.error("Tool execution failed for {}: {}", toolName, e.getMessage(), e);
            return errorResult(e.getMessage());
        }
    }

    /**
     * De-sanitize LLM tool names back to ToolProvider code convention.
     * <p>
     * LLM tool names use underscores (LLM function-name compatible),
     * provider codes use colons/dots as namespace separators.
     */
    private String toProviderToolCode(String toolName, String modelCode) {
        if (toolName == null) return toolName;

        // Provider naming: platform_* → platform.*
        if (toolName.startsWith("platform_")) {
            return "platform." + toolName.substring("platform_".length());
        }
        // Provider naming: cmd_* → cmd:*
        if (toolName.startsWith("cmd_")) {
            return "cmd:" + toolName.substring(4);
        }
        // Provider naming: nq_* → nq:*
        if (toolName.startsWith("nq_")) {
            return "nq:" + toolName.substring(3);
        }
        // Provider naming: list_* → list:*
        if (toolName.startsWith("list_")) {
            return "list:" + toolName.substring(5);
        }
        // Provider naming: get_* → get:*
        if (toolName.startsWith("get_")) {
            return "get:" + toolName.substring(4);
        }
        // Pass-through for unknown patterns
        return toolName;
    }

    // ==================== Helpers ====================

    private static Map<String, Object> errorResult(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("error", message != null ? message : "Unknown error");
        return result;
    }
}
