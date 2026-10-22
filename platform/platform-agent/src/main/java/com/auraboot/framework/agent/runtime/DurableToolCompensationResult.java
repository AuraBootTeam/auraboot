package com.auraboot.framework.agent.runtime;

/**
 * Result returned by a domain compensation handler.
 */
public record DurableToolCompensationResult(
        boolean compensated,
        String rawResult,
        String message) {
}
