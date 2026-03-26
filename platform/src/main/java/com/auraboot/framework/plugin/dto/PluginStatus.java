package com.auraboot.framework.plugin.dto;

/**
 * Plugin lifecycle status enumeration.
 *
 * State transitions:
 * INSTALLED -> ENABLED <-> DISABLED -> (uninstall)
 */
public enum PluginStatus {

    /**
     * Plugin is installed but not yet enabled.
     * Initial state after installation.
     */
    INSTALLED("installed"),

    /**
     * Plugin is enabled and active.
     * Plugin callbacks and services are running.
     */
    ENABLED("enabled"),

    /**
     * Plugin is disabled.
     * Plugin callbacks are stopped, but data is preserved.
     */
    DISABLED("disabled"),

    /**
     * Plugin is in a failed state.
     * An error occurred during lifecycle transition.
     */
    FAILED("failed");

    private final String code;

    PluginStatus(String code) {
        this.code = code;
    }

    /**
     * Returns the lowercase database value.
     */
    public String code() {
        return code;
    }

    /**
     * Parse from database value (case-insensitive).
     */
    public static PluginStatus fromCode(String code) {
        if (code == null) return null;
        for (PluginStatus s : values()) {
            if (s.code.equalsIgnoreCase(code)) return s;
        }
        return valueOf(code.toUpperCase());
    }

    /**
     * Check if transition to target status is valid.
     *
     * @param target target status
     * @return true if transition is allowed
     */
    public boolean canTransitionTo(PluginStatus target) {
        return switch (this) {
            case INSTALLED -> target == ENABLED || target == FAILED;
            case ENABLED -> target == DISABLED || target == FAILED;
            case DISABLED -> target == ENABLED || target == FAILED;
            case FAILED -> target == DISABLED || target == ENABLED;
        };
    }

    /**
     * Check if plugin is in an active state (can be disabled).
     */
    public boolean isActive() {
        return this == ENABLED;
    }

    /**
     * Check if plugin can be uninstalled.
     * Only INSTALLED or DISABLED plugins can be uninstalled.
     */
    public boolean canUninstall() {
        return this == INSTALLED || this == DISABLED;
    }
}
