package com.auraboot.framework.meta.service.impl;

import java.util.LinkedHashMap;
import java.util.Map;

public final class CommandAuditPayloads {

    public static final String AUDIT_CONTEXT_KEY = "__auditContext";

    private CommandAuditPayloads() {
    }

    public static Map<String, Object> withAuditContext(
            Map<String, Object> payload,
            Map<String, Object> auditContext) {
        if ((auditContext == null || auditContext.isEmpty()) && payload == null) {
            return null;
        }

        Map<String, Object> auditPayload = new LinkedHashMap<>();
        if (payload != null) {
            payload.forEach((key, value) -> {
                if (!AUDIT_CONTEXT_KEY.equals(key)) {
                    auditPayload.put(key, value);
                }
            });
        }
        if (auditContext != null && !auditContext.isEmpty()) {
            auditPayload.put(AUDIT_CONTEXT_KEY, new LinkedHashMap<>(auditContext));
        }
        return auditPayload;
    }
}
