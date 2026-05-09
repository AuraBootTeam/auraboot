package com.auraboot.framework.connector.sdk;

/**
 * Result envelope from a connector invocation. Keeps the SDK call site
 * exception-free so the registry can route uniformly across protocols.
 *
 * @param success       true when the call succeeded
 * @param data          payload (typically a {@code Map<String,Object>}); null on failure
 * @param errorMessage  non-null on failure
 * @since 5.2.0
 */
public record ConnectorInvocationResult(
        boolean success,
        Object data,
        String errorMessage
) {
    public static ConnectorInvocationResult success(Object data) {
        return new ConnectorInvocationResult(true, data, null);
    }

    public static ConnectorInvocationResult failure(String errorMessage) {
        return new ConnectorInvocationResult(false, null, errorMessage);
    }
}
