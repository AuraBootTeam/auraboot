package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.provider.ToolDefinition;
import com.auraboot.framework.agent.provider.ToolDiscoveryContext;
import com.auraboot.framework.agent.provider.ToolProviderRegistry;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves an agent's <b>explicitly declared</b> tools (the {@code tools} column) into available
 * {@link ToolDefinition}s, independent of any task-derived model hint.
 *
 * <p>{@code DslToolProvider} discovers commands/queries scoped to a single {@code modelHint}. The
 * dispatch run path ({@code AgentRunService}) only knows the one model the task text grounds to, so
 * a declared command on any <i>other</i> model (e.g. {@code cmd:crm:create_activity} on
 * {@code crm_activity_common} while the task is about {@code crm_complaint}) is never discovered and
 * the plan validator rejects it as hallucinated. This resolver fixes that: it derives a model hint
 * per declared command/query tool, unions the discoveries, and returns only the declared codes — so
 * the caller can additively merge them into the run's tool set.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeclaredAgentToolResolver {

    private static final Pattern NAMED_QUERY_PARAM_PATTERN =
            Pattern.compile("#\\{params\\.([A-Za-z0-9_]+)}");
    private static final Set<String> NAMED_QUERY_SYSTEM_PARAMS =
            Set.of("tenantId", "currentUserId", "currentUserPid", "page", "pageSize", "offset", "limit");

    private final ToolProviderRegistry toolProviderRegistry;
    private final DynamicDataMapper dynamicDataMapper;
    private final ObjectMapper objectMapper;
    private final UserPermissionService userPermissionService;

    public List<ToolDefinition> resolveDeclaredTools(Long tenantId, Long userId, String agentCode,
                                                     List<String> declaredCodes) {
        if (declaredCodes == null || declaredCodes.isEmpty()) {
            return List.of();
        }
        Set<String> wanted = new LinkedHashSet<>(declaredCodes);

        // Derive a model hint per declared command/query tool, plus a null hint for non-model tools
        // (custom:/dsl./mcp:) that providers discover without a hint.
        Set<String> hints = new LinkedHashSet<>();
        for (String code : declaredCodes) {
            String hint = modelHintFor(tenantId, code);
            if (hint != null && !hint.isBlank()) {
                hints.add(hint);
            }
        }
        hints.add(null);

        Map<String, ToolDefinition> byCode = new LinkedHashMap<>();
        for (String hint : hints) {
            ToolDiscoveryContext ctx = ToolDiscoveryContext.builder()
                    .tenantId(tenantId)
                    .userId(userId)
                    .agentCode(agentCode)
                    .modelHint(hint)
                    .maxResults(100)
                    .build();
            List<ToolDefinition> discovered;
            try {
                discovered = toolProviderRegistry.discoverAll(ctx);
            } catch (Exception e) {
                log.warn("Declared-tool discovery failed for hint {}: {}", hint, e.getMessage());
                continue;
            }
            if (discovered == null) {
                continue;
            }
            for (ToolDefinition def : discovered) {
                if (def != null && def.getToolCode() != null && wanted.contains(def.getToolCode())) {
                    byCode.putIfAbsent(def.getToolCode(), def);
                }
            }
        }
        for (String code : wanted) {
            if (!byCode.containsKey(code)) {
                ToolDefinition direct = loadDirectDeclaredTool(tenantId, userId, code);
                if (direct != null) {
                    byCode.putIfAbsent(code, direct);
                } else {
                    log.warn("Declared agent tool was not discoverable: agent={}, tool={}", agentCode, code);
                }
            }
        }
        return new ArrayList<>(byCode.values());
    }

    private String modelHintFor(Long tenantId, String code) {
        if (code == null) {
            return null;
        }
        if (code.startsWith("cmd:")) {
            return loadCommandModelCode(tenantId, code.substring("cmd:".length()));
        }
        if (code.startsWith("get:")) {
            return code.substring("get:".length());
        }
        if (code.startsWith("list:")) {
            return code.substring("list:".length());
        }
        // nq: / custom: / dsl. / mcp: have no single reliable model — discovered via the null hint.
        return null;
    }

    private String loadCommandModelCode(Long tenantId, String commandCode) {
        try {
            String sql = "SELECT model_code FROM ab_command_definition "
                    + "WHERE tenant_id = #{params.tenantId} "
                    + "AND code = #{params.commandCode} "
                    + "AND (deleted_flag = FALSE OR deleted_flag IS NULL) "
                    + "AND (is_current = TRUE OR is_current IS NULL) LIMIT 1";
            List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                    Map.of("tenantId", tenantId, "commandCode", commandCode));
            return rows.isEmpty() ? null : (String) rows.get(0).get("model_code");
        } catch (Exception e) {
            log.warn("Failed to resolve command model for {}: {}", commandCode, e.getMessage());
            return null;
        }
    }

    private ToolDefinition loadDirectDeclaredTool(Long tenantId, Long userId, String toolCode) {
        if (toolCode == null || toolCode.isBlank()) {
            return null;
        }
        if (toolCode.startsWith("cmd:")) {
            return loadDirectCommandTool(tenantId, userId, toolCode.substring("cmd:".length()));
        }
        if (toolCode.startsWith("nq:")) {
            return loadDirectNamedQueryTool(tenantId, userId, toolCode.substring("nq:".length()));
        }
        if (toolCode.startsWith("custom:")) {
            return loadDirectCustomTool(tenantId, toolCode.substring("custom:".length()));
        }
        if (toolCode.startsWith("list:")) {
            String modelCode = toolCode.substring("list:".length());
            if (!canReadModel(userId, modelCode)) {
                return null;
            }
            return ToolDefinition.builder()
                    .toolCode(toolCode)
                    .toolName("List " + modelCode)
                    .description("Paginated list of " + modelCode + " records. Params: pageNum, pageSize, keyword.")
                    .providerCode("dsl")
                    .toolType("dsl_query")
                    .sourceCode(modelCode)
                    .riskLevel("L0")
                    .confirmationPolicy("none")
                    .parameterSchema(listParameterSchema())
                    .build();
        }
        if (toolCode.startsWith("get:")) {
            String modelCode = toolCode.substring("get:".length());
            if (!canReadModel(userId, modelCode)) {
                return null;
            }
            return ToolDefinition.builder()
                    .toolCode(toolCode)
                    .toolName("Get " + modelCode)
                    .description("Get a single " + modelCode + " record by pid. Params: recordPid (required); "
                            + "recordId is accepted for compatibility.")
                    .providerCode("dsl")
                    .toolType("dsl_query")
                    .sourceCode(modelCode)
                    .riskLevel("L0")
                    .confirmationPolicy("none")
                    .parameterSchema(getParameterSchema())
                    .build();
        }
        return loadProviderDeclaredTool(tenantId, userId, toolCode);
    }

    private ToolDefinition loadDirectCommandTool(Long tenantId, Long userId, String commandCode) {
        try {
            String sql = "SELECT code, display_name, description, agent_hint, input_schema, execution_config, "
                    + "cmd_risk_level, model_code "
                    + "FROM ab_command_definition "
                    + "WHERE tenant_id = #{params.tenantId} "
                    + "AND code = #{params.commandCode} "
                    + "AND (deleted_flag = FALSE OR deleted_flag IS NULL) "
                    + "AND (is_current = TRUE OR is_current IS NULL) LIMIT 1";
            List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                    Map.of("tenantId", tenantId, "commandCode", commandCode));
            if (rows == null || rows.isEmpty()) {
                return null;
            }
            Map<String, Object> row = rows.get(0);
            Map<String, Object> executionConfig = parseMap(row.get("execution_config"));
            Object rawPermissions = executionConfig.get("permissions");
            if (!hasAnyDeclaredPermission(userId, rawPermissions)) {
                return null;
            }
            boolean readQuery = isReadQueryCommand(commandCode, executionConfig, row.get("cmd_risk_level"));
            String riskLevel = readQuery ? "L0" : normalizeRiskLevel(row.get("cmd_risk_level"), "L1");
            String displayName = stringValue(row.get("display_name"));
            String description = firstNonBlank(stringValue(row.get("agent_hint")), stringValue(row.get("description")));
            return ToolDefinition.builder()
                    .toolCode("cmd:" + commandCode)
                    .toolName(displayName != null ? displayName : commandCode)
                    .description(description)
                    .providerCode("dsl")
                    .toolType(readQuery ? "dsl_query" : "dsl_command")
                    .sourceCode(commandCode)
                    .riskLevel(riskLevel)
                    .requiredPermissions(Set.copyOf(extractPermissions(rawPermissions)))
                    .confirmationPolicy(confirmationPolicy(riskLevel))
                    .requiresApproval(isApprovalRisk(riskLevel))
                    .requiresConfirmation(isConfirmationRisk(riskLevel))
                    .parameterSchema(buildCommandParameterSchema(row.get("input_schema"), executionConfig))
                    .build();
        } catch (Exception e) {
            log.warn("Failed to load declared command tool {}: {}", commandCode, e.getMessage());
            return null;
        }
    }

    private ToolDefinition loadDirectNamedQueryTool(Long tenantId, Long userId, String queryCode) {
        if (!canDiscoverNamedQueries(userId)) {
            return null;
        }
        try {
            String sql = "SELECT code, title, description, purpose, from_sql, parameter_schema "
                    + "FROM ab_named_query "
                    + "WHERE tenant_id = #{params.tenantId} "
                    + "AND code = #{params.queryCode} "
                    + "AND status = 'published' "
                    + "AND (deleted_flag = FALSE OR deleted_flag IS NULL) LIMIT 1";
            List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                    Map.of("tenantId", tenantId, "queryCode", queryCode));
            if (rows == null || rows.isEmpty()) {
                return null;
            }
            Map<String, Object> row = rows.get(0);
            String title = stringValue(row.get("title"));
            String description = firstNonBlank(stringValue(row.get("purpose")), stringValue(row.get("description")));
            return ToolDefinition.builder()
                    .toolCode("nq:" + queryCode)
                    .toolName(title != null ? title : queryCode)
                    .description(description)
                    .providerCode("dsl")
                    .toolType("dsl_query")
                    .sourceCode(queryCode)
                    .riskLevel("L0")
                    .confirmationPolicy("none")
                    .requiresApproval(false)
                    .requiresConfirmation(false)
                    .parameterSchema(buildNamedQueryParameterSchema(row.get("parameter_schema"), row.get("from_sql")))
                    .build();
        } catch (Exception e) {
            log.warn("Failed to load declared named query tool {}: {}", queryCode, e.getMessage());
            return null;
        }
    }

    private ToolDefinition loadDirectCustomTool(Long tenantId, String customCode) {
        try {
            String sql = "SELECT tool_code, tool_name, tool_description, tool_type, source_code, "
                    + "input_schema, requires_approval, risk_level "
                    + "FROM ab_agent_tool "
                    + "WHERE tenant_id = #{params.tenantId} "
                    + "AND tool_code = #{params.toolCode} "
                    + "AND tool_type NOT IN ('dsl_command', 'dsl_query') "
                    + "AND tool_status = 'active' "
                    + "AND (deleted_flag = FALSE OR deleted_flag IS NULL) LIMIT 1";
            List<Map<String, Object>> rows = dynamicDataMapper.selectByQueryWithoutTenant(sql,
                    Map.of("tenantId", tenantId, "toolCode", customCode));
            if (rows == null || rows.isEmpty()) {
                return null;
            }
            Map<String, Object> row = rows.get(0);
            String rawRiskLevel = stringValue(row.get("risk_level"));
            String normalizedRiskLevel = normalizeRiskLevel(rawRiskLevel, "L1");
            boolean requiresApproval = booleanValue(row.get("requires_approval"))
                    || isApprovalRisk(normalizedRiskLevel);
            String toolType = firstNonBlank(stringValue(row.get("tool_type")), "custom");
            String sourceCode = firstNonBlank(stringValue(row.get("source_code")), "custom:" + customCode);
            String displayName = firstNonBlank(stringValue(row.get("tool_name")), customCode);
            return ToolDefinition.builder()
                    .toolCode("custom:" + customCode)
                    .toolName(displayName)
                    .description(stringValue(row.get("tool_description")))
                    .providerCode("custom")
                    .toolType(toolType)
                    .sourceCode(sourceCode)
                    .riskLevel(rawRiskLevel != null ? rawRiskLevel : normalizedRiskLevel)
                    .confirmationPolicy(confirmationPolicy(normalizedRiskLevel))
                    .requiresApproval(requiresApproval)
                    .requiresConfirmation(requiresApproval || isConfirmationRisk(normalizedRiskLevel))
                    .parameterSchema(parseMap(row.get("input_schema")))
                    .build();
        } catch (Exception e) {
            log.warn("Failed to load declared custom tool {}: {}", customCode, e.getMessage());
            return null;
        }
    }

    private ToolDefinition loadProviderDeclaredTool(Long tenantId, Long userId, String toolCode) {
        String providerCode = providerCodeFor(toolCode);
        if (providerCode == null) {
            return null;
        }
        try {
            ToolDiscoveryContext ctx = ToolDiscoveryContext.builder()
                    .tenantId(tenantId)
                    .userId(userId)
                    .maxResults(Integer.MAX_VALUE)
                    .build();
            List<ToolDefinition> discovered = toolProviderRegistry.discoverByProvider(providerCode, ctx);
            if (discovered == null || discovered.isEmpty()) {
                return null;
            }
            for (ToolDefinition def : discovered) {
                if (def != null && toolCode.equals(def.getToolCode())) {
                    return def;
                }
            }
        } catch (Exception e) {
            log.warn("Provider-specific declared-tool discovery failed: provider={}, tool={}, error={}",
                    providerCode, toolCode, e.getMessage());
        }
        return null;
    }

    private String providerCodeFor(String toolCode) {
        if (toolCode == null) {
            return null;
        }
        if (toolCode.startsWith("custom:")) {
            return "custom";
        }
        if (toolCode.startsWith("mcp:")) {
            return "mcp";
        }
        if (toolCode.startsWith("platform.")) {
            return "platform";
        }
        if (toolCode.startsWith("aurabot:")) {
            return "aurabot";
        }
        return null;
    }

    private Map<String, Object> buildCommandParameterSchema(Object rawInputSchema,
                                                            Map<String, Object> executionConfig) {
        Map<String, Object> explicitSchema = parseMap(rawInputSchema);
        if (isUsableObjectSchema(explicitSchema)) {
            return explicitSchema;
        }

        Object inputFieldsObj = executionConfig != null ? executionConfig.get("inputFields") : null;
        if (!(inputFieldsObj instanceof List<?> inputFields) || inputFields.isEmpty()) {
            Object type = executionConfig != null ? executionConfig.get("type") : null;
            if (type != null && "state_transition".equalsIgnoreCase(String.valueOf(type))) {
                return objectSchema(recordPidProperties("Record pid to transition"), List.of("recordPid"));
            }
            return objectSchema(Map.of(), List.of());
        }

        Map<String, Object> properties = new LinkedHashMap<>();
        for (Object field : inputFields) {
            if (field != null && !String.valueOf(field).isBlank()) {
                String fieldCode = String.valueOf(field);
                properties.put(fieldCode, Map.of("type", "string", "description", "DSL field " + fieldCode));
            }
        }
        return objectSchema(properties, List.of());
    }

    private Map<String, Object> buildNamedQueryParameterSchema(Object rawParameterSchema, Object fromSql) {
        Map<String, Object> explicitSchema = parseMap(rawParameterSchema);
        if (isUsableObjectSchema(explicitSchema)) {
            return explicitSchema;
        }

        Set<String> params = new LinkedHashSet<>();
        Matcher matcher = NAMED_QUERY_PARAM_PATTERN.matcher(fromSql != null ? String.valueOf(fromSql) : "");
        while (matcher.find()) {
            String param = matcher.group(1);
            if (!NAMED_QUERY_SYSTEM_PARAMS.contains(param)) {
                params.add(param);
            }
        }

        Map<String, Object> properties = new LinkedHashMap<>();
        for (String param : params) {
            properties.put(param, Map.of("type", "string", "description", "NamedQuery parameter " + param));
        }
        return objectSchema(properties, List.of());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return result;
        }
        String text = String.valueOf(value);
        if (text.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(text, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.debug("Failed to parse declared tool schema/config: {}", e.getMessage());
            return Map.of();
        }
    }

    private boolean isUsableObjectSchema(Map<String, Object> schema) {
        Object properties = schema.get("properties");
        return "object".equals(schema.get("type"))
                && properties instanceof Map<?, ?> map
                && !map.isEmpty();
    }

    private List<String> extractPermissions(Object rawPermissions) {
        if (!(rawPermissions instanceof List<?> values) || values.isEmpty()) {
            return List.of();
        }
        List<String> permissions = new ArrayList<>();
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) {
                permissions.add(String.valueOf(value));
            }
        }
        return permissions;
    }

    private boolean hasAnyDeclaredPermission(Long userId, Object rawPermissions) {
        if (userId == null) {
            return true;
        }
        List<String> permissions = extractPermissions(rawPermissions);
        if (permissions.isEmpty()) {
            return true;
        }
        for (String permission : permissions) {
            if (hasPermission(userId, permission)) {
                return true;
            }
        }
        return false;
    }

    private boolean canReadModel(Long userId, String modelCode) {
        return userId == null || hasPermission(userId, "model." + modelCode + ".read");
    }

    private boolean canDiscoverNamedQueries(Long userId) {
        return userId == null
                || hasPermission(userId, MetaPermission.QUERY_READ)
                || hasPermission(userId, MetaPermission.DATASOURCE_READ);
    }

    private boolean hasPermission(Long userId, String permission) {
        try {
            return userPermissionService.hasPermission(userId, permission);
        } catch (RuntimeException e) {
            log.warn("Failed to resolve declared-tool permission: userId={}, permission={}, errorType={}",
                    userId, permission, e.getClass().getSimpleName());
            return false;
        }
    }

    private Map<String, Object> listParameterSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("pageNum", Map.of("type", "integer", "minimum", 1));
        properties.put("pageSize", Map.of("type", "integer", "minimum", 1, "maximum", 1000));
        properties.put("keyword", Map.of("type", "string"));
        properties.put("filters", Map.of("type", "array", "items", Map.of("type", "object")));
        return objectSchema(properties, List.of());
    }

    private Map<String, Object> getParameterSchema() {
        return objectSchema(recordPidProperties("Record pid to fetch"), List.of("recordPid"));
    }

    private Map<String, Object> recordPidProperties(String description) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("recordPid", Map.of("type", "string", "description", description));
        properties.put("recordId", Map.of("type", "string", "description", "Compatibility alias for recordPid"));
        properties.put("pid", Map.of("type", "string", "description", "Compatibility alias for recordPid"));
        return properties;
    }

    private Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties != null ? properties : Map.of());
        if (required != null && !required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    private boolean isReadQueryCommand(String commandCode, Map<String, Object> executionConfig, Object riskLevel) {
        String type = String.valueOf(executionConfig.getOrDefault("type", "")).toLowerCase(Locale.ROOT);
        String risk = riskLevel != null ? String.valueOf(riskLevel).toLowerCase(Locale.ROOT) : "";
        String normalizedCode = commandCode != null ? commandCode.toLowerCase(Locale.ROOT) : "";
        return "query".equals(type)
                || "l0".equals(risk)
                || normalizedCode.contains(":list_")
                || normalizedCode.contains(":detail_")
                || normalizedCode.contains(":get_")
                || normalizedCode.contains(":search_");
    }

    private String normalizeRiskLevel(Object riskLevel, String fallback) {
        if (riskLevel == null) {
            return fallback;
        }
        String normalized = String.valueOf(riskLevel).trim().toUpperCase(Locale.ROOT);
        if (normalized.startsWith("R") && normalized.length() == 2) {
            normalized = "L" + normalized.substring(1);
        }
        return switch (normalized) {
            case "L0", "L1", "L2", "L3", "L4" -> normalized;
            case "LOW" -> "L0";
            case "MEDIUM" -> "L2";
            case "HIGH" -> "L3";
            case "CRITICAL" -> "L4";
            default -> fallback;
        };
    }

    private String confirmationPolicy(String riskLevel) {
        return switch (normalizeRiskLevel(riskLevel, "L1")) {
            case "L2" -> "confirm";
            case "L3" -> "confirm_with_detail";
            case "L4" -> "approval_required";
            default -> "none";
        };
    }

    private boolean isConfirmationRisk(String riskLevel) {
        return "L2".equals(normalizeRiskLevel(riskLevel, "L1"));
    }

    private boolean isApprovalRisk(String riskLevel) {
        String normalized = normalizeRiskLevel(riskLevel, "L1");
        return "L3".equals(normalized) || "L4".equals(normalized);
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    /** Parse the agent's {@code tools} column (JSON array, comma string, or list of maps) into codes. */
    @SuppressWarnings("unchecked")
    static List<String> parseDeclaredCodes(Map<String, Object> agentDef, ObjectMapper objectMapper) {
        if (agentDef == null || agentDef.get("tools") == null) {
            return List.of();
        }
        Object raw = agentDef.get("tools");
        List<Object> values = new ArrayList<>();
        if (raw instanceof List<?> list) {
            values.addAll((List<Object>) list);
        } else {
            String text = String.valueOf(raw).trim();
            if (text.isBlank()) {
                return List.of();
            }
            if (text.startsWith("[")) {
                try {
                    values.addAll(objectMapper.readValue(text, List.class));
                } catch (Exception e) {
                    return List.of();
                }
            } else {
                Collections.addAll(values, (Object[]) text.split(","));
            }
        }
        Set<String> codes = new LinkedHashSet<>();
        for (Object value : values) {
            String code = null;
            if (value instanceof Map<?, ?> map) {
                Object rawCode = map.get("toolCode");
                if (rawCode == null) {
                    rawCode = map.get("code");
                }
                if (rawCode == null) {
                    rawCode = map.get("name");
                }
                if (rawCode != null) {
                    code = String.valueOf(rawCode);
                }
            } else if (value != null) {
                code = String.valueOf(value);
            }
            if (code != null && !code.isBlank()) {
                codes.add(code.trim());
            }
        }
        return new ArrayList<>(codes);
    }
}
