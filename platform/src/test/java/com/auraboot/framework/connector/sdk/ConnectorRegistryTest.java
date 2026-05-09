package com.auraboot.framework.connector.sdk;

import com.auraboot.framework.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link ConnectorRegistry}.
 * Uses inline stub adapters — no Spring context required.
 */
class ConnectorRegistryTest {

    // -------------------------------------------------------------------------
    // Stub adapters
    // -------------------------------------------------------------------------

    private static ConnectorAdapter stubAdapter(String protocolType,
                                                ConnectorInvocationResult invokeResult,
                                                boolean testResult) {
        return new ConnectorAdapter() {
            private final ConnectorDescriptor desc = new ConnectorDescriptor(
                    protocolType, protocolType + " adapter", List.of());

            @Override
            public ConnectorDescriptor descriptor() {
                return desc;
            }

            @Override
            public ConnectorInvocationResult invoke(ConnectorInvocationContext context) {
                return invokeResult;
            }

            @Override
            public boolean testConnection(Long tenantId, String connectorPid) {
                return testResult;
            }

            @Override
            public Optional<Connector> findConnector(Long tenantId, String connectorPid) {
                return Optional.empty();
            }
        };
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    void routesInvokeByProtocolType() {
        ConnectorInvocationResult expected = ConnectorInvocationResult.success(Map.of("rows", 1));
        ConnectorAdapter httpAdapter = stubAdapter("http", expected, true);
        ConnectorRegistry registry = new ConnectorRegistry(List.of(httpAdapter));

        ConnectorInvocationContext ctx = new ConnectorInvocationContext(
                1L, "conn-001", "query", Map.of(), false);

        ConnectorInvocationResult result = registry.invoke("http", ctx);

        assertThat(result.success()).isTrue();
        assertThat(result.data()).isEqualTo(Map.of("rows", 1));
    }

    @Test
    void routesTestConnectionByProtocolType() {
        ConnectorAdapter jdbcAdapter = stubAdapter("jdbc", ConnectorInvocationResult.success(null), true);
        ConnectorRegistry registry = new ConnectorRegistry(List.of(jdbcAdapter));

        boolean ok = registry.testConnection("jdbc", 1L, "conn-002");

        assertThat(ok).isTrue();
    }

    @Test
    void throwsBusinessExceptionForUnknownProtocol() {
        ConnectorAdapter httpAdapter = stubAdapter("http",
                ConnectorInvocationResult.success(null), true);
        ConnectorRegistry registry = new ConnectorRegistry(List.of(httpAdapter));

        ConnectorInvocationContext ctx = new ConnectorInvocationContext(
                1L, "conn-003", "query", Map.of(), false);

        assertThatThrownBy(() -> registry.invoke("grpc", ctx))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("grpc");

        assertThatThrownBy(() -> registry.testConnection("grpc", 1L, "conn-003"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("grpc");
    }

    @Test
    void listsAllRegisteredDescriptors() {
        ConnectorAdapter httpAdapter = stubAdapter("http",
                ConnectorInvocationResult.success(null), true);
        ConnectorAdapter jdbcAdapter = stubAdapter("jdbc",
                ConnectorInvocationResult.success(null), false);
        ConnectorRegistry registry = new ConnectorRegistry(List.of(httpAdapter, jdbcAdapter));

        List<ConnectorDescriptor> descriptors = registry.listDescriptors();

        assertThat(descriptors).hasSize(2);
        assertThat(descriptors).extracting(ConnectorDescriptor::protocolType)
                .containsExactlyInAnyOrder("http", "jdbc");
    }

    @Test
    void rejectsDuplicateProtocolTypeAtConstruction() {
        ConnectorAdapter adapterA = stubAdapter("mysql",
                ConnectorInvocationResult.success(null), true);
        ConnectorAdapter adapterB = stubAdapter("mysql",
                ConnectorInvocationResult.success(null), false);

        assertThatThrownBy(() -> new ConnectorRegistry(List.of(adapterA, adapterB)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mysql");
    }

    @Test
    void handlesCaseInsensitiveMatch() {
        ConnectorAdapter httpAdapter = stubAdapter("http",
                ConnectorInvocationResult.success(Map.of("ok", true)), true);
        ConnectorRegistry registry = new ConnectorRegistry(List.of(httpAdapter));

        ConnectorInvocationContext ctx = new ConnectorInvocationContext(
                1L, "conn-004", "query", Map.of(), false);

        // Lookup with uppercase — must resolve to the "http" adapter
        assertThat(registry.invoke("HTTP", ctx).success()).isTrue();
        assertThat(registry.invoke("Http", ctx).success()).isTrue();
        assertThat(registry.testConnection("HTTP", 1L, "conn-004")).isTrue();
    }
}
