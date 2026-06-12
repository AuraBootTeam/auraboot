package com.auraboot.framework.meta.ai;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Server-side guard for the AI field-lock feature (D5).
 *
 * <p>A form field marked AI-locked (authored in the unified designer's field
 * inspector via {@code props.aiLocked}) must never be overwritten by an AI fill.
 * The client skips locked fields when applying an AI-returned field map, and the
 * AI-fill banner forwards the locked field codes to the server; endpoints use
 * this helper to strip those codes from a generated field map before persisting
 * or returning it, so the lock holds regardless of what the model produced.
 */
public final class AiFieldLockSupport {

    private AiFieldLockSupport() {}

    /**
     * Return a copy of {@code fields} with every entry whose key is in
     * {@code lockedFieldCodes} removed. The input map is never mutated. Null or
     * empty inputs are handled safely: a null {@code fields} yields an empty map,
     * and a null/empty lock set returns {@code fields} unchanged.
     */
    public static Map<String, Object> stripLockedFields(
            Map<String, Object> fields, Collection<String> lockedFieldCodes) {
        if (fields == null) {
            return Map.of();
        }
        if (lockedFieldCodes == null || lockedFieldCodes.isEmpty() || fields.isEmpty()) {
            return fields;
        }
        Set<String> locked = new HashSet<>(lockedFieldCodes);
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            if (!locked.contains(entry.getKey())) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }
}
