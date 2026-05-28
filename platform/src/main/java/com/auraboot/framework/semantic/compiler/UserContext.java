package com.auraboot.framework.semantic.compiler;

import java.util.Collections;
import java.util.Map;

/**
 * Minimal user context used by the semantic compiler for RLS injection.
 *
 * <p>{@link #attributes} is a flat key→value map of user_attribute codes to their
 * scalar string value (e.g. {@code "department_code" -> "FIN"}). Multi-value
 * attributes are serialised as comma-separated strings and rendered with
 * {@code IN (?, ?, ...)} expansion by {@link AccessPolicyCompiler}.
 *
 * <p>The platform's existing {@code UserAttribute} subsystem is the source of
 * this map; the compiler does not touch the DB.
 */
public record UserContext(Long userId, Long tenantId, Map<String, String> attributes) {

    public UserContext {
        if (attributes == null) {
            attributes = Collections.emptyMap();
        }
    }

    public String attribute(String key) {
        return attributes.get(key);
    }
}
