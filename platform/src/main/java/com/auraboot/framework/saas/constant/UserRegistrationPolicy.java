package com.auraboot.framework.saas.constant;

import java.util.Locale;

/** Controls whether a deployment can create a new user identity from a public login flow. */
public enum UserRegistrationPolicy {
    OPEN,
    INVITE_ONLY,
    CLOSED;

    public static UserRegistrationPolicy fromConfig(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("User registration policy is required");
        }
        return valueOf(value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
    }

    public String getCode() {
        return name().toLowerCase(Locale.ROOT);
    }
}
