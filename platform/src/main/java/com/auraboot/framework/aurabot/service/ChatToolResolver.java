package com.auraboot.framework.aurabot.service;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.port.GroundingPort;
import com.auraboot.framework.agent.port.ToolDiscoveryPort;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.entity.NamedQuery;
import com.auraboot.framework.meta.mapper.CommandDefinitionMapper;
import com.auraboot.framework.meta.mapper.NamedQueryMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * Resolves available tools for AuraBot chat based on user intent and model context.
 *
 * <p>Two resolution paths:
 * <ul>
 *   <li><b>Path A (Intelligent)</b>: When enterprise-ai module is loaded, uses D1 Grounding
 *       to resolve intent + object from user message, then ToolDiscoveryPort to discover
 *       precisely matching tools from ToolProviderRegistry.</li>
 *   <li><b>Path B (Legacy)</b>: When enterprise-ai is not loaded, falls back to hardcoded
 *       builtin tools + DB-queried Commands/NamedQueries.</li>
 * </ul>
 *
 * Tool naming conventions:
 * - Commands:     cmd__{modelCode}__{commandCode}
 * - NamedQueries: nq__{queryCode}
 * - Built-in:     builtin__get_record
 *
 * @author AuraBoot Team
 * @since 3.1.0
 */
@Slf4j
@Service
public class ChatToolResolver {

    private static final int MAX_TOOLS = 15;
    private static final Set<String> EXCLUDED_COMMAND_TYPES = Set.of("create", "delete");
    private static final String TOOL_PREFIX_CMD = "cmd__";
    private static final String TOOL_PREFIX_NQ = "nq__";
    private static final String TOOL_PREFIX_BUILTIN = "builtin__";

    private final CommandDefinitionMapper commandDefinitionMapper;
    private final NamedQueryMapper namedQueryMapper;
    private final ObjectMapper objectMapper;

    // SPI ports — only available when enterprise-ai module is loaded
    private final GroundingPort groundingPort;
    private final ToolDiscoveryPort toolDiscoveryPort;

    @Autowired
    public ChatToolResolver(
            CommandDefinitionMapper commandDefinitionMapper,
            NamedQueryMapper namedQueryMapper,
            ObjectMapper objectMapper,
            @Autowired(required = false) GroundingPort groundingPort,
            @Autowired(required = false) ToolDiscoveryPort toolDiscoveryPort
    ) {
        this.commandDefinitionMapper = commandDefinitionMapper;
        this.namedQueryMapper = namedQueryMapper;
        this.objectMapper = objectMapper;
        this.groundingPort = groundingPort;
        this.toolDiscoveryPort = toolDiscoveryPort;
    }

    /**
     * Result wrapper including grounding metadata for prompt construction.
     *
     * @param tools      LLM tool definitions
     * @param intent     resolved intent (nullable in legacy mode)
     * @param object     resolved model code (nullable in legacy mode)
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
     * <p>Path A: When GroundingPort and ToolDiscoveryPort are available (enterprise-ai loaded),
     * performs D1 Grounding to resolve intent/object, then discovers matching tools.
     * <p>Path B: Legacy fallback with hardcoded builtins + DB-queried Commands/NQs.
     *
     * @param userMessage the raw user message text (nullable, triggers legacy path if null)
     * @param modelCode   the current page model code (e.g., "crm_lead")
     * @param recordPid   optional current record PID
     * @return resolved tools with optional grounding metadata
     */
    public ResolvedTools resolveTools(String userMessage, String modelCode, String recordPid) {
        // Path A: Intelligent routing (enterprise-ai loaded)
        if (groundingPort != null && toolDiscoveryPort != null && userMessage != null) {
            try {
                Long tenantId = MetaContext.getCurrentTenantId();
                var grounding = groundingPort.ground(tenantId, userMessage, modelCode, recordPid);

                log.info("AuraBot D1: intent={}, object={}, confidence={}, skills={}",
                        grounding.intent(), grounding.object(),
                        String.format("%.2f", grounding.confidence()), grounding.candidateSkills());

                var toolDefs = toolDiscoveryPort.discoverTools(
                        tenantId, grounding.candidateSkills(),
                        grounding.object(), grounding.intent(), MAX_TOOLS);

                List<LlmChatRequest.Tool> llmTools = toolDefs.stream()
                        .map(this::convertToolDef)
                        .toList();

                log.info("AuraBot D1: resolved {} tools via ToolDiscoveryPort", llmTools.size());
                return new ResolvedTools(llmTools, grounding.intent(), grounding.object(), grounding.readOnly());
            } catch (Exception e) {
                log.warn("AuraBot D1 grounding failed, falling back to legacy: {}", e.getMessage());
            }
        }

        // Path B: Legacy fallback
        return legacyResolveTools(modelCode, recordPid);
    }

    /**
     * Determine if a tool is read-only (no data mutation).
     * Read-only tools: nq__ (queries) and builtin__ (record fetch).
     * cmd__ tools require user confirmation before execution.
     *
     * @param toolName the tool name
     * @return true if the tool is read-only
     */
    public boolean isReadOnly(String toolName) {
        if (toolName == null) return true;
        // Legacy naming: nq__*, builtin__*
        if (toolName.startsWith(TOOL_PREFIX_NQ) || toolName.startsWith(TOOL_PREFIX_BUILTIN)) return true;
        // Provider naming (from ToolDiscoveryPort, sanitized): nq_*, list_*, get_*, platform_*
        if (toolName.startsWith("nq_") || toolName.startsWith("list_") || toolName.startsWith("get_")
                || toolName.startsWith("platform_")) return true;
        return false;
    }

    // ==================== Path A: Intelligent Tool Resolution ====================

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

    // ==================== Path B: Legacy Fallback ====================

    /**
     * Legacy tool resolution — hardcoded builtins + DB-queried Commands/NQs.
     * Used when enterprise-ai module is not loaded.
     */
    private ResolvedTools legacyResolveTools(String modelCode, String recordPid) {
        List<LlmChatRequest.Tool> tools = new ArrayList<>();

        // 1. Built-in: execute_query
        tools.add(buildLegacyTool("builtin__execute_query",
                "Execute a SQL SELECT query against the business database and return results with chart visualization. "
                        + "Use builtin__list_models first to discover available tables and columns. "
                        + "All queries MUST include 'WHERE tenant_id = #{params.tenantId}' for data isolation.",
                Map.of("type", "object",
                        "properties", Map.of(
                                "sql", Map.of("type", "string",
                                        "description", "A PostgreSQL SELECT query. MUST be SELECT-only. Use mt_{modelCode} table names. Include tenant_id = #{params.tenantId}."),
                                "chartType", Map.of("type", "string",
                                        "enum", List.of("table", "bar", "pie", "line"),
                                        "description", "Suggested chart type for the results."),
                                "interpretation", Map.of("type", "string",
                                        "description", "Brief interpretation of what this query answers.")),
                        "required", List.of("sql"))));

        // 2. Built-in: list_models
        tools.add(buildLegacyTool("builtin__list_models",
                "List available data models with their table names and fields. Use to discover schema before writing SQL.",
                Map.of("type", "object",
                        "properties", Map.of(
                                "keyword", Map.of("type", "string",
                                        "description", "Optional keyword to filter models."),
                                "includeFields", Map.of("type", "boolean",
                                        "description", "If true, include field details for each model.")))));

        // 3. Built-in: get_record (when viewing a specific record)
        if (recordPid != null && !recordPid.isBlank()) {
            tools.add(buildLegacyTool("builtin__get_record",
                    "Fetch the full details of a specific record by its PID.",
                    Map.of("type", "object",
                            "properties", Map.of(
                                    "recordPid", Map.of("type", "string",
                                            "description", "The PID of the record to fetch.")),
                            "required", List.of("recordPid"))));
        }

        // 5. Commands + NamedQueries for this model
        if (modelCode != null && !modelCode.isBlank()) {
            tools.addAll(legacyResolveCommandTools(modelCode));
            tools.addAll(legacyResolveNamedQueryTools(modelCode));
        }

        // Enforce max tools limit
        if (tools.size() > MAX_TOOLS) {
            log.info("Legacy: truncating tools from {} to {} for model {}", tools.size(), MAX_TOOLS, modelCode);
            tools = new ArrayList<>(tools.subList(0, MAX_TOOLS));
        }

        return new ResolvedTools(tools, null, null, false);
    }

    private LlmChatRequest.Tool buildLegacyTool(String name, String description, Map<String, Object> inputSchema) {
        return LlmChatRequest.Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(inputSchema)
                .build();
    }

    private List<LlmChatRequest.Tool> legacyResolveCommandTools(String modelCode) {
        List<CommandDefinition> commands = commandDefinitionMapper.selectList(
                new QueryWrapper<CommandDefinition>()
                        .eq("model_code", modelCode)
                        .eq("status", StatusConstants.PUBLISHED)
                        .eq("is_current", true)
                        .and(w -> w.eq("deleted_flag", false).or().isNull("deleted_flag"))
                        .orderByAsc("code")
        );

        List<LlmChatRequest.Tool> tools = new ArrayList<>();
        for (CommandDefinition cmd : commands) {
            String cmdType = extractCommandType(cmd);
            if (EXCLUDED_COMMAND_TYPES.contains(cmdType)) {
                continue;
            }
            String toolName = TOOL_PREFIX_CMD + cmd.getModelCode() + "__" + sanitizeCode(cmd.getCode());
            String description = buildCommandDescription(cmd, cmdType);
            Map<String, Object> inputSchema = buildCommandInputSchema(cmd);
            tools.add(LlmChatRequest.Tool.builder()
                    .name(toolName)
                    .description(description)
                    .inputSchema(inputSchema)
                    .build());
        }
        return tools;
    }

    private List<LlmChatRequest.Tool> legacyResolveNamedQueryTools(String modelCode) {
        String exactPrefix = modelCode + "_";
        List<NamedQuery> queries = namedQueryMapper.selectList(
                new QueryWrapper<NamedQuery>()
                        .eq("status", StatusConstants.PUBLISHED)
                        .likeRight("code", exactPrefix)
                        .orderByAsc("code")
        );

        return queries.stream()
                .map(nq -> {
                    String description = nq.getDescription() != null && !nq.getDescription().isBlank()
                            ? nq.getDescription()
                            : (nq.getTitle() != null ? nq.getTitle() : "Query: " + nq.getCode());
                    Map<String, Object> inputSchema = new LinkedHashMap<>();
                    inputSchema.put("type", "object");
                    Map<String, Object> properties = new LinkedHashMap<>();
                    properties.put("filters", Map.of("type", "array",
                            "description", "Filter conditions as [{fieldName, operator, value}]",
                            "items", Map.of("type", "object")));
                    properties.put("maxItems", Map.of("type", "integer",
                            "description", "Maximum number of records to return (default 50)"));
                    inputSchema.put("properties", properties);
                    return LlmChatRequest.Tool.builder()
                            .name(TOOL_PREFIX_NQ + sanitizeCode(nq.getCode()))
                            .description(description)
                            .inputSchema(inputSchema)
                            .build();
                })
                .collect(Collectors.toList());
    }

    // ==================== Shared Helpers ====================

    /**
     * Sanitize a command/query code for use as a tool name segment.
     * Replaces colons and other non-alphanumeric chars with underscores.
     */
    String sanitizeCode(String code) {
        if (code == null) return "unknown";
        return code.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private String extractCommandType(CommandDefinition cmd) {
        if (cmd.getExecutionConfig() == null || cmd.getExecutionConfig().isBlank()) {
            return "unknown";
        }
        try {
            Map<String, Object> config = objectMapper.readValue(
                    cmd.getExecutionConfig(), new TypeReference<>() {});
            Object type = config.get("type");
            return type != null ? type.toString().toUpperCase() : "unknown";
        } catch (Exception e) {
            log.warn("Failed to parse executionConfig for command {}: {}", cmd.getCode(), e.getMessage());
            return "unknown";
        }
    }

    private String buildCommandDescription(CommandDefinition cmd, String cmdType) {
        String agentHint = cmd.getAgentHint();
        if (agentHint != null && !agentHint.isBlank()) {
            return agentHint + " [" + cmdType + "]";
        }
        StringBuilder desc = new StringBuilder();
        if (cmd.getDescription() != null && !cmd.getDescription().isBlank()) {
            desc.append(cmd.getDescription());
        } else if (cmd.getDisplayName() != null && !cmd.getDisplayName().isBlank()) {
            desc.append(cmd.getDisplayName());
        } else {
            desc.append("Execute command ").append(cmd.getCode());
        }
        desc.append(" [").append(cmdType).append("]");
        return desc.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildCommandInputSchema(CommandDefinition cmd) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> recordPidProp = new LinkedHashMap<>();
        recordPidProp.put("type", "string");
        recordPidProp.put("description", "The PID of the target record");
        properties.put("recordPid", recordPidProp);
        List<String> required = new ArrayList<>();
        required.add("recordPid");

        if (cmd.getInputSchema() != null && !cmd.getInputSchema().isBlank()
                && !cmd.getInputSchema().equals("{}")) {
            try {
                Map<String, Object> cmdInputSchema = objectMapper.readValue(
                        cmd.getInputSchema(), new TypeReference<>() {});
                Object fields = cmdInputSchema.get("fields");
                if (fields instanceof List<?> fieldList) {
                    for (Object fieldObj : fieldList) {
                        if (fieldObj instanceof Map<?, ?> fieldMap) {
                            String fieldCode = (String) fieldMap.get("code");
                            if (fieldCode != null) {
                                Map<String, Object> prop = new LinkedHashMap<>();
                                prop.put("type", mapDataTypeToJsonSchema(
                                        (String) fieldMap.get("dataType")));
                                Object desc = fieldMap.get("description");
                                if (desc == null) desc = fieldMap.get("displayName");
                                if (desc != null) prop.put("description", desc.toString());
                                properties.put(fieldCode, prop);
                                if (Boolean.TRUE.equals(fieldMap.get("required"))) {
                                    required.add(fieldCode);
                                }
                            }
                        }
                    }
                }
                Object props = cmdInputSchema.get("properties");
                if (props instanceof Map<?, ?> propsMap) {
                    for (Map.Entry<?, ?> entry : propsMap.entrySet()) {
                        String key = entry.getKey().toString();
                        if (!"recordPid".equals(key) && entry.getValue() instanceof Map) {
                            properties.put(key, entry.getValue());
                        }
                    }
                    Object reqList = cmdInputSchema.get("required");
                    if (reqList instanceof List<?> reqs) {
                        for (Object r : reqs) {
                            String reqField = r.toString();
                            if (!required.contains(reqField)) {
                                required.add(reqField);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to parse inputSchema for command {}: {}", cmd.getCode(), e.getMessage());
            }
        }

        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    private String mapDataTypeToJsonSchema(String dataType) {
        if (dataType == null) return "string";
        return switch (dataType.toUpperCase()) {
            case "integer", "int", "long", "bigint" -> "integer";
            case "decimal", "numeric", "float", "double" -> "number";
            case "boolean", "bool" -> "boolean";
            default -> "string";
        };
    }
}
