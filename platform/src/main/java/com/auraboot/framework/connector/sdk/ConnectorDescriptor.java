package com.auraboot.framework.connector.sdk;

import java.util.List;

/**
 * Static metadata about a connector adapter (returned by
 * {@link ConnectorAdapter#descriptor()}). Used by the admin UI to list
 * available connector types and the endpoint codes each one accepts.
 *
 * @param protocolType            stable key, e.g. "http"
 * @param description             human description
 * @param supportedEndpointCodes  endpoint codes this protocol natively recognises
 * @since 5.2.0
 */
public record ConnectorDescriptor(
        String protocolType,
        String description,
        List<String> supportedEndpointCodes
) {
}
