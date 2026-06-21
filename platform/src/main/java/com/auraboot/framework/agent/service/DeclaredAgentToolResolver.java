package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.provider.ToolDefinition;
import com.auraboot.framework.agent.provider.ToolDiscoveryContext;
import com.auraboot.framework.agent.provider.ToolProviderRegistry;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    private final ToolProviderRegistry toolProviderRegistry;
    private final DynamicDataMapper dynamicDataMapper;
    private final ObjectMapper objectMapper;

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
}
