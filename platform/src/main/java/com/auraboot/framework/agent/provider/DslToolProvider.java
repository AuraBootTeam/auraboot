package com.auraboot.framework.agent.provider;

import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.CommandExecuteResult;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.NamedQueryTestRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.dto.QueryCondition;
import com.auraboot.framework.meta.dto.SortField;
import com.fasterxml.jackson.core.type.TypeReference;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.CommandExecutor;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.NamedQueryService;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
 * ToolProvider implementation for DSL-based tools (commands, named queries, list, get-by-id).
 *
 * Discovers tools by reading DSL metadata tables (ab_command_definition, ab_named_query)
 * instead of ab_agent_tool, and executes them via the standard DSL engine services.
 *
 * Tool code conventions:
 * - cmd:{commandCode}  — execute a DSL command
 * - nq:{queryCode}     — execute a named query
 * - list:{modelCode}   — paginated list by model
 * - get:{modelCode}    — get single record by ID
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DslToolProvider implements ToolProvider {

    private static final String PREFIX_CMD = "cmd:";
    private static final String PREFIX_NQ = "nq:";
    private static final String PREFIX_LIST = "list:";
    private static final String PREFIX_GET = "get:";
    private static final Pattern PARAM_PATTERN = Pattern.compile("#\\{params\\.([A-Za-z0-9_]+)}");
    private static final Set<String> SYSTEM_QUERY_PARAMS = Set.of(
            "tenantId", "currentUserId", "currentUserPid", "page", "pageSize", "offset", "limit");

    private final CommandExecutor commandExecutor;
    private final DynamicDataService dynamicDataService;
    private final NamedQueryService namedQueryService;
    private final DynamicDataMapper dynamicDataMapper;
    private final ObjectMapper objectMapper;
    private final UserPermissionService userPermissionService;

    @Override
    public String providerCode() {
        return "dsl";
    }

    @Override
    public boolean handles(String toolCode) {
        return toolCode != null && (
                toolCode.startsWith(PREFIX_CMD) || toolCode.startsWith(PREFIX_NQ) ||
                toolCode.startsWith(PREFIX_LIST) || toolCode.startsWith(PREFIX_GET));
    }

    @Override
    public List<ToolDefinition> discover(ToolDiscoveryContext ctx) {
        if (ctx.getModelHint() == null || ctx.getModelHint().isBlank()) {
            // Too many tools to enumerate without a model hint
            return List.of();
        }

        String modelHint = ctx.getModelHint();
        int maxResults = ctx.getMaxResults() > 0 ? ctx.getMaxResults() : 20;
        List<ToolDefinition> tools = new ArrayList<>();

        // 1. Discover commands for this model
        try {
            String sql = "SELECT code, display_name, description, agent_hint, input_schema, execution_config, cmd_risk_level " +
                    "FROM ab_command_definition " +
                    "WHERE tenant_id = #{params.tenantId} " +
                    "AND model_code = #{params.modelCode} " +
                    "AND (deleted_flag = FALSE OR deleted_flag IS NULL) " +
                    "AND (is_current = TRUE OR is_current IS NULL) " +
                    "LIMIT #{params.limit}";
            List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                    Map.of("tenantId", ctx.getTenantId(), "modelCode", modelHint, "limit", maxResults));
            for (Map<String, Object> row : rows) {
                String code = (String) row.get("code");
                String displayName = (String) row.get("display_name");
                String description = row.get("agent_hint") != null
                        ? (String) row.get("agent_hint")
                        : (String) row.get("description");
                Map<String, Object> executionConfig = parseExecutionConfig(row.get("execution_config"));
                if (!hasAnyDeclaredPermission(ctx.getUserId(), executionConfig.get("permissions"))) {
                    continue;
                }
                boolean readQuery = isReadQueryCommand(code, executionConfig, row.get("cmd_risk_level"));
                String riskLevel = readQuery ? "L0" : normalizeRiskLevel(row.get("cmd_risk_level"), "L1");
                Map<String, Object> parameterSchema = buildCommandParameterSchema(
                        ctx.getTenantId(), modelHint, row.get("input_schema"), executionConfig);
                tools.add(ToolDefinition.builder()
                        .toolCode(PREFIX_CMD + code)
                        .toolName(displayName != null ? displayName : code)
                        .description(description)
                        .providerCode("dsl")
                        .toolType(readQuery ? "dsl_query" : "dsl_command")
                        .sourceCode(code)
                        .riskLevel(riskLevel)
                        .confirmationPolicy(confirmationPolicy(riskLevel))
                        .requiresApproval(isApprovalRisk(riskLevel))
                        .requiresConfirmation(isConfirmationRisk(riskLevel))
                        .parameterSchema(parameterSchema)
                        .build());
            }
        } catch (Exception e) {
            log.warn("Failed to discover DSL commands for model {}: {}", modelHint, e.getMessage());
        }

        // 2. Discover named queries matching this model
        try {
            String sql = "SELECT code, title, description, purpose, from_sql, parameter_schema " +
                    "FROM ab_named_query " +
                    "WHERE tenant_id = #{params.tenantId} " +
                    "AND code LIKE #{params.codePattern} " +
                    "AND status = 'published' " +
                    "LIMIT #{params.limit}";
            int remaining = maxResults - tools.size();
            if (remaining > 0 && canDiscoverNamedQueries(ctx.getUserId())) {
                List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                        Map.of("tenantId", ctx.getTenantId(), "codePattern", modelHint + "%", "limit", remaining));
                for (Map<String, Object> row : rows) {
                    String code = (String) row.get("code");
                    String title = (String) row.get("title");
                    String description = row.get("purpose") != null
                            ? (String) row.get("purpose")
                            : (String) row.get("description");
                    tools.add(ToolDefinition.builder()
                            .toolCode(PREFIX_NQ + code)
                            .toolName(title != null ? title : code)
                            .description(description)
                            .providerCode("dsl")
                            .toolType("dsl_query")
                            .sourceCode(code)
                            .riskLevel("L0")
                            .confirmationPolicy("none")
                            .parameterSchema(buildNamedQueryParameterSchema(
                                    row.get("parameter_schema"), row.get("from_sql")))
                            .build());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to discover named queries for model {}: {}", modelHint, e.getMessage());
        }

        // 3. Always add generic list + get tools for the model
        boolean canReadModel = canReadModel(ctx.getUserId(), modelHint);
        if (canReadModel && tools.size() < maxResults) {
            tools.add(ToolDefinition.builder()
                    .toolCode(PREFIX_LIST + modelHint)
                    .toolName("List " + modelHint)
                    .description("Paginated list of " + modelHint + " records. Params: pageNum, pageSize, keyword.")
                    .providerCode("dsl")
                    .toolType("dsl_query")
                    .sourceCode(modelHint)
                    .riskLevel("L0")
                    .confirmationPolicy("none")
                    .parameterSchema(listParameterSchema())
                    .build());
        }
        if (canReadModel && tools.size() < maxResults) {
            tools.add(ToolDefinition.builder()
                    .toolCode(PREFIX_GET + modelHint)
                    .toolName("Get " + modelHint)
                    .description("Get a single " + modelHint + " record by pid. Params: recordPid (required); recordId is accepted for compatibility.")
                    .providerCode("dsl")
                    .toolType("dsl_query")
                    .sourceCode(modelHint)
                    .riskLevel("L0")
                    .confirmationPolicy("none")
                    .parameterSchema(getParameterSchema())
                    .build());
        }

        return tools;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildCommandParameterSchema(Long tenantId, String modelCode,
                                                             Object rawInputSchema,
                                                             Map<String, Object> executionConfig) {
        Map<String, Object> explicitSchema = parseMap(rawInputSchema);
        if (isUsableSchema(explicitSchema)) {
            return explicitSchema;
        }

        Object inputFieldsObj = executionConfig != null ? executionConfig.get("inputFields") : null;
        if (!(inputFieldsObj instanceof List<?> inputFields) || inputFields.isEmpty()) {
            Object type = executionConfig != null ? executionConfig.get("type") : null;
            if (type != null && "state_transition".equalsIgnoreCase(String.valueOf(type))) {
                return objectSchema(
                        recordPidProperties("Record pid to transition"),
                        List.of("recordPid"));
            }
            return objectSchema(Map.of(), List.of());
        }

        Set<String> fieldCodes = new LinkedHashSet<>();
        for (Object field : inputFields) {
            if (field != null && !String.valueOf(field).isBlank()) {
                fieldCodes.add(String.valueOf(field));
            }
        }

        Map<String, FieldSchemaInfo> fieldInfo = loadModelFieldInfo(tenantId, modelCode);
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (String fieldCode : fieldCodes) {
            FieldSchemaInfo info = fieldInfo.get(fieldCode);
            Map<String, Object> propertySchema = jsonTypeFor(info != null ? info.dataType() : "text", fieldCode);
            if (info != null && info.required() && info.editable()) {
                required.add(fieldCode);
                if ("string".equals(propertySchema.get("type"))) {
                    propertySchema.put("minLength", 1);
                }
            }
            properties.put(fieldCode, propertySchema);
        }
        return objectSchema(properties, required);
    }

    private Map<String, Object> buildNamedQueryParameterSchema(Object rawParameterSchema, Object fromSql) {
        Map<String, Object> explicitSchema = parseMap(rawParameterSchema);
        if (isUsableSchema(explicitSchema)) {
            return explicitSchema;
        }

        Set<String> params = new LinkedHashSet<>();
        Matcher matcher = PARAM_PATTERN.matcher(fromSql != null ? String.valueOf(fromSql) : "");
        while (matcher.find()) {
            String param = matcher.group(1);
            if (!SYSTEM_QUERY_PARAMS.contains(param)) {
                params.add(param);
            }
        }

        Map<String, Object> properties = new LinkedHashMap<>();
        for (String param : params) {
            properties.put(param, Map.of("type", "string", "description", "NamedQuery parameter " + param));
        }
        return objectSchema(properties, List.of());
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
            log.debug("Failed to parse DSL tool parameter schema: {}", e.getMessage());
            return Map.of();
        }
    }

    private boolean isUsableSchema(Map<String, Object> schema) {
        Object properties = schema.get("properties");
        return "object".equals(schema.get("type"))
                && properties instanceof Map<?, ?> map
                && !map.isEmpty();
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
            if (userPermissionService.hasPermission(userId, permission)) {
                return true;
            }
        }
        return false;
    }

    private boolean canReadModel(Long userId, String modelCode) {
        return userId == null || userPermissionService.hasPermission(userId, "model." + modelCode + ".read");
    }

    private boolean canDiscoverNamedQueries(Long userId) {
        return userId == null
                || userPermissionService.hasPermission(userId, MetaPermission.QUERY_READ)
                || userPermissionService.hasPermission(userId, MetaPermission.DATASOURCE_READ);
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

    private Map<String, FieldSchemaInfo> loadModelFieldInfo(Long tenantId, String modelCode) {
        try {
            String sql = "SELECT f.code, f.data_type, b.required, b.editable " +
                    "FROM ab_meta_field f " +
                    "JOIN ab_meta_model_field_binding b ON b.field_id = f.id " +
                    "JOIN ab_meta_model m ON m.id = b.model_id " +
                    "WHERE m.tenant_id = #{params.tenantId} " +
                    "AND m.code = #{params.modelCode} " +
                    "AND (m.deleted_flag = FALSE OR m.deleted_flag IS NULL) " +
                    "AND (f.deleted_flag = FALSE OR f.deleted_flag IS NULL) " +
                    "AND (b.deleted_flag = FALSE OR b.deleted_flag IS NULL) " +
                    "AND (m.is_current = TRUE OR m.is_current IS NULL) " +
                    "AND (f.is_current = TRUE OR f.is_current IS NULL) " +
                    "ORDER BY b.field_order";
            List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                    Map.of("tenantId", tenantId, "modelCode", modelCode));
            Map<String, FieldSchemaInfo> result = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                String code = String.valueOf(row.get("code"));
                result.put(code, new FieldSchemaInfo(
                        String.valueOf(row.getOrDefault("data_type", "text")),
                        Boolean.TRUE.equals(row.get("required")),
                        !Boolean.FALSE.equals(row.get("editable"))));
            }
            return result;
        } catch (Exception e) {
            log.debug("Failed to load field metadata for DSL tool schema {}: {}", modelCode, e.getMessage());
            return Map.of();
        }
    }

    private Map<String, Object> jsonTypeFor(String dataType, String fieldCode) {
        String normalized = dataType != null ? dataType.toLowerCase(Locale.ROOT) : "text";
        Map<String, Object> schema = new LinkedHashMap<>();
        if (normalized.contains("int")) {
            schema.put("type", "integer");
        } else if (normalized.contains("decimal") || normalized.contains("number")
                || normalized.contains("double") || normalized.contains("float")
                || normalized.contains("currency")) {
            schema.put("type", "number");
        } else if (normalized.contains("bool")) {
            schema.put("type", "boolean");
        } else if (normalized.equals("date")) {
            schema.put("type", "string");
            schema.put("format", "date");
        } else if (normalized.contains("time")) {
            schema.put("type", "string");
            schema.put("format", "date-time");
        } else if (normalized.contains("json")) {
            schema.put("type", "object");
        } else {
            schema.put("type", "string");
        }
        schema.put("description", "DSL field " + fieldCode);
        return schema;
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

    private record FieldSchemaInfo(String dataType, boolean required, boolean editable) {}

    @Override
    public ProviderExecutionResult execute(Long tenantId, String toolCode, Map<String, Object> params) {
        long start = System.currentTimeMillis();
        try {
            if (toolCode.startsWith(PREFIX_CMD)) {
                return executeCommand(tenantId, toolCode.substring(PREFIX_CMD.length()), params, start);
            } else if (toolCode.startsWith(PREFIX_NQ)) {
                return executeNamedQuery(toolCode.substring(PREFIX_NQ.length()), params, start);
            } else if (toolCode.startsWith(PREFIX_LIST)) {
                return executeList(toolCode.substring(PREFIX_LIST.length()), params, start);
            } else if (toolCode.startsWith(PREFIX_GET)) {
                return executeGetById(toolCode.substring(PREFIX_GET.length()), params, start);
            }

            return ProviderExecutionResult.builder()
                    .success(false)
                    .errorMessage("Unknown DSL tool code prefix: " + toolCode)
                    .durationMs(System.currentTimeMillis() - start)
                    .build();
        } catch (Exception e) {
            log.error("DSL tool execution failed: toolCode={}, error={}", toolCode, e.getMessage());
            return ProviderExecutionResult.builder()
                    .success(false)
                    .errorMessage(e.getMessage())
                    .durationMs(System.currentTimeMillis() - start)
                    .build();
        }
    }

    // ========== Execution Methods ==========

    private ProviderExecutionResult executeCommand(Long tenantId, String commandCode, Map<String, Object> params, long start) {
        Map<String, Object> command = loadCommandMetadata(tenantId, commandCode);
        if (command != null) {
            String modelCode = (String) command.get("model_code");
            Map<String, Object> executionConfig = parseExecutionConfig(command.get("execution_config"));
            if (isReadQueryCommand(commandCode, executionConfig, command.get("cmd_risk_level"))
                    && modelCode != null && !modelCode.isBlank()) {
                if (isDetailQueryCommand(commandCode, params)) {
                    return executeGetById(modelCode, params, start);
                }
                return executeList(modelCode, params, start);
            }
        }

        CommandExecuteRequest req = new CommandExecuteRequest();
        req.setPayload(params != null ? params : Map.of());
        CommandExecuteResult cmdResult = commandExecutor.execute(commandCode, req);
        return ProviderExecutionResult.builder()
                .success(true)
                .data(cmdResult.getData() != null ? cmdResult.getData() : Map.of())
                .durationMs(System.currentTimeMillis() - start)
                .build();
    }

    private ProviderExecutionResult executeNamedQuery(String queryCode, Map<String, Object> params, long start) {
        NamedQueryTestRequest nqReq = new NamedQueryTestRequest();
        nqReq.setParameters(params);
        PaginationResult<Map<String, Object>> result = namedQueryService.executeQuery(queryCode, nqReq);
        List<Map<String, Object>> records = result.getRecords() != null ? result.getRecords() : List.of();
        return ProviderExecutionResult.builder()
                .success(true)
                .data(Map.of("total", result.getTotal(), "records", records))
                .durationMs(System.currentTimeMillis() - start)
                .build();
    }

    private ProviderExecutionResult executeList(String modelCode, Map<String, Object> params, long start) {
        DynamicQueryRequest qr = buildQueryRequest(params);
        PaginationResult<Map<String, Object>> result = dynamicDataService.list(modelCode, qr);
        List<Map<String, Object>> records = result.getRecords() != null ? result.getRecords() : List.of();
        return ProviderExecutionResult.builder()
                .success(true)
                .data(Map.of("total", result.getTotal(), "records", records))
                .durationMs(System.currentTimeMillis() - start)
                .build();
    }

    private ProviderExecutionResult executeGetById(String modelCode, Map<String, Object> params, long start) {
        String recordId = extractRecordId(params);
        if (recordId == null) {
            return ProviderExecutionResult.builder()
                    .success(false)
                    .errorMessage("recordPid is required for get: tool")
                    .durationMs(System.currentTimeMillis() - start)
                    .build();
        }
        Map<String, Object> record = dynamicDataService.getById(modelCode, recordId);
        return ProviderExecutionResult.builder()
                .success(record != null)
                .data(record != null ? Map.of("record", record) : Map.of())
                .errorMessage(record == null ? "Record not found: " + recordId : null)
                .durationMs(System.currentTimeMillis() - start)
                .build();
    }

    private String extractRecordId(Map<String, Object> params) {
        if (params == null) return null;
        Object recordId = params.get("recordPid");
        if (recordId == null) recordId = params.get("recordId");
        if (recordId == null) recordId = params.get("pid");
        if (recordId == null) recordId = params.get("id");
        return recordId != null ? String.valueOf(recordId) : null;
    }

    // ========== Helpers ==========

    private DynamicQueryRequest buildQueryRequest(Map<String, Object> params) {
        if (params == null) params = Map.of();
        Integer pageNum = params.get("pageNum") instanceof Number n ? n.intValue()
                : params.get("page") instanceof Number n ? n.intValue()
                : 1;
        Integer pageSize = params.get("pageSize") instanceof Number n ? n.intValue() : 20;
        String keyword = params.get("keyword") instanceof String s ? s
                : params.get("search") instanceof String s ? s
                : null;
        return DynamicQueryRequest.builder()
                .pageNum(pageNum)
                .pageSize(pageSize)
                .keyword(keyword)
                .conditions(parseConditions(params.get("filters")))
                .sortFields(parseSortFields(params))
                .extraParams(extractExtraParams(params))
                .build();
    }

    private Map<String, Object> loadCommandMetadata(Long tenantId, String commandCode) {
        String sql = "SELECT model_code, execution_config, cmd_risk_level " +
                "FROM ab_command_definition " +
                "WHERE tenant_id = #{params.tenantId} " +
                "AND code = #{params.code} " +
                "AND (deleted_flag = FALSE OR deleted_flag IS NULL) " +
                "AND (is_current = TRUE OR is_current IS NULL) " +
                "LIMIT 1";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                Map.of("tenantId", tenantId, "code", commandCode));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> parseExecutionConfig(Object raw) {
        if (raw == null) return Map.of();
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return result;
        }
        String text = String.valueOf(raw);
        if (text.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(text, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.debug("Failed to parse command execution_config: {}", text, e);
            return Map.of();
        }
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

    private boolean isDetailQueryCommand(String commandCode, Map<String, Object> params) {
        if (params != null && (params.containsKey("recordId") || params.containsKey("pid") || params.containsKey("id"))) {
            String code = commandCode != null ? commandCode.toLowerCase(Locale.ROOT) : "";
            return code.contains(":detail_") || code.contains(":get_");
        }
        return false;
    }

    private List<QueryCondition> parseConditions(Object rawFilters) {
        if (rawFilters == null) return null;
        try {
            List<QueryCondition> conditions;
            if (rawFilters instanceof String text) {
                if (text.isBlank()) return null;
                conditions = objectMapper.readValue(text, new TypeReference<List<QueryCondition>>() {});
            } else {
                conditions = objectMapper.convertValue(rawFilters, new TypeReference<List<QueryCondition>>() {});
            }
            return conditions == null || conditions.isEmpty() ? null : conditions;
        } catch (Exception e) {
            log.warn("Ignoring invalid DSL query filters from tool params: {}", rawFilters, e);
            return null;
        }
    }

    private List<SortField> parseSortFields(Map<String, Object> params) {
        if (params == null) return null;
        Object sortFields = params.get("sortFields");
        if (sortFields instanceof List<?> list && !list.isEmpty()) {
            try {
                return objectMapper.convertValue(sortFields, new TypeReference<List<SortField>>() {});
            } catch (Exception e) {
                log.warn("Ignoring invalid DSL query sortFields from tool params: {}", sortFields, e);
            }
        }

        Object sortField = params.get("sortField");
        if (!(sortField instanceof String fieldName) || fieldName.isBlank()) {
            return null;
        }
        SortField.SortDirection direction = "asc".equalsIgnoreCase(String.valueOf(params.get("sortOrder")))
                ? SortField.SortDirection.ASC
                : SortField.SortDirection.DESC;
        return List.of(SortField.builder()
                .fieldName(fieldName)
                .direction(direction)
                .build());
    }

    private Map<String, Object> extractExtraParams(Map<String, Object> params) {
        if (params == null || params.isEmpty()) return Collections.emptyMap();
        Map<String, Object> extra = new LinkedHashMap<>(params);
        extra.keySet().removeAll(List.of(
                "pageNum", "page", "pageSize", "keyword", "search", "filters",
                "sortField", "sortOrder", "sortFields", "recordPid", "recordId", "pid", "id"));
        return extra.isEmpty() ? Collections.emptyMap() : extra;
    }
}
