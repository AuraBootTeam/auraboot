package com.auraboot.framework.plugin.extension;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Tenant-bound, read-only data access for an explicitly authorized projection command.
 *
 * <p>The host exposes this capability only when a command declares
 * {@value #OPT_IN_HANDLER_PARAM} as the boolean value {@code true} in its handler parameters.
 * It bypasses the caller's raw model-read permissions so a trusted handler can return a smaller,
 * sanitized projection. The command permission remains the public authorization boundary.
 *
 * <p>This contract intentionally has no mutation methods and no tenant argument. The host binds
 * every read to the command tenant, preventing a plugin from selecting another tenant.
 */
public interface TenantProjectionAccessor {

    String SETTINGS_KEY = "__tenantProjectionAccessor";
    String OPT_IN_HANDLER_PARAM = "tenantProjectionRead";

    Map<String, Object> getById(String modelCode, String recordId);

    List<Map<String, Object>> query(String modelCode, Map<String, Object> filters);

    default List<Map<String, Object>> queryIn(String modelCode, String fieldName,
                                              Collection<?> values) {
        if (fieldName == null || fieldName.isBlank()) {
            throw new IllegalArgumentException("fieldName cannot be null or blank");
        }
        if (values == null || values.isEmpty()) return List.of();
        LinkedHashSet<Object> distinct = new LinkedHashSet<>();
        values.stream().filter(java.util.Objects::nonNull).forEach(distinct::add);
        if (distinct.isEmpty()) return List.of();
        List<Map<String, Object>> records = new ArrayList<>();
        for (Object value : distinct) {
            records.addAll(query(modelCode, Map.of(fieldName, value)));
        }
        return records;
    }
}
