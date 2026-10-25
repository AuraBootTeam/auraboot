package com.auraboot.framework.agent.identity;

/**
 * Raised when a requested execution identity is incomplete or inconsistent.
 *
 * <p>Principal resolution is a security boundary, so these cases fail closed
 * instead of falling back to the initiating user's authority.
 */
public class ExecutionPrincipalResolutionException extends RuntimeException {

    public enum Reason {
        AGENT_MISSING,
        AGENT_INACTIVE,
        INVOCATION_DENIED,
        CONFIGURATION_INVALID
    }

    private final Reason reason;

    public ExecutionPrincipalResolutionException(String message) {
        this(Reason.CONFIGURATION_INVALID, message, null);
    }

    public ExecutionPrincipalResolutionException(String message, Throwable cause) {
        this(Reason.CONFIGURATION_INVALID, message, cause);
    }

    public ExecutionPrincipalResolutionException(Reason reason, String message) {
        this(reason, message, null);
    }

    public ExecutionPrincipalResolutionException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason == null ? Reason.CONFIGURATION_INVALID : reason;
    }

    public Reason reason() {
        return reason;
    }
}
