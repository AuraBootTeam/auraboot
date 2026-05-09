package com.auraboot.framework.connector.jdbc.service;

import com.auraboot.framework.connector.jdbc.entity.JdbcConnector;
import com.auraboot.framework.connector.sdk.Connector;
import com.auraboot.framework.connector.sdk.ConnectorDescriptor;
import com.auraboot.framework.connector.sdk.ConnectorInvocationContext;
import com.auraboot.framework.connector.sdk.ConnectorInvocationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JdbcConnectorAdapterTest {

    @Mock
    private JdbcConnectorService jdbcConnectorService;

    @InjectMocks
    private JdbcConnectorAdapter adapter;

    @Test
    void descriptor_returnsJdbcWithQueryAndUpdateEndpoints() {
        ConnectorDescriptor descriptor = adapter.descriptor();
        assertThat(descriptor.protocolType()).isEqualTo("jdbc");
        assertThat(descriptor.supportedEndpointCodes()).contains("query", "update");
    }

    @Test
    void findConnector_wrapsRowAsConnectorRecord() {
        JdbcConnector row = new JdbcConnector();
        row.setPid("conn-1");
        row.setTenantId(42L);
        row.setName("Test DB");
        row.setEnabled(true);

        when(jdbcConnectorService.getByPid("conn-1")).thenReturn(row);

        Optional<Connector> result = adapter.findConnector(42L, "conn-1");

        assertThat(result).isPresent();
        assertThat(result.get().protocolType()).isEqualTo("jdbc");
        assertThat(result.get().pid()).isEqualTo("conn-1");
        assertThat(result.get().tenantId()).isEqualTo(42L);
    }

    @Test
    void findConnector_returnsEmptyForCrossTenantAccess() {
        JdbcConnector row = new JdbcConnector();
        row.setPid("conn-1");
        row.setTenantId(42L);
        row.setEnabled(true);

        when(jdbcConnectorService.getByPid("conn-1")).thenReturn(row);

        Optional<Connector> result = adapter.findConnector(99L, "conn-1");

        assertThat(result).isEmpty();
    }

    @Test
    void testConnection_returnsFalseOnTransportException() {
        when(jdbcConnectorService.testConnection("conn-broken"))
                .thenThrow(new RuntimeException("Connection refused"));

        boolean result = adapter.testConnection(1L, "conn-broken");

        assertThat(result).isFalse();
    }
}
