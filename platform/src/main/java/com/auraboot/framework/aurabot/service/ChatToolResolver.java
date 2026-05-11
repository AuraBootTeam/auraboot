package com.auraboot.framework.aurabot.service;

import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.port.GroundingPort;
import com.auraboot.framework.agent.port.ToolDiscoveryPort;
import com.auraboot.framework.agent.service.SkillPackActivator;
import com.auraboot.framework.application.tenant.MetaContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves available tools for AuraBot chat based on user intent and model context.
 *
 * <p>Uses D1 Grounding (via GroundingPort) to resolve intent + object from user message,
 * then ToolDiscoveryPort to discover precisely matching tools from ToolProviderRegistry.
 *
 * <p>Tool naming uses provider conventions (sanitized for LLM compatibility):
 * <ul>
 *   <li>Commands:     cmd_{commandCode}</li>
 *   <li>NamedQueries: nq_{queryCode}</li>
 *   <li>List:         list_{modelCode}</li>
 *   <li>Get:          get_{modelCode}</li>
 *   <li>Platform:     platform_{toolName}</li>
 * </ul>
 *
 * @author AuraBoot Team
 * @since 3.1.0
 */
@Slf4j
@Service
public class ChatToolResolver {

    private static final int MAX_TOOLS = 15;

    // SPI ports from the shared AI runtime
    private final GroundingPort groundingPort;
    private final ToolDiscoveryPort toolDiscoveryPort;
    private final SkillPackActivator skillPackActivator;
    private final Map<String, Boolean> discoveredToolReadOnlyByName = new ConcurrentHashMap<>();
    private final Map<String, String> discoveredProviderToolCodeByName = new ConcurrentHashMap<>();
    private final Map<String, AgentToolDefinition> discoveredAgentToolByName = new ConcurrentHashMap<>();

    @Autowired
    public ChatToolResolver(
            @Autowired(required = false) GroundingPort groundingPort,
            @Autowired(required = false) ToolDiscoveryPort toolDiscoveryPort,
            @Autowired(required = false) SkillPackActivator skillPackActivator
    ) {
        this.groundingPort = groundingPort;
        this.toolDiscoveryPort = toolDiscoveryPort;
        this.skillPackActivator = skillPackActivator;
    }

    /**
     * Result wrapper including grounding metadata for prompt construction.
     *
     * @param tools      LLM tool definitions
     * @param intent     resolved intent (nullable)
     * @param object     resolved model code (nullable)
     * @param isReadOnly true if the resolved intent is read-only
     */
    public record ResolvedTools(
            List<LlmChatRequest.Tool> tools,
            String intent,
            String object,
            boolean isReadOnly
    ) {}

    /**
     * Resolve tools for a given user message and model context.
     *
     * <p>Uses GroundingPort to resolve intent/object, then ToolDiscoveryPort to discover
     * matching tools. Returns empty tools if ports are unavailable or message is blank.
     *
     * @param userMessage the raw user message text
     * @param modelCode   the current page model code (e.g., "crm_lead")
     * @param recordPid   optional current record PID
     * @return resolved tools with optional grounding metadata
     */
    public ResolvedTools resolveTools(String userMessage, String modelCode, String recordPid) {
        if (groundingPort == null || toolDiscoveryPort == null) {
            log.error("AuraBot D1: GroundingPort or ToolDiscoveryPort not available — no tools will be provided");
            return new ResolvedTools(List.of(), null, null, true);
        }

        if (userMessage == null || userMessage.isBlank()) {
            log.warn("AuraBot D1: empty user message — no tools will be provided");
            return new ResolvedTools(List.of(), null, null, true);
        }

        try {
            Long tenantId = MetaContext.getCurrentTenantId();
            var grounding = groundingPort.ground(tenantId, userMessage, modelCode, recordPid);

            log.info("AuraBot D1: intent={}, object={}, confidence={}, skills={}",
                    grounding.intent(), grounding.object(),
                    String.format("%.2f", grounding.confidence()), grounding.candidateSkills());

            // ACP §3.3 Tier-1 — apply SkillPack Activation Filter to narrow the
            // candidate skill set the planner will see. Tenants with no pack
            // bindings pass through unchanged (progressive rollout).
            List<String> candidates = grounding.candidateSkills();
            if (skillPackActivator != null && candidates != null && !candidates.isEmpty()) {
                SkillPackActivator.ActivationResult activation =
                        skillPackActivator.filter(tenantId, null, null, null, candidates);
                if ("filter_applied".equals(activation.getReason()) && activation.getRemovedCount() > 0) {
                    log.info("SkillPack filter: {} → {} skills (dropped {})",
                            candidates.size(),
                            activation.getActivatedCandidates().size(),
                            activation.getRemovedCount());
                }
                candidates = activation.getActivatedCandidates();
            }

            var toolDefs = toolDiscoveryPort.discoverTools(
                    tenantId, candidates,
                    grounding.object(), grounding.intent(), MAX_TOOLS);

            List<LlmChatRequest.Tool> llmTools = new ArrayList<>(toolDefs.stream()
                    .map(this::convertToolDef)
                    .toList());

            removeSqlFallbackWhenDomainReadToolAvailable(llmTools);

            // Ensure safe platform tools are available (grounding may filter them out)
            ensurePlatformTools(llmTools);

            log.info("AuraBot D1: resolved {} tools via ToolDiscoveryPort", llmTools.size());
            return new ResolvedTools(llmTools, grounding.intent(), grounding.object(), grounding.readOnly());
        } catch (Exception e) {
            log.error("AuraBot D1 grounding failed: {}", e.getMessage(), e);
            return new ResolvedTools(List.of(), null, null, true);
        }
    }

    /**
     * Determine if a tool is read-only (no data mutation).
     *
     * @param toolName the tool name
     * @return true if the tool is read-only
     */
    public boolean isReadOnly(String toolName) {
        if (toolName == null) return true;
        Boolean discoveredReadOnly = discoveredToolReadOnlyByName.get(toolName);
        if (discoveredReadOnly != null) return discoveredReadOnly;
        // Provider naming (from ToolDiscoveryPort, sanitized)
        if (toolName.startsWith("nq_") || toolName.startsWith("list_") || toolName.startsWith("get_")) return true;
        // Platform tools: all read-only EXCEPT create_model
        if (toolName.startsWith("platform_")) {
            return !toolName.equals("platform_create_model");
        }
        return false;
    }

    /**
     * Return the exact provider tool code for a sanitized LLM tool name.
     */
    public String getProviderToolCode(String toolName) {
        if (toolName == null) return null;
        return discoveredProviderToolCodeByName.get(toolName);
    }

    /**
     * Return the canonical tool definition for a sanitized LLM tool name.
     * Execution callers pass this directly to ToolLoopService.
     */
    public AgentToolDefinition getAgentToolDefinition(String toolName) {
        if (toolName == null) return null;
        return discoveredAgentToolByName.get(toolName);
    }

    // ==================== Platform Tool Injection ====================

    /** Platform form extraction should always be available regardless of grounding result. */
    private static final LlmChatRequest.Tool PLATFORM_FILL_FORM_TOOL =
            LlmChatRequest.Tool.builder()
                    .name("platform_fill_form")
                    .description("Extract structured data from text (chat transcript, email, notes) "
                            + "and fill the current page's form fields. Use when user asks to populate "
                            + "a form from unstructured text. The 'fields' parameter must use the "
                            + "model's field codes as keys.")
                    .inputSchema(Map.of("type", "object",
                            "properties", Map.of(
                                    "fields", Map.of("type", "object",
                                            "description", "Map of fieldCode → value extracted from text"),
                                    "source", Map.of("type", "string",
                                            "description", "Brief description of source text"),
                                    "confidence", Map.of("type", "number",
                                            "description", "Overall confidence 0.0-1.0")),
                            "required", List.of("fields")))
                    .build();

    /** SQL remains a fallback only when no domain read tool is available. */
    private static final LlmChatRequest.Tool PLATFORM_EXECUTE_SQL_TOOL =
            LlmChatRequest.Tool.builder()
                    .name("platform_execute_sql")
                    .description("Execute a read-only SQL SELECT query with safety validation and tenant isolation. "
                            + "Use the model schema from the system prompt to write SQL directly.")
                    .inputSchema(Map.of("type", "object",
                            "properties", Map.of(
                                    "sql", Map.of("type", "string",
                                            "description", "PostgreSQL SELECT query. Include tenant_id = #{params.tenantId}."),
                                    "chartType", Map.of("type", "string",
                                            "enum", List.of("table", "bar", "pie", "line")),
                                    "interpretation", Map.of("type", "string",
                                            "description", "Brief interpretation")),
                            "required", List.of("sql")))
                    .build();

    /**
     * Ensure platform tools are present in the tool list.
     * SQL is not exposed when a model-specific read tool exists, so CRM/list/NQ
     * tools remain the primary path and SQL cannot bypass their safer contracts.
     */
    private void ensurePlatformTools(List<LlmChatRequest.Tool> tools) {
        Set<String> existing = tools.stream()
                .map(LlmChatRequest.Tool::getName)
                .collect(java.util.stream.Collectors.toSet());

        if (!existing.contains(PLATFORM_FILL_FORM_TOOL.getName())) {
            tools.add(PLATFORM_FILL_FORM_TOOL);
        }
        if (!hasDomainReadTool(tools) && !existing.contains(PLATFORM_EXECUTE_SQL_TOOL.getName())) {
            tools.add(PLATFORM_EXECUTE_SQL_TOOL);
        }
        cacheSyntheticPlatformTool(PLATFORM_FILL_FORM_TOOL, "platform.fill_form", true, "L1");
        cacheSyntheticPlatformTool(PLATFORM_EXECUTE_SQL_TOOL, "platform.execute_sql", true, "L1");
    }

    private void removeSqlFallbackWhenDomainReadToolAvailable(List<LlmChatRequest.Tool> tools) {
        if (!hasDomainReadTool(tools)) {
            return;
        }
        tools.removeIf(tool -> "platform_execute_sql".equals(tool.getName()));
    }

    private boolean hasDomainReadTool(List<LlmChatRequest.Tool> tools) {
        return tools.stream()
                .map(LlmChatRequest.Tool::getName)
                .anyMatch(name -> name.startsWith("nq_")
                        || name.startsWith("list_")
                        || name.startsWith("get_")
                        || (name.startsWith("cmd_") && isReadOnly(name)));
    }

    // ==================== Tool Conversion ====================

    /**
     * Convert a ToolDef from ToolDiscoveryPort into an LLM Tool definition.
     */
    private LlmChatRequest.Tool convertToolDef(ToolDiscoveryPort.ToolDef toolDef) {
        // Use code as tool name — LLM returns this in tool_use calls.
        // Sanitize: replace colons/dots with underscores for LLM function-name compatibility.
        String llmName = toolDef.code().replace(':', '_').replace('.', '_');
        discoveredToolReadOnlyByName.put(llmName, toolDef.readOnly());
        discoveredProviderToolCodeByName.put(llmName, toolDef.code());
        discoveredAgentToolByName.put(llmName, toAgentToolDefinition(toolDef));
        String desc = toolDef.description();
        if (toolDef.name() != null && !toolDef.name().isBlank() && !toolDef.name().equals(toolDef.code())) {
            desc = desc + " (" + toolDef.name() + ")";
        }
        return LlmChatRequest.Tool.builder()
                .name(llmName)
                .description(desc)
                .inputSchema(toolDef.inputSchema())
                .build();
    }

    private void cacheSyntheticPlatformTool(LlmChatRequest.Tool tool,
                                            String providerToolCode,
                                            boolean readOnly,
                                            String riskLevel) {
        discoveredToolReadOnlyByName.put(tool.getName(), readOnly);
        discoveredProviderToolCodeByName.put(tool.getName(), providerToolCode);
        discoveredAgentToolByName.put(tool.getName(), AgentToolDefinition.builder()
                .name(providerToolCode)
                .description(tool.getDescription())
                .inputSchema(tool.getInputSchema())
                .toolType("platform")
                .sourceCode(providerToolCode)
                .riskLevel(riskLevel)
                .confirmationPolicy("none")
                .requiresApproval(false)
                .requiresConfirmation(false)
                .build());
    }

    private AgentToolDefinition toAgentToolDefinition(ToolDiscoveryPort.ToolDef toolDef) {
        String toolCode = toolDef.code();
        String toolType = canonicalToolType(toolDef);
        String sourceCode = canonicalSourceCode(toolDef, toolType);
        return AgentToolDefinition.builder()
                .name(toolCode)
                .description(toolDef.description())
                .inputSchema(toolDef.inputSchema())
                .toolType(toolType)
                .sourceCode(sourceCode)
                .requiresApproval(toolDef.requiresApproval())
                .requiresConfirmation(toolDef.requiresConfirmation())
                .riskLevel(toolDef.riskLevel())
                .confirmationPolicy(toolDef.confirmationPolicy())
                .build();
    }

    private String canonicalToolType(ToolDiscoveryPort.ToolDef toolDef) {
        String code = toolDef.code();
        if (code != null && code.startsWith("aurabot:")) {
            return "AURABOT_SKILL";
        }
        if (code != null && (code.startsWith("cmd:")
                || code.startsWith("nq:")
                || code.startsWith("list:")
                || code.startsWith("get:"))) {
            return "built_in";
        }
        if (toolDef.toolType() != null && !toolDef.toolType().isBlank()) {
            return toolDef.toolType();
        }
        return inferProviderToolType(code);
    }

    private String canonicalSourceCode(ToolDiscoveryPort.ToolDef toolDef, String toolType) {
        String code = toolDef.code();
        if ("AURABOT_SKILL".equals(toolType) && code != null && code.startsWith("aurabot:")) {
            return code.substring("aurabot:".length());
        }
        if ("built_in".equals(toolType)) {
            return code;
        }
        if (toolDef.sourceCode() != null && !toolDef.sourceCode().isBlank()) {
            return toolDef.sourceCode();
        }
        return code;
    }

    private String inferProviderToolType(String toolCode) {
        if (toolCode == null) return "built_in";
        if (toolCode.startsWith("platform.")) return "platform";
        if (toolCode.startsWith("custom:")) return "custom";
        if (toolCode.startsWith("mcp:")) return "mcp";
        return "built_in";
    }
}
