package com.auraboot.framework.openplatform.service;

/** Stable public 412 boundary for missing, malformed, cross-resource, or stale If-Match tokens. */
public class OpenApiPreconditionException extends RuntimeException {
    public OpenApiPreconditionException(String message) {
        super(message);
    }

    public OpenApiPreconditionException(String message, Throwable cause) {
        super(message, cause);
    }
}
