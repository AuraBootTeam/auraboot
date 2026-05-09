package com.auraboot.framework.connector.sdk;

import com.auraboot.framework.connector.entity.ApiConnector;
import com.auraboot.framework.connector.mapper.ApiConnectorMapper;
import com.auraboot.framework.connector.service.ApiConnectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link ConnectorAdapter} implementation that delegates to the existing
 * {@link ApiConnectorService} for REST/HTTP connectors.
 *
 * <p>This adapter is automatically registered in {@link ConnectorRegistry}
 * via Spring component scanning and is resolved for protocol type "http".
 *
 * @since 5.2.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpConnectorAdapter extends AbstractConnectorAdapter {

    private static final ConnectorDescriptor DESCRIPTOR = new ConnectorDescriptor(
            "http",
            "REST/HTTP API connector with auth + SSRF + DNS-pinning",
            List.of("invoke"));

    private final ApiConnectorService apiConnectorService;
    private final ApiConnectorMapper apiConnectorMapper;

    @Override
    public ConnectorDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public Optional<Connector> findConnector(Long tenantId, String connectorPid) {
        ApiConnector row = apiConnectorMapper.findByPid(tenantId, connectorPid);
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(new Connector(
                row.getPid(),
                row.getTenantId(),
                "http",
                row.getName(),
                Boolean.TRUE.equals(row.getEnabled())));
    }

    @Override
    public ConnectorInvocationResult invoke(ConnectorInvocationContext context) {
        Map<String, Object> result = apiConnectorService.invoke(
                context.connectorPid(),
                context.endpointCode(),
                context.params() == null ? Map.of() : context.params());
        return ConnectorInvocationResult.success(result);
    }

    @Override
    public boolean testConnection(Long tenantId, String connectorPid) {
        try {
            return apiConnectorService.testConnection(connectorPid);
        } catch (IllegalArgumentException e) {
            // Re-throw config/validation errors so callers can distinguish bad input
            // from transient connectivity failures
            throw e;
        } catch (Exception e) {
            // Transient connectivity failures (timeouts, DNS errors, etc.) are logged
            // and surfaced as false rather than propagated, keeping the SDK call-site
            // exception-free per ConnectorAdapter contract
            log.warn("HttpConnectorAdapter.testConnection failed for pid={}: {}",
                    connectorPid, e.getMessage());
            return false;
        }
    }
}
