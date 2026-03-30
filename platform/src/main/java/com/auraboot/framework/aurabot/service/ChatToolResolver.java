package com.auraboot.framework.aurabot.service;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.port.GroundingPort;
import com.auraboot.framework.agent.port.ToolDiscoveryPort;
import com.auraboot.framework.application.tenant.MetaContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

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

    // SPI ports — only available when enterprise-ai module is loaded
    private final GroundingPort groundingPort;
    private final ToolDiscoveryPort toolDiscoveryPort;

    @Autowired
    public ChatToolResolver(
            @Autowired(required = false) GroundingPort groundingPort,
            @Autowired(required = false) ToolDiscoveryPort toolDiscoveryPort
    ) {
        this.groundingPort = groundingPort;
        this.toolDiscoveryPort = toolDiscoveryPort;
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

            var toolDefs = toolDiscoveryPort.discoverTools(
                    tenantId, grounding.candidateSkills(),
                    grounding.object(), grounding.intent(), MAX_TOOLS);

            List<LlmChatRequest.Tool> llmTools = new ArrayList<>(toolDefs.stream()
                    .map(this::convertToolDef)
                    .toList());

            // Ensure platform tools are always available (grounding may filter them out)
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
        // Provider naming (from ToolDiscoveryPort, sanitized)
        if (toolName.startsWith("nq_") || toolName.startsWith("list_") || toolName.startsWith("get_")) return true;
        // Platform tools: all read-only EXCEPT create_model
        if (toolName.startsWith("platform_")) {
            return !toolName.equals("platform_create_model");
        }
        return false;
    }

    // ==================== Platform Tool Injection ====================

    /** Platform tools that should always be available regardless of grounding result */
    private static final List<LlmChatRequest.Tool> ALWAYS_AVAILABLE_PLATFORM_TOOLS = List.of(
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
                    .build(),
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
                    .build()
    );

    /**
     * Ensure platform tools are present in the tool list.
     * Grounding may filter them out based on intent, but these should always be available.
     */
    private void ensurePlatformTools(List<LlmChatRequest.Tool> tools) {
        Set<String> existing = tools.stream()
                .map(LlmChatRequest.Tool::getName)
                .collect(java.util.stream.Collectors.toSet());

        for (LlmChatRequest.Tool pt : ALWAYS_AVAILABLE_PLATFORM_TOOLS) {
            if (!existing.contains(pt.getName())) {
                tools.add(pt);
            }
        }
    }

    // ==================== Tool Conversion ====================

    /**
     * Convert a ToolDef from ToolDiscoveryPort into an LLM Tool definition.
     */
    private LlmChatRequest.Tool convertToolDef(ToolDiscoveryPort.ToolDef toolDef) {
        // Use code as tool name — LLM returns this in tool_use calls.
        // Sanitize: replace colons/dots with underscores for LLM function-name compatibility.
        String llmName = toolDef.code().replace(':', '_').replace('.', '_');
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
}
