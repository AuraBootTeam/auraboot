package com.auraboot.framework.bpm.enums;

/**
 * Storage mode for process execution.
 * Determines how SmartEngine persists execution state.
 */
public enum StorageMode {

    /** Full persistence to PostgreSQL (default for approval workflows) */
    DATABASE,

    /** In-memory execution via custom storage (for automation/orchestration) */
    CUSTOM,

    /** Write to both database and custom storage (migration/debug) */
    DUAL_WRITE
}
