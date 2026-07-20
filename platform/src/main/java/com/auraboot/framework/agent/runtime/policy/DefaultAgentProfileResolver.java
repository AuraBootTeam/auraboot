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
                contextPolicy(guardrails, agentDefinition),
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
    private ToolCapabilityCeiling ceilingFromAllowedOperations(Map<String, Object> agentDefinition) {
        Set<String> operations = stringSet(agentDefinition.get("allowed_operations"));
        if (operations.isEmpty()) {
            return null;
        }
        boolean writes = operations.stream()
                .map(op -> op.trim().toLowerCase(Locale.ROOT))
                .anyMatch(op -> WRITE_OPERATIONS.contains(op));
        return writes ? ToolCapabilityCeiling.WRITE_CAPABLE : ToolCapabilityCeiling.READ_ONLY;
    }

    @SuppressWarnings("unchecked")
    private AgentContextPolicy contextPolicy(Map<String, Object> guardrails, Map<String, Object> agentDefinition) {
        ToolCapabilityCeiling operationCeiling = ceilingFromAllowedOperations(agentDefinition);
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
