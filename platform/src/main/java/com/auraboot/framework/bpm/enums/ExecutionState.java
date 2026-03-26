package com.auraboot.framework.bpm.enums;

/**
 * Execution lifecycle states for orchestrated processes.
 */
public enum ExecutionState {

    /** Execution is actively running */
    RUNNING,

    /** Execution is paused at a specific node */
    PAUSED,

    /** Execution completed successfully */
    COMPLETED,

    /** Execution was cancelled by user */
    CANCELLED,

    /** Execution failed due to an error */
    FAILED
}
