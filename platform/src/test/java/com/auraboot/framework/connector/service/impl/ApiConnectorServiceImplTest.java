package com.auraboot.framework.connector.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.crypto.FieldEncryptionService;
import com.auraboot.framework.connector.dto.ApiConnectorCreateRequest;
import com.auraboot.framework.connector.entity.ApiConnector;
import com.auraboot.framework.connector.mapper.ApiConnectorEndpointMapper;
import com.auraboot.framework.connector.mapper.ApiConnectorMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.MockedStatic;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApiConnectorServiceImplTest {

    @Mock
    private ApiConnectorMapper connectorMapper;
    @Mock
    private ApiConnectorEndpointMapper endpointMapper;
    @Mock
    private FieldEncryptionService fieldEncryptionService;

    private ObjectMapper objectMapper = new ObjectMapper();

    private ApiConnectorServiceImpl service;

    private MockedStatic<MetaContext> metaContextMock;

    @BeforeEach
    void setUp() {
        service = new ApiConnectorServiceImpl(connectorMapper, endpointMapper, objectMapper, fieldEncryptionService);
        metaContextMock = Mockito.mockStatic(MetaContext.class);
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(1L);
    }

    @AfterEach
    void tearDown() {
        metaContextMock.close();
    }

    private ApiConnectorCreateRequest req(String url) {
        ApiConnectorCreateRequest r = new ApiConnectorCreateRequest();
        r.setName("svc");
        r.setBaseUrl(url);
        r.setAuthType("none");
        r.setAuthConfig("{}");
        r.setEnabled(true);
        return r;
    }

    @Test
    void create_validRequest_persistsAndEncryptsAuthConfig() {
        when(fieldEncryptionService.encrypt("{}")).thenReturn("ENC");

        ApiConnector entity = service.create(req("https://api.example.com/"));

        assertThat(entity.getName()).isEqualTo("svc");
        assertThat(entity.getAuthConfig()).isEqualTo("ENC");
        assertThat(entity.getTenantId()).isEqualTo(1L);
        assertThat(entity.getPid()).isNotBlank();
        verify(connectorMapper).insert(entity);
    }

    @Test
    void create_invalidUrl_throws() {
        assertThatThrownBy(() -> service.create(req("not-a-url")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(connectorMapper, never()).insert(any(ApiConnector.class));
    }

    @Test
    void getByPid_delegatesToMapper() {
        ApiConnector existing = new ApiConnector();
        when(connectorMapper.findByPid(1L, "p")).thenReturn(existing);
        assertThat(service.getByPid("p")).isSameAs(existing);
    }

    @Test
    void listAll_delegatesToMapper() {
        when(connectorMapper.findByTenant(1L)).thenReturn(List.of());
        assertThat(service.listAll()).isEmpty();
    }

    @Test
    void update_existing_persistsChanges() {
        ApiConnector existing = new ApiConnector();
        existing.setPid("p");
        when(connectorMapper.findByPid(1L, "p")).thenReturn(existing);
        when(fieldEncryptionService.encrypt(any())).thenReturn("ENC");

        ApiConnector updated = service.update("p", req("https://api2.example.com/"));
        assertThat(updated.getBaseUrl()).isEqualTo("https://api2.example.com/");
        assertThat(updated.getAuthConfig()).isEqualTo("ENC");
        verify(connectorMapper).updateById(existing);
    }

    @Test
    void update_missing_throws() {
        when(connectorMapper.findByPid(1L, "missing")).thenReturn(null);
        assertThatThrownBy(() -> service.update("missing", req("https://api.example.com/")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void update_invalidUrl_throws() {
        assertThatThrownBy(() -> service.update("p", req("ftp://forbidden")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void delete_cascadesEndpointsAndConnector() {
        service.delete("p");
        verify(endpointMapper).deleteByConnector("p");
        verify(connectorMapper).deleteByPid(1L, "p");
    }

    @Test
    void invoke_connectorMissing_throws() {
        when(connectorMapper.findByPid(1L, "p")).thenReturn(null);
        assertThatThrownBy(() -> service.invoke("p", "ep", java.util.Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void invoke_endpointMissing_throws() {
        ApiConnector c = new ApiConnector();
        c.setBaseUrl("https://api.example.com");
        when(connectorMapper.findByPid(1L, "p")).thenReturn(c);
        when(endpointMapper.findByCode("p", "ep")).thenReturn(null);
        assertThatThrownBy(() -> service.invoke("p", "ep", java.util.Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testConnection_connectorMissing_throws() {
        when(connectorMapper.findByPid(1L, "p")).thenReturn(null);
        assertThatThrownBy(() -> service.testConnection("p"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testConnection_invalidUrl_returnsFalse() {
        ApiConnector c = new ApiConnector();
        c.setPid("p");
        c.setBaseUrl("not-a-url");
        when(connectorMapper.findByPid(1L, "p")).thenReturn(c);
        assertThat(service.testConnection("p")).isFalse();
    }
}
