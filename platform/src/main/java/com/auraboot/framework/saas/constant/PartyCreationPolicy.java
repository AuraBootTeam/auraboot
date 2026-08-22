package com.auraboot.framework.saas.constant;

import java.util.Locale;

/** Controls the initial lifecycle applied when a business participant creates a Party. */
public enum PartyCreationPolicy {
    DISABLED,
    APPROVAL_REQUIRED,
    AUTO_APPROVE;

    public static PartyCreationPolicy fromConfig(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Party creation policy is required");
        }
        return valueOf(value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
    }

    public String getCode() {
        return name().toLowerCase(Locale.ROOT);
    }
}
