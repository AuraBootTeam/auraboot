package com.auraboot.framework.connector.jdbc.service;

import com.auraboot.framework.connector.jdbc.entity.JdbcConnector;
import com.auraboot.framework.connector.sdk.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SDK adapter that wires {@link JdbcConnectorService} into the unified
 * {@link ConnectorRegistry} under protocol type "jdbc".
 *
 * <p>Follows the same pattern as {@link HttpConnectorAdapter}: delegates
 * all business logic to the service, maps raw entity rows to the generic
 * {@link Connector} value-object, and absorbs transport-level failures in
 * {@link #testConnection} per the {@link ConnectorAdapter} contract.
 *
 * @since 5.2.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JdbcConnectorAdapter extends AbstractConnectorAdapter {

    private static final ConnectorDescriptor DESCRIPTOR = new ConnectorDescriptor(
            "jdbc",
            "JDBC database connector (MySQL/PostgreSQL) with HikariCP pooling",
            List.of("query", "update"));

    private final JdbcConnectorService jdbcConnectorService;

    @Override
    public ConnectorDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public Optional<Connector> findConnector(Long tenantId, String connectorPid) {
        JdbcConnector raw = jdbcConnectorService.getByPid(connectorPid);
        if (raw == null || !tenantId.equals(raw.getTenantId())) {
            return Optional.empty();
        }
        return Optional.of(new Connector(
                raw.getPid(),
                raw.getTenantId(),
                "jdbc",
                raw.getName(),
                Boolean.TRUE.equals(raw.getEnabled())));
    }

    @Override
    public ConnectorInvocationResult invoke(ConnectorInvocationContext ctx) {
        Map<String, Object> result = jdbcConnectorService.invoke(
                ctx.connectorPid(),
                ctx.endpointCode(),
                ctx.params() == null ? Map.of() : ctx.params());
        return ConnectorInvocationResult.success(result);
    }

    @Override
    public boolean testConnection(Long tenantId, String connectorPid) {
        try {
            return jdbcConnectorService.testConnection(connectorPid);
        } catch (IllegalArgumentException e) {
            // Re-throw config/validation errors so callers can distinguish bad input
            // from transient connectivity failures
            throw e;
        } catch (Exception e) {
            // Transport-level failures (timeouts, refused connections, DNS errors) are
            // absorbed and surfaced as false per ConnectorAdapter contract — adapter
            // must NOT propagate transient errors to the registry call-site
            log.warn("JdbcConnectorAdapter.testConnection failed for pid={}: {}",
                    connectorPid, e.getMessage());
            return false;
        }
    }
}
