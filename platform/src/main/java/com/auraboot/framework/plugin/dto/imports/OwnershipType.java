package com.auraboot.framework.plugin.dto.imports;

/**
 * Ownership types for plugin-managed resources.
 * Database values are lowercase (matching CHECK constraint).
 */
public enum OwnershipType {
    PLUGIN_OWNED("Plugin fully controls this resource"),
    SHARED("Plugin created, user can customize"),
    USER_CLAIMED("User has taken ownership");

    private final String description;

    OwnershipType(String description) {
        this.description = description;
    }

    /** Lowercase code for database storage. */
    public String code() {
        return name().toLowerCase();
    }

    public String getDescription() { return description; }

    public boolean allowsUserModification() {
        return this == SHARED || this == USER_CLAIMED;
    }

    public boolean deleteOnUninstall() {
        return this == PLUGIN_OWNED;
    }

    public boolean isManagedByPlugin() {
        return this == PLUGIN_OWNED || this == SHARED;
    }

    public boolean needsUserDecisionOnUninstall() {
        return this == SHARED;
    }

    public static OwnershipType fromCode(String code) {
        if (code == null) return null;
        for (OwnershipType t : values()) {
            if (t.code().equalsIgnoreCase(code)) return t;
        }
        return valueOf(code.toUpperCase());
    }
}
