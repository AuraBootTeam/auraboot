package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.connector.entity.ApiConnector;
import com.auraboot.framework.connector.service.ApiConnectorService;
import com.auraboot.framework.plugin.extension.BackgroundConnectorCredentialAccessor.ConnectorCredentials;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BackgroundConnectorCredentialAccessorImplTest {

    private ApiConnectorService connectorService;
    private BackgroundConnectorCredentialAccessorImpl accessor;

    @BeforeEach
    void setUp() {
        connectorService = mock(ApiConnectorService.class);
        accessor = new BackgroundConnectorCredentialAccessorImpl(connectorService, new ObjectMapper());
    }

    private static ApiConnector entity(String pid, String baseUrl, String authType,
                                       String authConfig, String defaultHeaders) {
        ApiConnector c = new ApiConnector();
        c.setPid(pid);
        c.setBaseUrl(baseUrl);
        c.setAuthType(authType);
        c.setAuthConfig(authConfig);
        c.setDefaultHeaders(defaultHeaders);
        return c;
    }

    @Test
    void lookupByPid_returnsSnapshot_whenConnectorExists() {
        when(connectorService.getByPid("conn-1"))
                .thenReturn(entity("conn-1", "https://api.example.com", "bearer",
                        "{\"token\":\"abc\"}", "{\"X-Trace\":\"yes\"}"));
        Optional<ConnectorCredentials> got = accessor.lookupByPid("conn-1");
        assertThat(got).isPresent();
        ConnectorCredentials c = got.get();
        assertThat(c.getPid()).isEqualTo("conn-1");
        assertThat(c.getBaseUrl()).isEqualTo("https://api.example.com");
        assertThat(c.getAuthType()).isEqualTo("bearer");
        assertThat(c.getAuthConfigJson()).isEqualTo("{\"token\":\"abc\"}");
        assertThat(c.getDefaultHeaders()).containsEntry("X-Trace", "yes");
    }

    @Test
    void lookupByPid_returnsEmpty_whenPidBlankOrNull() {
        assertThat(accessor.lookupByPid(null)).isEmpty();
        assertThat(accessor.lookupByPid("")).isEmpty();
        assertThat(accessor.lookupByPid("   ")).isEmpty();
    }

    @Test
    void lookupByPid_returnsEmpty_whenServiceReturnsNull() {
        when(connectorService.getByPid("missing")).thenReturn(null);
        assertThat(accessor.lookupByPid("missing")).isEmpty();
    }

    @Test
    void lookupByPid_returnsEmpty_whenServiceThrows_doesNotPropagate() {
        when(connectorService.getByPid("boom")).thenThrow(new RuntimeException("DB down"));
        // Resilient: transient host failure should not kill the caller's
        // scheduler/admit loop. Plugin can decide to skip or retry.
        assertThat(accessor.lookupByPid("boom")).isEmpty();
    }

    @Test
    void parseHeaders_handlesNullAndBlank() {
        when(connectorService.getByPid("a"))
                .thenReturn(entity("a", "u", "none", null, null));
        when(connectorService.getByPid("b"))
                .thenReturn(entity("b", "u", "none", null, ""));
        when(connectorService.getByPid("c"))
                .thenReturn(entity("c", "u", "none", null, "   "));
        assertThat(accessor.lookupByPid("a").get().getDefaultHeaders()).isEmpty();
        assertThat(accessor.lookupByPid("b").get().getDefaultHeaders()).isEmpty();
        assertThat(accessor.lookupByPid("c").get().getDefaultHeaders()).isEmpty();
    }

    @Test
    void parseHeaders_returnsEmpty_whenHeadersAreNotJsonStringMap() {
        // Defensive: legacy rows might be a JSON array or a non-string-valued
        // object. Don't propagate the parse failure to callers.
        when(connectorService.getByPid("legacy"))
                .thenReturn(entity("legacy", "u", "none", null, "[1,2,3]"));
        assertThat(accessor.lookupByPid("legacy").get().getDefaultHeaders()).isEmpty();
    }

    @Test
    void lookupByPid_preservesHeaderOrder() {
        // LinkedHashMap insertion order — relevant because some auth flows
        // care about header precedence (e.g., overriding by later entry).
        when(connectorService.getByPid("ordered"))
                .thenReturn(entity("ordered", "u", "none", null,
                        "{\"A\":\"1\",\"B\":\"2\",\"C\":\"3\"}"));
        assertThat(accessor.lookupByPid("ordered").get().getDefaultHeaders().keySet())
                .containsExactly("A", "B", "C");
    }
}
