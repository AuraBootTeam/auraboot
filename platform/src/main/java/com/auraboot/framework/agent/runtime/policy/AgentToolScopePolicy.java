package com.auraboot.framework.agent.runtime.policy;

import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.provider.ToolDefinition;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Enforces an agent definition's data-domain guardrails at tool-assembly time:
 * {@code allowed_models} (which dynamic models the agent may touch) and
 * {@code allowed_operations} (which operation kinds it may perform).
 *
 * <p>Assembly-time filtering IS the enforcement boundary for both engines: the
 * shared executor ({@code ToolLoopService}) rejects any call whose tool name is
 * not in the assembled list, and the chat runtime only offers the assembled
 * list to the model — so a tool dropped here is a tool the agent cannot call.
 * Before this policy both columns were write-only (B4, quality-state
 * 2026-07-20): clearing "delete" saved, displayed as cleared, and changed
 * nothing.
 *
 * <p>Restriction wins over declaration: an explicitly declared tool that falls
 * outside {@code allowed_models} is still dropped, because the guardrail is
 * the boundary an admin believes is in force.
 *
 * <p>Semantics of the stored values (kept compatible with the wizard and with
 * rows that predate the columns): {@code null}, {@code "*"} and an empty list
 * all mean "not configured" — no restriction. Only a non-empty list restricts.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentToolScopePolicy {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    /** Operation vocabulary written by the colleague wizard checkboxes. */
    private static final Set<String> WRITE_OPERATIONS = Set.of("create", "update", "delete", "transition");
    /** Tool types that target dynamic models and are therefore governed by this policy. */
    private static final Set<String> MODEL_SCOPED_TOOL_TYPES = Set.of("dsl_command", "dsl_query");

    private final ObjectMapper objectMapper;

    /** Parsed guardrail scope; a {@code null} set means "that axis is unrestricted". */
    public record Scope(Set<String> allowedModels, Set<String> allowedOperations) {
        public boolean unrestricted() {
            return allowedModels == null && allowedOperations == null;
        }
    }

    public Scope scopeOf(Map<String, Object> agentDef) {
        if (agentDef == null) {
            return new Scope(null, null);
        }
        return new Scope(
                parseList(agentDef.get("allowed_models")),
                normalizeOps(parseList(agentDef.get("allowed_operations"))));
    }

    public List<ToolDefinition> filterDefinitions(Scope scope, List<ToolDefinition> tools, String agentCode) {
        if (scope == null || scope.unrestricted() || tools == null || tools.isEmpty()) {
            return tools;
        }
        List<ToolDefinition> kept = tools.stream()
                .filter(t -> t == null || allows(scope,
                        t.getModelCode(), t.getSourceCode(), t.getToolType(), t.getOperationKind(), t.getToolCode()))
                .toList();
        logDropped(agentCode, tools.size(), kept.size());
        return kept;
    }

    public List<AgentToolDefinition> filterAgentTools(Scope scope, List<AgentToolDefinition> tools, String agentCode) {
        if (scope == null || scope.unrestricted() || tools == null || tools.isEmpty()) {
            return tools;
        }
        List<AgentToolDefinition> kept = tools.stream()
                .filter(t -> t == null || allows(scope,
                        t.getModelCode(), t.getSourceCode(), t.getToolType(), t.getOperationKind(), t.getName()))
                .toList();
        logDropped(agentCode, tools.size(), kept.size());
        return kept;
    }

    /**
     * Whether one tool survives the scope. Non-model tools (platform / custom /
     * mcp / api_call / llm_native) pass both axes untouched: the columns govern
     * dynamic-model data access, and those tools are governed by the capability
     * ceiling, tool ACL and approval gate instead.
     */
    boolean allows(Scope scope, String modelCode, String sourceCode, String toolType,
                   String operationKind, String toolCode) {
        boolean modelScoped = toolType != null && MODEL_SCOPED_TOOL_TYPES.contains(toolType);
        if (!modelScoped && modelCode == null) {
            return true;
        }
        return modelAllowed(scope.allowedModels(), modelCode, sourceCode)
                && operationAllowed(scope.allowedOperations(), operationKind, toolType, toolCode, sourceCode);
    }

    private boolean modelAllowed(Set<String> allowedModels, String modelCode, String sourceCode) {
        if (allowedModels == null) {
            return true;
        }
        if (modelCode != null && !modelCode.isBlank()) {
            return allowedModels.contains(modelCode);
        }
        if (sourceCode == null || sourceCode.isBlank()) {
            return true;
        }
        // Legacy fallback for tools assembled without a stamped model code
        // (e.g. skill-resolved tools): prefix matching in both directions, the
        // same shape the original (never-wired) enforceModelScope used.
        String pluginPrefix = sourceCode.contains(":") ? sourceCode.substring(0, sourceCode.indexOf(':')) : null;
        return allowedModels.stream().anyMatch(m ->
                sourceCode.startsWith(m) || (pluginPrefix != null && m.startsWith(pluginPrefix)));
    }

    private boolean operationAllowed(Set<String> allowedOps, String operationKind, String toolType,
                                     String toolCode, String sourceCode) {
        if (allowedOps == null) {
            return true;
        }
        String kind = operationKind != null ? normalizeKind(operationKind)
                : inferKind(toolType, toolCode, sourceCode);
        if ("query".equals(kind)) {
            return allowedOps.contains("query");
        }
        if (kind != null && WRITE_OPERATIONS.contains(kind)) {
            return allowedOps.contains(kind);
        }
        // Unknown or unmapped write kind (e.g. "automate", or a command whose
        // verb we cannot classify): deny only when the agent has no write verb
        // at all — the same read-only semantics the capability ceiling applies.
        return allowedOps.stream().anyMatch(WRITE_OPERATIONS::contains);
    }

    private String normalizeKind(String raw) {
        String kind = raw.trim().toLowerCase(Locale.ROOT);
        return "state_transition".equals(kind) ? "transition" : kind;
    }

    /** Best-effort operation classification for tools without a stamped kind. */
    private String inferKind(String toolType, String toolCode, String sourceCode) {
        if ("dsl_query".equals(toolType)) {
            return "query";
        }
        String code = toolCode != null ? toolCode : sourceCode;
        if (code == null) {
            return null;
        }
        String local = code.toLowerCase(Locale.ROOT);
        if (local.startsWith("list:") || local.startsWith("get:") || local.startsWith("nq:")) {
            return "query";
        }
        int colon = local.lastIndexOf(':');
        String verb = colon >= 0 ? local.substring(colon + 1) : local;
        if (verb.startsWith("create") || verb.startsWith("add")) {
            return "create";
        }
        if (verb.startsWith("update") || verb.startsWith("edit")) {
            return "update";
        }
        if (verb.startsWith("delete") || verb.startsWith("remove")) {
            return "delete";
        }
        if (verb.startsWith("transition") || verb.startsWith("set_status") || verb.startsWith("change_status")) {
            return "transition";
        }
        if (verb.startsWith("list") || verb.startsWith("get") || verb.startsWith("search")
                || verb.startsWith("detail") || verb.startsWith("count")) {
            return "query";
        }
        return null;
    }

    private Set<String> parseList(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.isEmpty() || "*".equals(trimmed)) {
                return null;
            }
            try {
                List<String> parsed = objectMapper.readValue(trimmed, STRING_LIST);
                return parsed.isEmpty() ? null : new LinkedHashSet<>(parsed);
            } catch (Exception e) {
                log.warn("Unparseable agent scope column ignored (treated as unrestricted): {}", trimmed);
                return null;
            }
        }
        if (raw instanceof List<?> list) {
            Set<String> values = new LinkedHashSet<>();
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    values.add(String.valueOf(item));
                }
            }
            return values.isEmpty() ? null : values;
        }
        return null;
    }

    private Set<String> normalizeOps(Set<String> ops) {
        if (ops == null) {
            return null;
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String op : ops) {
            normalized.add(normalizeKind(op));
        }
        return normalized;
    }

    private void logDropped(String agentCode, int before, int after) {
        if (after < before) {
            log.info("Agent {} tool scope dropped {} of {} tool(s) outside allowed_models/allowed_operations",
                    agentCode, before - after, before);
        }
    }
}
