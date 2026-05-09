package com.auraboot.framework.connector.sdk;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ConnectorAdapter} default contract and
 * {@link AbstractConnectorAdapter} as a base class.
 *
 * <p>Uses an inline {@code EchoAdapter} stub to exercise the interface without
 * any Spring context — pure JUnit 5 + AssertJ only.
 */
class ConnectorAdapterTest {

    // ---------------------------------------------------------------------------
    // Stub adapter
    // ---------------------------------------------------------------------------

    /**
     * Minimal stub that echoes the invocation params back as the result payload.
     */
    static class EchoAdapter extends AbstractConnectorAdapter {

        private static final ConnectorDescriptor DESCRIPTOR = new ConnectorDescriptor(
                "echo",
                "Echo connector for testing",
                List.of("ping")
        );

        @Override
        public ConnectorDescriptor descriptor() {
            return DESCRIPTOR;
        }

        @Override
        public ConnectorInvocationResult invoke(ConnectorInvocationContext context) {
            return ConnectorInvocationResult.success(Map.of(
                    "tenantId", context.tenantId(),
                    "endpointCode", context.endpointCode(),
                    "params", context.params()
            ));
        }

        @Override
        public boolean testConnection(Long tenantId, String connectorPid) {
            return true;
        }
    }

    // ---------------------------------------------------------------------------
    // Test fixtures
    // ---------------------------------------------------------------------------

    private final EchoAdapter adapter = new EchoAdapter();

    private ConnectorInvocationContext ctx(String endpointCode) {
        return new ConnectorInvocationContext(1L, "conn-001", endpointCode, Map.of("k", "v"), false);
    }

    // ---------------------------------------------------------------------------
    // supports() — case-insensitive, null-safe, blank-safe
    // ---------------------------------------------------------------------------

    @Test
    void supports_exactMatch_returnsTrue() {
        assertThat(adapter.supports("echo")).isTrue();
    }

    @Test
    void supports_upperCase_returnsTrue() {
        assertThat(adapter.supports("ECHO")).isTrue();
    }

    @Test
    void supports_mixedCase_returnsTrue() {
        assertThat(adapter.supports("Echo")).isTrue();
    }

    @Test
    void supports_differentProtocol_returnsFalse() {
        assertThat(adapter.supports("http")).isFalse();
    }

    @Test
    void supports_null_returnsFalse() {
        assertThat(adapter.supports(null)).isFalse();
    }

    @Test
    void supports_empty_returnsFalse() {
        assertThat(adapter.supports("")).isFalse();
    }

    @Test
    void supports_blank_returnsFalse() {
        assertThat(adapter.supports("  ")).isFalse();
    }

    // ---------------------------------------------------------------------------
    // invoke()
    // ---------------------------------------------------------------------------

    @Test
    void invoke_returnsSuccess() {
        ConnectorInvocationResult result = adapter.invoke(ctx("ping"));

        assertThat(result.success()).isTrue();
        assertThat(result.errorMessage()).isNull();
        assertThat(result.data()).isNotNull();
    }

    @Test
    void invoke_echosEndpointCodeInData() {
        ConnectorInvocationResult result = adapter.invoke(ctx("ping"));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.data();
        assertThat(data).containsEntry("endpointCode", "ping");
    }

    // ---------------------------------------------------------------------------
    // findConnector() — default implementation returns empty
    // ---------------------------------------------------------------------------

    @Test
    void findConnector_defaultReturnsEmpty() {
        Optional<Connector> result = adapter.findConnector(1L, "conn-001");

        assertThat(result).isEmpty();
    }

    // ---------------------------------------------------------------------------
    // descriptor()
    // ---------------------------------------------------------------------------

    @Test
    void descriptor_returnsCorrectProtocolType() {
        assertThat(adapter.descriptor().protocolType()).isEqualTo("echo");
    }
}
