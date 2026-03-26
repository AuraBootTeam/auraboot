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
 * <p>Converts AuraBot tool naming conventions (cmd__, nq__, builtin__, and sanitized provider names)
 * to ToolProvider codes, then delegates execution to ToolProviderRegistry through ToolDiscoveryPort.
 *
 * <p>Tool name conventions (from {@link ChatToolResolver}):
 * <ul>
 *   <li>{@code cmd__{modelCode}__{commandCode}} → {@code cmd:{commandCode}}</li>
 *   <li>{@code nq__{queryCode}} → {@code nq:{queryCode}}</li>
 *   <li>{@code builtin__execute_query} → {@code platform.execute_sql}</li>
 *   <li>{@code builtin__list_models} → {@code platform.list_models}</li>
 *   <li>{@code builtin__get_record} → {@code get:{modelCode}}</li>
 * </ul>
 *
 * @author AuraBoot Team
 * @since 3.1.0
 */
@Slf4j
@Service
public class ChatToolExecutor {

    private static final String PREFIX_CMD = "cmd__";
    private static final String PREFIX_NQ = "nq__";
    private static final String BUILTIN_GET_RECORD = "builtin__get_record";
    private static final String BUILTIN_EXECUTE_QUERY = "builtin__execute_query";
    private static final String BUILTIN_LIST_MODELS = "builtin__list_models";
    private static final String BUILTIN_MODEL_SUGGEST = "builtin__model_suggest";

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
     * @param toolName  the tool name (e.g., "cmd__crm_lead__advance_stage")
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
     * Convert AuraBot tool naming convention to ToolProvider code convention.
     * <p>
     * AuraBot naming: {@code cmd__{modelCode}__{commandCode}}, {@code nq__{queryCode}}, {@code builtin__*}
     * Provider naming: {@code cmd:{commandCode}}, {@code nq:{queryCode}}, {@code platform.*}, {@code get:{modelCode}}
     */
    private String toProviderToolCode(String toolName, String modelCode) {
        if (toolName.startsWith(PREFIX_CMD)) {
            // cmd__crm_lead__crm_update_lead → extract commandCode after last __
            String remainder = toolName.substring(PREFIX_CMD.length());
            int separatorIdx = remainder.indexOf("__");
            if (separatorIdx >= 0) {
                String commandCode = remainder.substring(separatorIdx + 2);
                return "cmd:" + commandCode;
            }
            return "cmd:" + remainder;
        }
        if (toolName.startsWith(PREFIX_NQ)) {
            return "nq:" + toolName.substring(PREFIX_NQ.length());
        }
        if (BUILTIN_EXECUTE_QUERY.equals(toolName)) {
            return "platform.execute_sql";
        }
        if (BUILTIN_LIST_MODELS.equals(toolName)) {
            return "platform.list_models";
        }
        if (BUILTIN_MODEL_SUGGEST.equals(toolName)) {
            return "platform.model_suggest";
        }
        if (BUILTIN_GET_RECORD.equals(toolName)) {
            return "get:" + (modelCode != null ? modelCode : "unknown");
        }
        // Provider-style sanitized names (from ToolDiscoveryPort: code.replace(':','_').replace('.','_'))
        // e.g., platform_execute_sql → platform.execute_sql, nq_crm_lead_stats → nq:crm_lead_stats
        if (toolName.startsWith("platform_")) return "platform." + toolName.substring("platform_".length());
        if (toolName.startsWith("nq_"))       return "nq:" + toolName.substring("nq_".length());
        if (toolName.startsWith("cmd_"))      return "cmd:" + toolName.substring("cmd_".length());
        if (toolName.startsWith("list_"))     return "list:" + toolName.substring("list_".length());
        if (toolName.startsWith("get_"))      return "get:" + toolName.substring("get_".length());

        // Pass-through for unknown tools
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
