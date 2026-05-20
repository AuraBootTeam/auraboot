package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.BusinessIntentFrame;
import com.auraboot.framework.agent.provider.ToolDefinition;
import com.auraboot.framework.agent.provider.ToolDiscoveryContext;
import com.auraboot.framework.agent.provider.ToolProviderRegistry;
import com.auraboot.framework.common.util.LogSanitizer;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@RequiredArgsConstructor
class AgentChatToolDiscoveryAdapter {

    private static final Pattern NAMED_QUERY_PARAM_PATTERN =
            Pattern.compile("#\\{params\\.([A-Za-z0-9_]+)}");
    private static final Set<String> NAMED_QUERY_SYSTEM_PARAMS =
            Set.of("tenantId", "currentUserId", "currentUserPid", "page", "pageSize", "offset", "limit");

    private final DynamicDataMapper dynamicDataMapper;
    private final ToolProviderRegistry toolProviderRegistry;
    private final GroundingService groundingService;
    private final ObjectMapper objectMapper;

    List<ToolDefinition> discover(Long tenantId,
                                  Long userId,
                                  String agentCode,
                                  String userMessage,
                                  Map<String, Object> agentDef) {
        try {
            BusinessIntentFrame bif = groundingService.ground(
                    tenantId, userMessage,
                    GroundingService.GroundingContext.builder().build());

            List<ToolDefinition> explicitDefs = discoverExplicitAgentTools(tenantId, userId, agentCode, agentDef, bif);

            ToolDiscoveryContext ctx = ToolDiscoveryContext.builder()
                    .tenantId(tenantId)
                    .userId(userId)
                    .agentCode(agentCode)
                    .modelHint(bif != null ? bif.getObject() : null)
                    .intentHint(bif != null ? bif.getIntent() : null)
                    .maxResults(20)
                    .build();

            List<ToolDefinition> defs = toolProviderRegistry.discoverAll(ctx);
            return mergeExplicitTools(explicitDefs, defs);
        } catch (Exception e) {
            String error = safeExceptionMessage(e);
            log.error("Tool discovery failed for agent {}: {}", agentCode, error, e);
            throw new IllegalStateException("Tool discovery failed for agent " + agentCode + ": " + error, e);
        }
    }

    private List<ToolDefinition> discoverExplicitAgentTools(Long tenantId, Long userId, String agentCode,
                                                            Map<String, Object> agentDef, BusinessIntentFrame bif) {
        List<String> explicitCodes = explicitToolCodes(agentDef);
        if (explicitCodes.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> discoveryHints = new LinkedHashSet<>();
        for (String code : explicitCodes) {
            String hint = resolveExplicitToolModelHint(tenantId, code);
            if (hint != null && !hint.isBlank()) {
                discoveryHints.add(hint);
            }
        }
        if (discoveryHints.isEmpty() && bif != null && bif.getObject() != null && !bif.getObject().isBlank()) {
            discoveryHints.add(bif.getObject());
        }
        if (discoveryHints.isEmpty()) {
            discoveryHints.add(null);
        }

        Map<String, ToolDefinition> byCode = new LinkedHashMap<>();
        Set<String> explicitSet = new LinkedHashSet<>(explicitCodes);
        for (String modelHint : discoveryHints) {
            ToolDiscoveryContext ctx = ToolDiscoveryContext.builder()
                    .tenantId(tenantId)
                    .userId(userId)
                    .agentCode(agentCode)
                    .modelHint(modelHint)
                    .intentHint(bif != null ? bif.getIntent() : null)
                    .maxResults(100)
                    .build();
            List<ToolDefinition> discovered = toolProviderRegistry.discoverAll(ctx);
            if (discovered == null) {
                continue;
            }
            for (ToolDefinition def : discovered) {
                if (def == null || def.getToolCode() == null) {
                    continue;
                }
                if (explicitSet.contains(def.getToolCode())) {
                    byCode.putIfAbsent(def.getToolCode(), def);
                }
            }
        }

        for (String code : explicitCodes) {
            if (!byCode.containsKey(code)) {
                ToolDefinition direct = loadDirectExplicitTool(tenantId, code);
                if (direct != null) {
                    byCode.putIfAbsent(code, direct);
                } else {
                    log.warn("Explicit agent tool was not discoverable: agent={}, tool={}", agentCode, code);
                }
            }
        }
        return new ArrayList<>(byCode.values());
    }

    private ToolDefinition loadDirectExplicitTool(Long tenantId, String toolCode) {
        if (toolCode == null || !toolCode.startsWith("nq:")) {
            return null;
        }
        String queryCode = toolCode.substring("nq:".length());
        try {
            String sql = "SELECT code, title, description, purpose, from_sql, parameter_schema " +
                    "FROM ab_named_query " +
                    "WHERE tenant_id = #{params.tenantId} " +
                    "AND code = #{params.queryCode} " +
                    "AND status = 'published' " +
                    "AND (deleted_flag = FALSE OR deleted_flag IS NULL) " +
                    "LIMIT 1";
            List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                    Map.of("tenantId", tenantId, "queryCode", queryCode));
            if (rows == null || rows.isEmpty()) {
                return null;
            }
            Map<String, Object> row = rows.get(0);
            String title = stringValue(row.get("title"));
            String purpose = stringValue(row.get("purpose"));
            String description = purpose != null ? purpose : stringValue(row.get("description"));
            return ToolDefinition.builder()
                    .toolCode(toolCode)
                    .toolName(title != null ? title : queryCode)
                    .description(description)
                    .providerCode("dsl")
                    .toolType("dsl_query")
                    .sourceCode(queryCode)
                    .riskLevel("L0")
                    .confirmationPolicy("none")
                    .requiresApproval(false)
                    .requiresConfirmation(false)
                    .parameterSchema(buildNamedQueryParameterSchema(
                            row.get("parameter_schema"), row.get("from_sql")))
                    .build();
        } catch (Exception e) {
            log.warn("Failed to load explicit named query tool {}: {}", queryCode, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildNamedQueryParameterSchema(Object rawParameterSchema, Object fromSql) {
        Map<String, Object> parsed = parseJsonObject(rawParameterSchema);
        if (isUsableObjectSchema(parsed)) {
            return parsed;
        }

        Set<String> params = new LinkedHashSet<>();
        if (fromSql != null) {
            Matcher matcher = NAMED_QUERY_PARAM_PATTERN.matcher(String.valueOf(fromSql));
            while (matcher.find()) {
                String param = matcher.group(1);
                if (!NAMED_QUERY_SYSTEM_PARAMS.contains(param)) {
                    params.add(param);
                }
            }
        }

        Map<String, Object> properties = new LinkedHashMap<>();
        for (String param : params) {
            properties.put(param, Map.of("type", "string", "description", "NamedQuery parameter " + param));
        }
        return Map.of("type", "object", "properties", properties);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonObject(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        if (value == null) {
            return Map.of();
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(text, Map.class);
        } catch (Exception e) {
            log.debug("Failed to parse explicit named query parameter schema: {}", e.getMessage());
            return Map.of();
        }
    }

    private boolean isUsableObjectSchema(Map<String, Object> schema) {
        Object properties = schema.get("properties");
        return "object".equals(schema.get("type"))
                && properties instanceof Map<?, ?> map
                && !map.isEmpty();
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private List<ToolDefinition> mergeExplicitTools(List<ToolDefinition> explicitTools,
                                                    List<ToolDefinition> discoveredTools) {
        Map<String, ToolDefinition> byCode = new LinkedHashMap<>();
        if (explicitTools != null) {
            for (ToolDefinition tool : explicitTools) {
                if (tool != null && tool.getToolCode() != null) {
                    byCode.put(tool.getToolCode(), tool);
                }
            }
        }
        if (discoveredTools != null) {
            for (ToolDefinition tool : discoveredTools) {
                if (tool != null && tool.getToolCode() != null) {
                    byCode.putIfAbsent(tool.getToolCode(), tool);
                }
            }
        }
        return new ArrayList<>(byCode.values());
    }

    @SuppressWarnings("unchecked")
    private List<String> explicitToolCodes(Map<String, Object> agentDef) {
        if (agentDef == null || agentDef.get("tools") == null) {
            return Collections.emptyList();
        }
        Object raw = agentDef.get("tools");
        List<Object> values = new ArrayList<>();
        if (raw instanceof List<?> list) {
            values.addAll((List<Object>) list);
        } else {
            String text = String.valueOf(raw).trim();
            if (text.isBlank()) {
                return Collections.emptyList();
            }
            if (text.startsWith("[")) {
                try {
                    values.addAll(objectMapper.readValue(text, List.class));
                } catch (Exception e) {
                    log.warn("Failed to parse agent tools JSON: {}", e.getMessage());
                    return Collections.emptyList();
                }
            } else {
                for (String item : text.split(",")) {
                    values.add(item);
                }
            }
        }

        Set<String> codes = new LinkedHashSet<>();
        for (Object value : values) {
            String code = null;
            if (value instanceof Map<?, ?> map) {
                Object rawCode = map.get("toolCode");
                if (rawCode == null) rawCode = map.get("code");
                if (rawCode == null) rawCode = map.get("name");
                if (rawCode != null) code = String.valueOf(rawCode);
            } else if (value != null) {
                code = String.valueOf(value);
            }
            if (code != null && !code.isBlank()) {
                codes.add(code.trim());
            }
        }
        return new ArrayList<>(codes);
    }

    private String resolveExplicitToolModelHint(Long tenantId, String toolCode) {
        if (toolCode == null || toolCode.isBlank()) {
            return null;
        }
        if (toolCode.startsWith("cmd:")) {
            return loadCommandModelCode(tenantId, toolCode.substring("cmd:".length()));
        }
        if (toolCode.startsWith("nq:")) {
            return inferNamedQueryModelCode(tenantId, toolCode.substring("nq:".length()));
        }
        if (toolCode.startsWith("list:")) {
            return toolCode.substring("list:".length());
        }
        if (toolCode.startsWith("get:")) {
            return toolCode.substring("get:".length());
        }
        return null;
    }

    private String loadCommandModelCode(Long tenantId, String commandCode) {
        try {
            String sql = "SELECT model_code FROM ab_command_definition " +
                    "WHERE tenant_id = #{params.tenantId} " +
                    "AND code = #{params.commandCode} " +
                    "AND (deleted_flag = FALSE OR deleted_flag IS NULL) " +
                    "AND (is_current = TRUE OR is_current IS NULL) " +
                    "LIMIT 1";
            List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                    Map.of("tenantId", tenantId, "commandCode", commandCode));
            return firstString(rows, "model_code");
        } catch (Exception e) {
            log.warn("Failed to resolve command model for explicit tool {}: {}", commandCode, e.getMessage());
            return null;
        }
    }

    private String inferNamedQueryModelCode(Long tenantId, String queryCode) {
        try {
            String sql = "SELECT code FROM ab_meta_model " +
                    "WHERE tenant_id = #{params.tenantId} " +
                    "AND #{params.queryCode} LIKE code || '%' " +
                    "AND (deleted_flag = FALSE OR deleted_flag IS NULL) " +
                    "AND (is_current = TRUE OR is_current IS NULL) " +
                    "ORDER BY length(code) DESC " +
                    "LIMIT 1";
            List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                    Map.of("tenantId", tenantId, "queryCode", queryCode));
            return firstString(rows, "code");
        } catch (Exception e) {
            log.warn("Failed to infer named-query model for explicit tool {}: {}", queryCode, e.getMessage());
            return null;
        }
    }

    private String firstString(List<Map<String, Object>> rows, String key) {
        if (rows == null || rows.isEmpty() || rows.get(0) == null || rows.get(0).get(key) == null) {
            return null;
        }
        String value = String.valueOf(rows.get(0).get(key));
        return value.isBlank() ? null : value;
    }

    private String safeExceptionMessage(Exception e) {
        if (e == null) {
            return "Unknown error";
        }
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return LogSanitizer.safe(message);
    }
}
