package com.auraboot.framework.connector.sdk;

/**
 * Protocol-agnostic connector descriptor returned by every {@link ConnectorAdapter}.
 *
 * <p>This is a value-object view; the actual storage row lives in the
 * protocol-specific table (e.g. {@code ab_api_connector}, {@code ab_jdbc_connector}).
 *
 * @param pid           ULID primary identifier (26 chars)
 * @param tenantId      owning tenant id
 * @param protocolType  registered protocol key, e.g. "http", "jdbc", "csv"
 * @param displayName   human-friendly name shown in admin UI
 * @param enabled       whether the connector is active
 * @since 5.2.0
 */
public record Connector(
        String pid,
        Long tenantId,
        String protocolType,
        String displayName,
        boolean enabled
) {
}
