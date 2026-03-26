package com.auraboot.framework.bpm.enums;

/**
 * Trigger types for process orchestration.
 */
public enum TriggerType {

    /** Cron-based scheduled trigger */
    SCHEDULED,

    /** Event bus trigger */
    EVENT,

    /** HTTP webhook trigger */
    WEBHOOK,

    /** Manual trigger (for testing or ad-hoc execution) */
    MANUAL
}
