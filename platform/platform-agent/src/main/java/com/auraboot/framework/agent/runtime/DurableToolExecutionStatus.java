package com.auraboot.framework.agent.runtime;

/**
 * Durable terminal state for a direct tool execution boundary.
 */
public enum DurableToolExecutionStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
    COMPENSATION_REQUIRED,
    COMPENSATED
}
