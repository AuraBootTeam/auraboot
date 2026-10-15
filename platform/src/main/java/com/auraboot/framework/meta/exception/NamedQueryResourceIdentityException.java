package com.auraboot.framework.meta.exception;

/** The authorized virtual result does not contain exactly one non-null row per public PID. */
public final class NamedQueryResourceIdentityException extends IllegalStateException {
    public NamedQueryResourceIdentityException() {
        super("Virtual resource PID is missing or ambiguous");
    }
}
