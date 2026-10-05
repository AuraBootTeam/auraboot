package com.auraboot.framework.plugin.extension;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** One row in an atomic control-state batch; null expected values are supported. */
public record CompareAndSetUpdate(String recordId, Object expectedValue, Map<String, Object> nextValues) {
    public CompareAndSetUpdate {
        if (recordId == null || recordId.isBlank() || nextValues == null || nextValues.isEmpty()) {
            throw new IllegalArgumentException("CAS requires a record ID and next values");
        }
        nextValues = Collections.unmodifiableMap(new LinkedHashMap<>(nextValues));
    }
}
