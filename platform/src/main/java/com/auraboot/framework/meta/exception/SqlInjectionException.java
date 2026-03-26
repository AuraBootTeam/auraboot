package com.auraboot.framework.meta.exception;

/**
 * Thrown when SQL injection patterns are detected in user input.
 */
public class SqlInjectionException extends RuntimeException {
    public SqlInjectionException(String message) {
        super(message);
    }

    public SqlInjectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
