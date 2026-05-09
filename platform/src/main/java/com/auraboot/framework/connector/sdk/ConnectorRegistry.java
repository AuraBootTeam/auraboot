package com.auraboot.framework.connector.sdk;

import com.auraboot.framework.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Spring-managed registry that routes connector operations to the correct
 * {@link ConnectorAdapter} by {@link ConnectorDescriptor#protocolType()}.
 *
 * <p>All registered adapters are discovered via Spring constructor injection.
 * Protocol-type matching is <em>case-insensitive</em> — keys are normalised to
 * lower-case on registration so that callers need not sanitise the value coming
 * from storage or user input.
 *
 * <p><strong>Duplicate detection:</strong> if two adapters share the same
 * (case-folded) protocol type, construction fails immediately with
 * {@link IllegalStateException} so misconfiguration is caught at startup.
 *
 * @since 5.2.0
 */
@Slf4j
@Component
public class ConnectorRegistry {

    private final Map<String, ConnectorAdapter> adaptersByProtocol;

    public ConnectorRegistry(List<ConnectorAdapter> adapters) {
        Map<String, ConnectorAdapter> map = new HashMap<>();
        for (ConnectorAdapter adapter : adapters) {
            String key = adapter.descriptor().protocolType().toLowerCase(Locale.ROOT);
            ConnectorAdapter prev = map.put(key, adapter);
            if (prev != null) {
                throw new IllegalStateException(
                        "Duplicate connector protocolType registered: " + key
                        + " (existing=" + prev.getClass().getName()
                        + ", new=" + adapter.getClass().getName() + ")");
            }
        }
        this.adaptersByProtocol = Collections.unmodifiableMap(map);
        log.info("ConnectorRegistry initialized with {} adapters: {}", map.size(), map.keySet());
    }

    /**
     * Routes an invocation to the adapter registered for {@code protocolType}.
     *
     * @param protocolType the protocol key (case-insensitive)
     * @param ctx          invocation context
     * @return non-null result envelope
     * @throws BusinessException if no adapter is registered for the given protocol
     */
    public ConnectorInvocationResult invoke(String protocolType, ConnectorInvocationContext ctx) {
        return resolve(protocolType).invoke(ctx);
    }

    /**
     * Tests the connection for the given protocol type.
     *
     * @param protocolType the protocol key (case-insensitive)
     * @param tenantId     owning tenant
     * @param connectorPid connector ULID
     * @return true if the connection test succeeded
     * @throws BusinessException if no adapter is registered for the given protocol
     */
    public boolean testConnection(String protocolType, Long tenantId, String connectorPid) {
        return resolve(protocolType).testConnection(tenantId, connectorPid);
    }

    /**
     * Returns metadata descriptors for all registered adapters.
     *
     * @return immutable snapshot of descriptors
     */
    public List<ConnectorDescriptor> listDescriptors() {
        return adaptersByProtocol.values().stream()
                .map(ConnectorAdapter::descriptor)
                .toList();
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private ConnectorAdapter resolve(String protocolType) {
        if (protocolType == null || protocolType.isBlank()) {
            throw new BusinessException("Connector protocolType must not be blank");
        }
        ConnectorAdapter adapter = adaptersByProtocol.get(protocolType.toLowerCase(Locale.ROOT));
        if (adapter == null) {
            throw new BusinessException("No connector adapter registered for protocol: " + protocolType);
        }
        return adapter;
    }
}
