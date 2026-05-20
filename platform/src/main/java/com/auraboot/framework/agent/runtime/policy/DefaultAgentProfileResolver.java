package com.auraboot.framework.agent.runtime.policy;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Default resolver for agent definition guardrails-backed profile policy.
 */
public enum DefaultAgentProfileResolver implements AgentProfileResolver {
    INSTANCE;

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
                contextPolicy(guardrails),
                guardrails != null && Boolean.TRUE.equals(guardrails.get("evidenceFirst")));
    }

    @SuppressWarnings("unchecked")
    private AgentContextPolicy contextPolicy(Map<String, Object> guardrails) {
        if (guardrails == null || guardrails.isEmpty()) {
            return AgentContextPolicy.defaults();
        }
        Object rawPolicy = guardrails.get("contextPolicy");
        if (rawPolicy instanceof Map<?, ?> rawMap) {
            Map<String, Object> policy = (Map<String, Object>) rawMap;
            return new AgentContextPolicy(
                    stringSet(firstNonNull(policy.get("scopes"), policy.get("contextScopes"))),
                    booleanValue(firstNonNull(policy.get("allowSensitiveContext"), policy.get("allowSensitive"))),
                    enumValue(ToolCapabilityCeiling.class, firstNonNull(policy.get("capabilityCeiling"), policy.get("capability"))),
                    enumValue(ToolExposure.class, policy.get("toolExposure")),
                    enumValue(DurabilityPreference.class, policy.get("durabilityPreference")));
        }
        return new AgentContextPolicy(
                stringSet(firstNonNull(guardrails.get("contextScopes"), guardrails.get("scopes"))),
                booleanValue(firstNonNull(guardrails.get("allowSensitiveContext"), guardrails.get("allowSensitive"))),
                enumValue(ToolCapabilityCeiling.class, firstNonNull(guardrails.get("capabilityCeiling"), guardrails.get("capability"))),
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
