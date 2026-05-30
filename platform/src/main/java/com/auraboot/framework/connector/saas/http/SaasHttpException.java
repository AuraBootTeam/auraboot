package com.auraboot.framework.connector.saas.http;

/**
 * Thrown by {@link SaasHttpClient} when an HTTP round-trip cannot produce a
 * useful response (transport failure, JSON parse failure, retries exhausted).
 *
 * <p>Vendor adapters typically catch this and translate it into either a
 * vendor-specific {@code ConnectorException} or a logged warning followed by
 * an empty stream — depending on whether the failure is fatal to the sync run
 * or just a transient page fetch.
 */
public class SaasHttpException extends RuntimeException {

    private final int statusCode;

    public SaasHttpException(String message) {
        super(message);
        this.statusCode = -1;
    }

    public SaasHttpException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
    }

    public SaasHttpException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public int statusCode() { return statusCode; }
}
