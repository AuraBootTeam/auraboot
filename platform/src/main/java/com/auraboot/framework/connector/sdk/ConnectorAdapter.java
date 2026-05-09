package com.auraboot.framework.connector.sdk;

import java.util.Optional;

/**
 * Strategy interface for protocol-specific connector adapters.
 *
 * <p>Each connector protocol (HTTP, JDBC, CSV, …) provides exactly one
 * {@code ConnectorAdapter} implementation. The adapter is registered with
 * {@code ConnectorRegistry}, which routes invocation requests by
 * {@link ConnectorDescriptor#protocolType()}.
 *
 * <h3>Implementing a new adapter</h3>
 * <ol>
 *   <li>Extend {@link AbstractConnectorAdapter} (or implement this interface directly).</li>
 *   <li>Return a stable {@link ConnectorDescriptor} from {@link #descriptor()}.</li>
 *   <li>Implement {@link #invoke(ConnectorInvocationContext)} — never throw on transport
 *       errors; wrap them in {@link ConnectorInvocationResult#failure(String)} instead.</li>
 *   <li>Implement {@link #testConnection(Long, String)} — return {@code false} on any
 *       error; do NOT throw.</li>
 *   <li>Register the bean as a Spring component so the registry can discover it.</li>
 * </ol>
 *
 * @since 5.2.0
 */
public interface ConnectorAdapter {

    /**
     * Returns the static metadata that describes this adapter, including the
     * {@link ConnectorDescriptor#protocolType() protocolType} key used for routing.
     *
     * <p>The returned descriptor must be effectively immutable and consistent across
     * calls; caching a singleton instance is recommended.
     *
     * @return non-null descriptor
     */
    ConnectorDescriptor descriptor();

    /**
     * Returns {@code true} when this adapter can handle the given protocol type.
     *
     * <p>Comparison is <em>case-insensitive</em> so that callers need not normalise
     * the value coming from storage or user input (e.g. {@code "HTTP"} and
     * {@code "http"} both match an adapter whose descriptor uses {@code "http"}).
     *
     * <p>Returns {@code false} for {@code null}, empty, or blank inputs without
     * throwing — the registry uses this as a safe predicate.
     *
     * @param protocolType the protocol key to test; may be null
     * @return true if this adapter owns the given protocol type
     */
    default boolean supports(String protocolType) {
        if (protocolType == null || protocolType.isBlank()) {
            return false;
        }
        return descriptor().protocolType().equalsIgnoreCase(protocolType);
    }

    /**
     * Optionally resolves a {@link Connector} record from the adapter's own
     * storage layer. The default implementation returns {@link Optional#empty()},
     * which causes the registry to fall back to the generic connector table.
     *
     * <p>Override when the protocol stores connectors in a dedicated table and
     * the caller needs the richer representation.
     *
     * @param tenantId     the owning tenant
     * @param connectorPid the connector's ULID identifier
     * @return an optional connector record
     */
    default Optional<Connector> findConnector(Long tenantId, String connectorPid) {
        return Optional.empty();
    }

    /**
     * Executes the connector invocation described by {@code context} and returns
     * a result envelope.
     *
     * <p><strong>Contract:</strong> this method MUST NOT throw on protocol-level or
     * transport-level errors. All failures must be returned as
     * {@link ConnectorInvocationResult#failure(String)}. Exceptions may only
     * propagate for programming errors (e.g. null context).
     *
     * @param context the invocation request (tenant, connector, endpoint, params)
     * @return non-null result envelope
     */
    ConnectorInvocationResult invoke(ConnectorInvocationContext context);

    /**
     * Tests whether the connector identified by {@code connectorPid} is reachable
     * and the credentials are valid.
     *
     * <p><strong>Contract:</strong> this method MUST NOT throw. Any connectivity
     * problem (timeout, auth failure, unreachable host) must be represented by
     * returning {@code false}. The caller treats any exception as a programming
     * error, not a connectivity signal.
     *
     * @param tenantId     the owning tenant
     * @param connectorPid the connector's ULID identifier
     * @return true if the connection test succeeded; false on any error
     */
    boolean testConnection(Long tenantId, String connectorPid);
}
