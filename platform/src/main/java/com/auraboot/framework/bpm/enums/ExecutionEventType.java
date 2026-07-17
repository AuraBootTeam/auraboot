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

    /** Rule binding evaluated for a BPM node */
    RULE_EVALUATED,

    /** Rule Center action executed from a BPM serviceTask */
    ACTION_EXECUTED,

    /** Execution state changed (pause/resume/cancel) */
    STATE_CHANGE
}
