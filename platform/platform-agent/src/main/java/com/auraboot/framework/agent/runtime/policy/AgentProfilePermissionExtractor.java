package com.auraboot.framework.agent.runtime.policy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class AgentProfilePermissionExtractor {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private AgentProfilePermissionExtractor() {
    }

    public static Set<String> extract(ObjectMapper objectMapper, Object rawGuardrails) {
        Map<String, Object> guardrails = guardrailsMap(objectMapper, rawGuardrails);
        if (guardrails == null || guardrails.isEmpty()) {
            return null;
        }
        Object rawPermissions = firstNonNull(
                guardrails.get("profilePermissions"),
                guardrails.get("toolPermissions"),
                guardrails.get("permissions"));
        return permissionSet(rawPermissions);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> guardrailsMap(ObjectMapper objectMapper, Object rawGuardrails) {
        if (rawGuardrails == null) {
            return null;
        }
        if (rawGuardrails instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        String text = String.valueOf(rawGuardrails);
        if (text.isBlank()) {
            return null;
        }
        try {
            ObjectMapper mapper = objectMapper != null ? objectMapper : new ObjectMapper();
            return mapper.readValue(text, MAP_TYPE);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private static Set<String> permissionSet(Object rawPermissions) {
        if (rawPermissions == null) {
            return null;
        }
        LinkedHashSet<String> permissions = new LinkedHashSet<>();
        if (rawPermissions instanceof Collection<?> values) {
            for (Object value : values) {
                addPermission(permissions, value);
            }
        } else if (rawPermissions instanceof String text) {
            for (String value : text.split(",")) {
                addPermission(permissions, value);
            }
        } else {
            addPermission(permissions, rawPermissions);
        }
        return Set.copyOf(permissions);
    }

    private static void addPermission(Set<String> permissions, Object value) {
        if (value == null) {
            return;
        }
        String permission = String.valueOf(value).trim();
        if (!permission.isBlank()) {
            permissions.add(permission);
        }
    }

    private static Object firstNonNull(Object... values) {
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
}
