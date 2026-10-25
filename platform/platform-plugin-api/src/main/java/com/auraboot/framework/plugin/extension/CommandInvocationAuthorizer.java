package com.auraboot.framework.plugin.extension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Optional handler contract for domain authorization before native idempotency lookup, including creates. */
public interface CommandInvocationAuthorizer {
    /** Payload values are untrusted assertions; implementations must resolve current domain facts. */
    boolean authorizeInvocation(InvocationContext context);

    /** Trusted host identity and immutable input snapshot. This contract grants no generic model access. */
    record InvocationContext(Long tenantId, Long memberId, String currentUserPid, String commandCode,
            String handlerCode, String modelCode, String targetRecordPid, Map<String, Object> payload) {
        public InvocationContext {
            payload = immutableMap(payload == null ? Map.of() : payload);
        }
    }

    private static Map<String, Object> immutableMap(Map<String, Object> values) {
        var copy = new LinkedHashMap<String, Object>();
        values.forEach((key, value) -> copy.put(key, immutableValue(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            var copy = new LinkedHashMap<Object, Object>();
            map.forEach((key, nested) -> copy.put(key, immutableValue(nested)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            var copy = new ArrayList<Object>();
            list.forEach(nested -> copy.add(immutableValue(nested)));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}
