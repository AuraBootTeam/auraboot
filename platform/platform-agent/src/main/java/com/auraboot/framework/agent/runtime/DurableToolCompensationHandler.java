package com.auraboot.framework.agent.runtime;

/**
 * Extension point for domain-specific compensation of failed external tool executions.
 */
public interface DurableToolCompensationHandler {

    boolean supports(DurableToolExecutionRecord record);

    DurableToolCompensationResult compensate(DurableToolExecutionRecord record);
}
