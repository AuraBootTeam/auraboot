package com.auraboot.framework.agent.nlmodeling;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Normalizes obsolete dynamic-model permission prefixes in LLM-generated resources. */
final class NlModelingPermissionConformance {

    private static final String LEGACY_PREFIX = "dynamic.";
    private static final String CANONICAL_PREFIX = "model.";

    private NlModelingPermissionConformance() {
    }

    static void canonicalizeDefinitionCode(Map<String, Object> permission) {
        canonicalizeMapValue(permission, "code");
    }

    /** Normalize permission-bearing values recursively without rewriting ordinary resource codes. */
    @SuppressWarnings("unchecked")
    static void canonicalizeReferences(Object node) {
        if (node instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = (Map<String, Object>) rawMap;
            canonicalizeMapValue(map, "permissionCode");
            if (map.get("permissions") instanceof List<?> permissions) {
                List<Object> normalized = new ArrayList<>(permissions.size());
                for (Object permission : permissions) {
                    normalized.add(permission instanceof String code ? canonicalCode(code) : permission);
                }
                map.put("permissions", normalized);
            }
            map.values().forEach(NlModelingPermissionConformance::canonicalizeReferences);
        } else if (node instanceof List<?> list) {
            list.forEach(NlModelingPermissionConformance::canonicalizeReferences);
        }
    }

    private static void canonicalizeMapValue(Map<String, Object> map, String key) {
        if (map.get(key) instanceof String code) {
            map.put(key, canonicalCode(code));
        }
    }

    private static String canonicalCode(String code) {
        return code.startsWith(LEGACY_PREFIX)
                ? CANONICAL_PREFIX + code.substring(LEGACY_PREFIX.length()) : code;
    }
}

