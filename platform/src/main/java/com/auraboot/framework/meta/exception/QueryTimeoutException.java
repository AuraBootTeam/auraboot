package com.auraboot.framework.meta.exception;

/**
 * Thrown when a query exceeds the configured timeout threshold.
 */
public class QueryTimeoutException extends RuntimeException {
    public QueryTimeoutException(String message) {
        super(message);
    }

    public QueryTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
