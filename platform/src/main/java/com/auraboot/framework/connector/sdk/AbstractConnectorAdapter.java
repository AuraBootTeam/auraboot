package com.auraboot.framework.connector.sdk;

/**
 * Skeletal implementation of {@link ConnectorAdapter} that future adapter
 * subclasses (e.g. {@code HttpConnectorAdapter}, {@code JdbcConnectorAdapter})
 * extend to inherit default behaviour and any shared helpers added in later tasks.
 *
 * <p>This class is intentionally empty for now: the interface already provides
 * sensible defaults for {@link ConnectorAdapter#supports(String)} and
 * {@link ConnectorAdapter#findConnector(Long, String)}, and the remaining abstract
 * methods — {@link ConnectorAdapter#descriptor()},
 * {@link ConnectorAdapter#invoke(ConnectorInvocationContext)}, and
 * {@link ConnectorAdapter#testConnection(Long, String)} — are left to concrete
 * subclasses to implement with protocol-specific logic.
 *
 * <h3>Extension contract</h3>
 * <ul>
 *   <li>Subclasses <em>should</em> annotate themselves with {@code @Component} (or
 *       equivalent Spring stereotype) so {@code ConnectorRegistry} can discover them.</li>
 *   <li>Subclasses <em>must not</em> throw on transport errors in {@code invoke} or
 *       {@code testConnection}; see {@link ConnectorAdapter} for details.</li>
 * </ul>
 *
 * @since 5.2.0
 */
public abstract class AbstractConnectorAdapter implements ConnectorAdapter {
}
