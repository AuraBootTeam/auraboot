package com.auraboot.framework.plugin.dto.imports;

/**
 * Status of a plugin import operation.
 * Database values are lowercase (e.g., "pending", "importing").
 */
public enum ImportStatus {
    PENDING("pending"),
    PARSING("parsing"),
    VALIDATING("validating"),
    PREVIEWING("previewing"),
    IMPORTING("importing"),
    SUCCESS("success"),
    FAILED("failed"),
    ROLLED_BACK("rolled_back"),
    CANCELLED("cancelled");

    private final String code;

    ImportStatus(String code) {
        this.code = code;
    }

    /**
     * Returns the lowercase database value.
     */
    public String code() {
        return code;
    }

    /**
     * Check if this status represents a terminal state.
     */
    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == ROLLED_BACK || this == CANCELLED;
    }

    /**
     * Check if this status allows rollback.
     */
    public boolean canRollback() {
        return this == SUCCESS;
    }

    /**
     * Check if this status is in progress.
     */
    public boolean isInProgress() {
        return this == PARSING || this == VALIDATING || this == PREVIEWING || this == IMPORTING;
    }

    /**
     * Parse from database value (case-insensitive).
     */
    public static ImportStatus fromCode(String code) {
        if (code == null) return null;
        for (ImportStatus s : values()) {
            if (s.code.equalsIgnoreCase(code)) return s;
        }
        return valueOf(code.toUpperCase());
    }
}
