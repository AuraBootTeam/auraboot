package com.auraboot.framework.exception;

/**
 * Thrown when an optimistic lock conflict is detected (HTTP 409 Conflict).
 * Example: saving a page schema with an outdated row_version.
 */
public class ConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConflictException(String message) {
        super(message);
    }

    public ConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
