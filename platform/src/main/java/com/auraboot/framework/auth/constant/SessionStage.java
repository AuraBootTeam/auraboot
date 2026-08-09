package com.auraboot.framework.auth.constant;

import java.util.Locale;

/** Explicit authentication/onboarding stage; a stage is not a permission. */
public enum SessionStage {
    ONBOARDING,
    ACTOR_SELECTION,
    READY,
    PLATFORM,
    TENANT_ADMIN;

    public String getCode() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static SessionStage fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return valueOf(code.trim().toUpperCase(Locale.ROOT));
    }
}
