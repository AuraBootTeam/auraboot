package com.auraboot.framework.bpm.enums;

/**
 * Event types recorded in execution logs.
 */
public enum ExecutionEventType {

    /** Node started execution */
    NODE_START,

    /** Node completed successfully */
    NODE_COMPLETE,

    /** Node execution failed */
    NODE_FAILURE,

    /** Execution state changed (pause/resume/cancel) */
    STATE_CHANGE
}
