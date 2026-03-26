package com.auraboot.framework.plugin.dto.uninstall;

/**
 * Decision options for handling modified resources during uninstall.
 */
public enum UninstallDecision {

    /**
     * Delete the resource even though it was modified.
     */
    DELETE("Delete the resource"),

    /**
     * Keep the resource and detach it from plugin management.
     * The resource will become USER_CLAIMED.
     */
    KEEP_AND_DETACH("Keep and detach from plugin"),

    /**
     * Skip this resource - let the user decide later.
     * This will abort the uninstall for resources without decisions.
     */
    SKIP("Skip (decide later)");

    private final String description;

    UninstallDecision(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
