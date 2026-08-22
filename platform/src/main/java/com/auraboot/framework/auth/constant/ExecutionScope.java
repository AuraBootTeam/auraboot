package com.auraboot.framework.auth.constant;

import java.util.Locale;

/** The authority boundary in which the current request executes. */
public enum ExecutionScope {
    PARTY,
    TENANT,
    PLATFORM,
    SYSTEM;

    public String getCode() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ExecutionScope fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return valueOf(code.trim().toUpperCase(Locale.ROOT));
    }
}
