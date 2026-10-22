package com.auraboot.framework.agent.runtime.policy;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Default resolver for agent definition guardrails-backed profile policy.
 */
public enum DefaultAgentProfileResolver implements AgentProfileResolver {
    INSTANCE;

    /**
     * Operations that make an agent write-capable. Anything outside this set — "query" today —
     * leaves it read-only. Kept here rather than inferred from the string so that adding an
     * operation to the UI cannot quietly widen what an agent is allowed to do.
     */
    private static final Set<String> WRITE_OPERATIONS =
            Set.of("create", "update", "delete", "transition");

    @Override
    public AgentProfile resolve(ObjectMapper objectMapper, Map<String, Object> agentDefinition) {
        if (agentDefinition == null) {
            return new AgentProfile(null, null, AgentContextPolicy.defaults(), false);
        }
        Object rawGuardrails = agentDefinition.get("guardrails");
        Map<String, Object> guardrails = AgentProfilePermissionExtractor.guardrailsMap(objectMapper, rawGuardrails);
        Set<String> permissions = AgentProfilePermissionExtractor.extract(objectMapper, rawGuardrails);
        return new AgentProfile(
                stringValue(agentDefinition.get("agent_code")),
                permissions,
                contextPolicy(objectMapper, guardrails, agentDefinition),
                guardrails != null && Boolean.TRUE.equals(guardrails.get("evidenceFirst")));
    }

    /**
     * The ceiling an agent's stored {@code allowed_operations} implies, or null when the field says
     * nothing useful.
     *
     * <p>That column is what the "allowed operations" checkboxes write, and until now nothing read
     * it: clearing Delete saved, displayed as cleared, and changed nothing about what the agent
     * could do. A permission control that does not control anything is worse than none, because
     * someone configures it and believes the boundary is there.
     *
     * <p>The mapping goes through the capability ceiling that already governs every tool call
     * rather than adding a second enforcement path — an operation set with no write verbs is a
     * read-only agent, which is exactly what {@code READ_ONLY} already means. An empty set is left
     * alone: it reads as "not configured", not as "forbidden from everything", and treating it as
     * the latter would silently mute every agent whose row predates this field.
     */
    private ToolCapabilityCeiling ceilingFromAllowedOperations(ObjectMapper objectMapper,
                                                               Map<String, Object> agentDefinition) {
        // allowed_operations is persisted as a JSONB column, so the resolver receives it as a raw
        // JSON-array STRING (e.g. `["query","create","update","delete","transition"]`), not a parsed
        // List. The old stringSet() comma-split left the brackets/quotes attached to each token
        // (`["query`, `"create"`, ...), so NONE matched the clean write verbs and every agent whose
        // operations were stored that way was misread as READ_ONLY — denying its own write tools with
        // capability_ceiling_exceeded even though it was explicitly granted create/update/delete.
        // Parse it the same way AgentToolScopePolicy does so the derived ceiling agrees with the
        // tool-list filter that already reads the column correctly.
        Set<String> operations = operationSet(objectMapper, agentDefinition.get("allowed_operations"));
        if (operations.isEmpty()) {
            return null;
        }
        boolean writes = operations.stream()
                .map(op -> op.trim().toLowerCase(Locale.ROOT))
                .anyMatch(WRITE_OPERATIONS::contains);
        return writes ? ToolCapabilityCeiling.WRITE_CAPABLE : ToolCapabilityCeiling.READ_ONLY;
    }

    /**
     * Robustly parse the {@code allowed_operations} guardrail into a set of operation verbs. The
     * value can arrive as a real {@code Collection}, a JSON-array {@code String}, or — the form the
     * JSONB column actually deserializes to on the runtime read path — a {@code PGobject} (or any
     * other type) whose {@code toString()} is the JSON array text. Reducing every non-collection
     * value to its text form before parsing is what makes {@code ["query","create",...]} yield the
     * five verbs instead of one bracket-and-quote-laden token. A blank value or {@code "*"} means
     * "not configured" → empty set.
     */
    private Set<String> operationSet(ObjectMapper objectMapper, Object raw) {
        if (raw == null) {
            return Set.of();
        }
        if (raw instanceof Collection<?> collection) {
            LinkedHashSet<String> values = new LinkedHashSet<>();
            for (Object value : collection) {
                add(values, value);
            }
            return Set.copyOf(values);
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty() || "*".equals(text) || "null".equals(text)) {
            return Set.of();
        }
        if (text.startsWith("[") && objectMapper != null) {
            try {
                java.util.List<String> parsed = objectMapper.readValue(
                        text, new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>() {});
                LinkedHashSet<String> values = new LinkedHashSet<>();
                for (String value : parsed) {
                    add(values, value);
                }
                return Set.copyOf(values);
            } catch (Exception ignored) {
                // Fall through to comma-splitting for a non-JSON string.
            }
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String value : text.split(",")) {
            add(values, value);
        }
        return Set.copyOf(values);
    }

    @SuppressWarnings("unchecked")
    private AgentContextPolicy contextPolicy(ObjectMapper objectMapper,
                                             Map<String, Object> guardrails,
                                             Map<String, Object> agentDefinition) {
        ToolCapabilityCeiling operationCeiling = ceilingFromAllowedOperations(objectMapper, agentDefinition);
        if (guardrails == null || guardrails.isEmpty()) {
            return operationCeiling == null
                    ? AgentContextPolicy.defaults()
                    : new AgentContextPolicy(Set.<String>of(), false, operationCeiling, null, null);
        }
        Object rawPolicy = guardrails.get("contextPolicy");
        if (rawPolicy instanceof Map<?, ?> rawMap) {
            Map<String, Object> policy = (Map<String, Object>) rawMap;
            return new AgentContextPolicy(
                    stringSet(firstNonNull(policy.get("scopes"), policy.get("contextScopes"))),
                    booleanValue(firstNonNull(policy.get("allowSensitiveContext"), policy.get("allowSensitive"))),
                    // An explicitly written ceiling wins. Someone who spelled it out in guardrails
                    // meant it, and deriving over the top would make their setting unexplainable.
                    ceilingOrDerived(
                            enumValue(ToolCapabilityCeiling.class,
                                    firstNonNull(policy.get("capabilityCeiling"), policy.get("capability"))),
                            operationCeiling),
                    enumValue(ToolExposure.class, policy.get("toolExposure")),
                    enumValue(DurabilityPreference.class, policy.get("durabilityPreference")));
        }
        return new AgentContextPolicy(
                stringSet(firstNonNull(guardrails.get("contextScopes"), guardrails.get("scopes"))),
                booleanValue(firstNonNull(guardrails.get("allowSensitiveContext"), guardrails.get("allowSensitive"))),
                ceilingOrDerived(
                        enumValue(ToolCapabilityCeiling.class,
                                firstNonNull(guardrails.get("capabilityCeiling"), guardrails.get("capability"))),
                        operationCeiling),
                enumValue(ToolExposure.class, guardrails.get("toolExposure")),
                enumValue(DurabilityPreference.class, guardrails.get("durabilityPreference")));
    }

    private Set<String> stringSet(Object raw) {
        if (raw == null) {
            return Set.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (raw instanceof Collection<?> collection) {
            for (Object value : collection) {
                add(values, value);
            }
        } else if (raw instanceof String text) {
            for (String value : text.split(",")) {
                add(values, value);
            }
        } else {
            add(values, raw);
        }
        return Set.copyOf(values);
    }

    private void add(Set<String> values, Object raw) {
        if (raw == null) {
            return;
        }
        String value = String.valueOf(raw).trim();
        if (!value.isBlank()) {
            values.add(value);
        }
    }

    private boolean booleanValue(Object raw) {
        if (raw instanceof Boolean value) {
            return value;
        }
        return raw != null && Boolean.parseBoolean(String.valueOf(raw));
    }

    private <E extends Enum<E>> E enumValue(Class<E> enumType, Object raw) {
        if (enumType == null || raw == null) {
            return null;
        }
        String value = String.valueOf(raw).trim();
        if (value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(enumType, value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /** An explicit ceiling wins over one derived from allowed_operations. */
    private ToolCapabilityCeiling ceilingOrDerived(ToolCapabilityCeiling explicit,
                                                   ToolCapabilityCeiling derived) {
        return explicit != null ? explicit : derived;
    }

    private Object firstNonNull(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String stringValue(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw);
        return value.isBlank() ? null : value;
    }
}
