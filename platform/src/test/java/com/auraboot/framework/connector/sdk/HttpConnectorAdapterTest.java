package com.auraboot.framework.connector.sdk;

import com.auraboot.framework.connector.entity.ApiConnector;
import com.auraboot.framework.connector.mapper.ApiConnectorMapper;
import com.auraboot.framework.connector.service.ApiConnectorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Pure Mockito unit tests for {@link HttpConnectorAdapter}.
 * No Spring context, no database — compliant with multi-worktree isolation rules.
 */
@ExtendWith(MockitoExtension.class)
class HttpConnectorAdapterTest {

    @Mock
    ApiConnectorService apiConnectorService;

    @Mock
    ApiConnectorMapper apiConnectorMapper;

    HttpConnectorAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new HttpConnectorAdapter(apiConnectorService, apiConnectorMapper);
    }

    // Test 1: descriptor() returns correct protocolType and supportedEndpointCodes
    @Test
    void descriptor_returnsHttpProtocolWithInvokeEndpoint() {
        ConnectorDescriptor desc = adapter.descriptor();

        assertThat(desc.protocolType()).isEqualTo("http");
        assertThat(desc.supportedEndpointCodes()).contains("invoke");
    }

    // Test 2: findConnector wraps ApiConnector row as Connector record
    @Test
    void findConnector_wrapsApiConnectorRowAsConnectorRecord() {
        ApiConnector row = new ApiConnector();
        row.setPid("pid-001");
        row.setTenantId(42L);
        row.setName("My HTTP Connector");
        row.setEnabled(true);

        when(apiConnectorMapper.findByPid(42L, "pid-001")).thenReturn(row);

        Optional<Connector> result = adapter.findConnector(42L, "pid-001");

        assertThat(result).isPresent();
        Connector connector = result.get();
        assertThat(connector.pid()).isEqualTo("pid-001");
        assertThat(connector.tenantId()).isEqualTo(42L);
        assertThat(connector.protocolType()).isEqualTo("http");
        assertThat(connector.displayName()).isEqualTo("My HTTP Connector");
        assertThat(connector.enabled()).isTrue();
    }

    // Test 3: findConnector returns Optional.empty() when mapper returns null
    @Test
    void findConnector_returnsEmptyWhenMapperReturnsNull() {
        when(apiConnectorMapper.findByPid(1L, "nonexistent")).thenReturn(null);

        Optional<Connector> result = adapter.findConnector(1L, "nonexistent");

        assertThat(result).isEmpty();
    }

    // Test 4: invoke rethrows IllegalArgumentException from apiConnectorService.invoke
    @Test
    void invoke_rethrowsIllegalArgumentExceptionFromService() {
        ConnectorInvocationContext ctx = new ConnectorInvocationContext(
                1L, "pid-001", "invoke", Map.of("key", "val"), false);

        when(apiConnectorService.invoke("pid-001", "invoke", Map.of("key", "val")))
                .thenThrow(new IllegalArgumentException("unknown connector pid-001"));

        assertThatThrownBy(() -> adapter.invoke(ctx))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown connector pid-001");
    }

    // Test 5a: testConnection returns false when service throws non-IllegalArgumentException
    @Test
    void testConnection_returnsFalseOnNonIllegalArgumentException() {
        when(apiConnectorService.testConnection("pid-001"))
                .thenThrow(new RuntimeException("connect timeout"));

        boolean result = adapter.testConnection(1L, "pid-001");

        assertThat(result).isFalse();
    }

    // Test 5b: testConnection rethrows IllegalArgumentException as-is
    @Test
    void testConnection_rethrowsIllegalArgumentException() {
        when(apiConnectorService.testConnection("pid-bad"))
                .thenThrow(new IllegalArgumentException("connector not found: pid-bad"));

        assertThatThrownBy(() -> adapter.testConnection(1L, "pid-bad"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connector not found: pid-bad");
    }
}
