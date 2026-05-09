package com.auraboot.framework.connector.sdk;

import java.util.Map;

/**
 * Protocol-agnostic endpoint descriptor.
 *
 * <p>{@code requestSchema} and {@code responseMapping} are intentionally
 * {@code Map<String, Object>} so each protocol can carry its own shape
 * (HTTP path/method, JDBC SQL template, CSV column map…).
 *
 * @param pid             ULID id
 * @param connectorPid    parent connector pid
 * @param code            unique within parent connector (e.g. "list-users", "query", "update")
 * @param displayName     UI label
 * @param requestSchema   JSON-shaped request schema
 * @param responseMapping JSON-shaped response mapping
 * @since 5.2.0
 */
public record ConnectorEndpoint(
        String pid,
        String connectorPid,
        String code,
        String displayName,
        Map<String, Object> requestSchema,
        Map<String, Object> responseMapping
) {
}
