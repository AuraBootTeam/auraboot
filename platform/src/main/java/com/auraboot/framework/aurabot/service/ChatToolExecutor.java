package com.auraboot.framework.aurabot.service;

import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.service.ToolLoopService;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.aurabot.skill.provider.AuraBotSkillToolProvider;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin chat adapter for executing LLM tool calls through the canonical ACP
 * ToolLoopService runtime.
 */
@Slf4j
@Service
public class ChatToolExecutor {

    private static final String DEFAULT_RUN_PID = "aurabot_chat";
    private static final String DEFAULT_AGENT_CODE = "aurabot";
    private static final String AURABOT_TOOL_PREFIX = AuraBotSkillToolProvider.PROVIDER_CODE + ":";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ToolLoopService toolLoopService;
    private final ChatToolResolver chatToolResolver;
    private final ObjectMapper objectMapper;

    public ChatToolExecutor(
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            ToolLoopService toolLoopService,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            ChatToolResolver chatToolResolver,
            ObjectMapper objectMapper) {
        this.toolLoopService = toolLoopService;
        this.chatToolResolver = chatToolResolver;
        this.objectMapper = objectMapper;
    }

    /**
     * Execute a tool call and return the parsed ToolLoopService result.
     */
    public Map<String, Object> execute(String toolName, Map<String, Object> input, String modelCode) {
        return execute(toolName, input, modelCode, DEFAULT_RUN_PID, null, DEFAULT_AGENT_CODE);
    }

    /**
     * Execute a tool call inside a known run/task context.
     */
    public Map<String, Object> execute(String toolName,
                                       Map<String, Object> input,
                                       String modelCode,
                                       String runPid,
                                       String taskPid,
                                       String agentCode) {
        return executeInternal(toolName, input, modelCode, runPid, taskPid, agentCode, false);
    }

    /**
     * Execute a tool after the chat-level user confirmation has been satisfied.
     */
    public Map<String, Object> executeConfirmed(String toolName,
                                                Map<String, Object> input,
                                                String modelCode,
                                                String runPid,
                                                String taskPid,
                                                String agentCode) {
        return executeInternal(toolName, input, modelCode, runPid, taskPid, agentCode, true);
    }

    private Map<String, Object> executeInternal(String toolName,
                                                Map<String, Object> input,
                                                String modelCode,
                                                String runPid,
                                                String taskPid,
                                                String agentCode,
                                                boolean confirmationSatisfied) {
        if (toolName == null || toolName.isBlank()) {
            return errorResult("Tool name is required");
        }
        if (toolLoopService == null) {
            return errorResult("ToolLoopService is not available in the current runtime.");
        }

        Map<String, Object> safeInput = input != null ? input : Map.of();
        AgentToolDefinition toolDef = resolveToolDefinition(toolName, modelCode);
        if (confirmationSatisfied && toolDef.isRequiresConfirmation()) {
            toolDef = withConfirmationSatisfied(toolDef);
        }
        String canonicalToolName = toolDef.getName();

        try {
            Long tenantId = MetaContext.getCurrentTenantId();
            String raw = toolLoopService.executeToolCall(
                    tenantId,
                    nonBlank(runPid, DEFAULT_RUN_PID),
                    taskPid,
                    nonBlank(agentCode, DEFAULT_AGENT_CODE),
                    canonicalToolName,
                    safeInput,
                    List.of(toolDef),
                    null);
            return parseToolLoopResult(raw);
        } catch (Exception e) {
            log.error("ToolLoopService execution failed for {}: {}", toolName, e.getMessage(), e);
            return errorResult(e.getMessage());
        }
    }

    /**
     * Confirm a pending AuraBot skill preview through ToolLoopService.
     */
    public Map<String, Object> confirmAuraBotSkill(String toolName,
                                                   Map<String, Object> input,
                                                   String modelCode,
                                                   String previewToken,
                                                   String runPid,
                                                   String taskPid,
                                                   String agentCode) {
        if (toolName == null || toolName.isBlank()) {
            return errorResult("Tool name is required");
        }
        if (previewToken == null || previewToken.isBlank()) {
            return errorResult("Preview token is required");
        }
        if (toolLoopService == null) {
            return errorResult("ToolLoopService is not available in the current runtime.");
        }

        Map<String, Object> safeInput = input != null ? input : Map.of();
        AgentToolDefinition toolDef = resolveAuraBotSkillConfirmDefinition(toolName, modelCode);
        try {
            Long tenantId = MetaContext.getCurrentTenantId();
            String raw = toolLoopService.confirmAuraBotSkill(
                    tenantId,
                    nonBlank(runPid, DEFAULT_RUN_PID),
                    taskPid,
                    nonBlank(agentCode, DEFAULT_AGENT_CODE),
                    toolDef.getName(),
                    safeInput,
                    List.of(toolDef),
                    previewToken,
                    null);
            return parseToolLoopResult(raw);
        } catch (Exception e) {
            log.error("ToolLoopService skill confirm failed for {}: {}", toolName, e.getMessage(), e);
            return errorResult(e.getMessage());
        }
    }

    private AgentToolDefinition resolveToolDefinition(String toolName, String modelCode) {
        AgentToolDefinition discovered = chatToolResolver != null
                ? chatToolResolver.getAgentToolDefinition(toolName)
                : null;
        if (discovered != null) {
            return discovered;
        }

        String providerToolCode = chatToolResolver != null
                ? chatToolResolver.getProviderToolCode(toolName)
                : null;
        if (providerToolCode == null || providerToolCode.isBlank()) {
            providerToolCode = toProviderToolCode(toolName, modelCode);
        }

        String toolType = inferToolType(providerToolCode);
        return AgentToolDefinition.builder()
                .name(providerToolCode)
                .description(providerToolCode)
                .inputSchema(Map.of("type", "object"))
                .toolType(toolType)
                .sourceCode(sourceCodeFor(providerToolCode, toolType))
                .requiresApproval(false)
                .requiresConfirmation(false)
                .riskLevel(defaultRiskLevel(providerToolCode, toolType))
                .confirmationPolicy("none")
                .build();
    }

    private AgentToolDefinition resolveAuraBotSkillConfirmDefinition(String toolName, String modelCode) {
        AgentToolDefinition toolDef = resolveToolDefinition(toolName, modelCode);
        if ("AURABOT_SKILL".equals(toolDef.getToolType())) {
            return toolDef;
        }

        String skillName = toolName;
        if (skillName.startsWith("aurabot_")) {
            skillName = restoreSkillCode(skillName.substring("aurabot_".length()));
        }
        String canonicalName = AURABOT_TOOL_PREFIX + skillName;
        return AgentToolDefinition.builder()
                .name(canonicalName)
                .description(toolDef.getDescription() != null ? toolDef.getDescription() : canonicalName)
                .inputSchema(toolDef.getInputSchema() != null ? toolDef.getInputSchema() : Map.of("type", "object"))
                .toolType("AURABOT_SKILL")
                .sourceCode(skillName)
                .requiresApproval(toolDef.isRequiresApproval())
                .requiresConfirmation(false)
                .riskLevel(toolDef.getRiskLevel())
                .confirmationPolicy(toolDef.getConfirmationPolicy())
                .nativeToolConfig(toolDef.getNativeToolConfig())
                .build();
    }

    private AgentToolDefinition withConfirmationSatisfied(AgentToolDefinition original) {
        return AgentToolDefinition.builder()
                .name(original.getName())
                .description(original.getDescription())
                .inputSchema(original.getInputSchema())
                .toolType(original.getToolType())
                .sourceCode(original.getSourceCode())
                .requiresApproval(original.isRequiresApproval())
                .requiresConfirmation(false)
                .riskLevel(original.getRiskLevel())
                .confirmationPolicy(original.getConfirmationPolicy())
                .nativeToolConfig(original.getNativeToolConfig())
                .build();
    }

    private String inferToolType(String providerToolCode) {
        if (providerToolCode != null && providerToolCode.startsWith(AURABOT_TOOL_PREFIX)) {
            return "AURABOT_SKILL";
        }
        if (providerToolCode != null && providerToolCode.startsWith("platform.")) {
            return "platform";
        }
        if (providerToolCode != null && providerToolCode.startsWith("custom:")) {
            return "custom";
        }
        if (providerToolCode != null && providerToolCode.startsWith("mcp:")) {
            return "mcp";
        }
        return "built_in";
    }

    private String sourceCodeFor(String providerToolCode, String toolType) {
        if ("AURABOT_SKILL".equals(toolType) && providerToolCode.startsWith(AURABOT_TOOL_PREFIX)) {
            return providerToolCode.substring(AURABOT_TOOL_PREFIX.length());
        }
        return providerToolCode;
    }

    private String defaultRiskLevel(String providerToolCode, String toolType) {
        if ("AURABOT_SKILL".equals(toolType) || isReadOnlyProviderTool(providerToolCode)) {
            return "L0";
        }
        return "L1";
    }

    private boolean isReadOnlyProviderTool(String providerToolCode) {
        if (providerToolCode == null) return false;
        return providerToolCode.startsWith("nq:")
                || providerToolCode.startsWith("list:")
                || providerToolCode.startsWith("get:")
                || "platform.execute_sql".equals(providerToolCode)
                || "platform.list_models".equals(providerToolCode);
    }

    /**
     * De-sanitize LLM tool names back to provider code convention.
     */
    private String toProviderToolCode(String toolName, String modelCode) {
        if (toolName == null) return toolName;
        if (toolName.startsWith(AURABOT_TOOL_PREFIX)) {
            return toolName;
        }
        if (toolName.startsWith("aurabot_")) {
            return AURABOT_TOOL_PREFIX + restoreSkillCode(toolName.substring("aurabot_".length()));
        }
        if (toolName.startsWith("platform_")) {
            return "platform." + toolName.substring("platform_".length());
        }
        if (toolName.startsWith("cmd_")) {
            return "cmd:" + restoreCommandCode(toolName.substring("cmd_".length()));
        }
        if (toolName.startsWith("nq_")) {
            return "nq:" + toolName.substring(3);
        }
        if (toolName.startsWith("list_")) {
            return "list:" + toolName.substring(5);
        }
        if (toolName.startsWith("get_")) {
            return "get:" + toolName.substring(4);
        }
        return toolName;
    }

    private String restoreCommandCode(String llmSafeCommandCode) {
        if (llmSafeCommandCode == null || llmSafeCommandCode.isBlank()) {
            return llmSafeCommandCode;
        }
        int namespaceEnd = llmSafeCommandCode.indexOf('_');
        if (namespaceEnd <= 0 || namespaceEnd >= llmSafeCommandCode.length() - 1) {
            return llmSafeCommandCode;
        }
        return llmSafeCommandCode.substring(0, namespaceEnd)
                + ":"
                + llmSafeCommandCode.substring(namespaceEnd + 1);
    }

    private String restoreSkillCode(String llmSafeSkillCode) {
        if (llmSafeSkillCode == null || llmSafeSkillCode.isBlank() || llmSafeSkillCode.contains(":")) {
            return llmSafeSkillCode;
        }
        int namespaceEnd = llmSafeSkillCode.indexOf('_');
        if (namespaceEnd <= 0 || namespaceEnd >= llmSafeSkillCode.length() - 1) {
            return llmSafeSkillCode;
        }
        return llmSafeSkillCode.substring(0, namespaceEnd)
                + ":"
                + llmSafeSkillCode.substring(namespaceEnd + 1);
    }

    private Map<String, Object> parseToolLoopResult(String raw) {
        if (raw == null || raw.isBlank()) {
            return errorResult("Empty tool result");
        }
        if (raw.startsWith("Error")) {
            return errorResult(raw);
        }
        try {
            return objectMapper.readValue(raw, MAP_TYPE);
        } catch (Exception e) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("data", raw);
            return result;
        }
    }

    private static String nonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private static Map<String, Object> errorResult(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("error", message != null ? message : "Unknown error");
        return result;
    }
}
