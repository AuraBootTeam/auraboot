package com.auraboot.framework.connector.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.crypto.FieldEncryptionService;
import com.auraboot.framework.connector.dto.ApiConnectorCreateRequest;
import com.auraboot.framework.connector.entity.ApiConnector;
import com.auraboot.framework.connector.mapper.ApiConnectorEndpointMapper;
import com.auraboot.framework.connector.mapper.ApiConnectorMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.MockedStatic;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;
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

    // -----------------------------------------------------------------------
    // Existing tests (passthrough / mock fieldEncryptionService)
    // -----------------------------------------------------------------------

    @Test
    void create_validRequest_persistsAndEncryptsAuthConfig() {
        when(fieldEncryptionService.encryptJsonFields(any(), any())).thenReturn("ENC_JSON_BLOB");

        ApiConnector entity = service.create(req("https://api.example.com/"));

        assertThat(entity.getName()).isEqualTo("svc");
        assertThat(entity.getAuthConfig()).isEqualTo("ENC_JSON_BLOB");
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
        when(fieldEncryptionService.encryptJsonFields(any(), any())).thenReturn("ENC_JSON_BLOB");

        ApiConnector updated = service.update("p", req("https://api2.example.com/"));
        assertThat(updated.getBaseUrl()).isEqualTo("https://api2.example.com/");
        assertThat(updated.getAuthConfig()).isEqualTo("ENC_JSON_BLOB");
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

    // -----------------------------------------------------------------------
    // NEW: field-level encryption tests using a REAL FieldEncryptionService
    // with a configured AES-256 key — verifies the JSONB-valid contract.
    // -----------------------------------------------------------------------

    /**
     * Build a real FieldEncryptionService with a deterministic 32-byte AES-256 key
     * so encrypt() actually prefixes ENC: instead of being a passthrough.
     */
    private FieldEncryptionService realEncryptionService() {
        byte[] keyBytes = new byte[32];
        for (int i = 0; i < 32; i++) keyBytes[i] = (byte) (i + 1);
        String base64Key = Base64.getEncoder().encodeToString(keyBytes);

        FieldEncryptionService svc = new FieldEncryptionService();
        ReflectionTestUtils.setField(svc, "base64Key", base64Key);
        // Call @PostConstruct init manually
        svc.init();
        return svc;
    }

    @Nested
    class WithRealEncryption {

        private FieldEncryptionService realEnc;
        private ApiConnectorServiceImpl realService;

        @BeforeEach
        void setUpRealEnc() {
            realEnc = realEncryptionService();
            realService = new ApiConnectorServiceImpl(
                    connectorMapper, endpointMapper, objectMapper, realEnc);
        }

        /**
         * When encryption is enabled, create() must store auth_config as valid JSON
         * (not an ENC: blob) — otherwise the JSONB column rejects it.
         * Secret fields inside the JSON must be individually encrypted.
         */
        @Test
        void create_withEncryptionEnabled_authConfigRemainsValidJson() throws Exception {
            String basicAuth = "{\"username\":\"alice\",\"password\":\"s3cr3t\"}";
            ApiConnectorCreateRequest r = new ApiConnectorCreateRequest();
            r.setName("basic-svc");
            r.setBaseUrl("https://api.example.com/");
            r.setAuthType("basic");
            r.setAuthConfig(basicAuth);
            r.setEnabled(true);

            ApiConnector entity = realService.create(r);

            String storedAuthConfig = entity.getAuthConfig();

            // (a) Must be parseable JSON — the JSONB constraint
            JsonNode node = objectMapper.readTree(storedAuthConfig);
            assertThat(node.isObject())
                    .as("stored auth_config must be a JSON object, not an ENC: blob")
                    .isTrue();

            // (b) Non-secret field (username) must remain plaintext
            assertThat(node.get("username").asText())
                    .as("non-secret field 'username' must not be encrypted")
                    .isEqualTo("alice");

            // (c) Secret field (password) must be individually encrypted (ENC: prefix)
            String storedPassword = node.get("password").asText();
            assertThat(storedPassword)
                    .as("secret field 'password' must start with ENC:")
                    .startsWith("ENC:");
        }

        /**
         * Round-trip: secret written by create() must be recoverable to its
         * original plaintext when the in-memory entity is used via applyAuth()
         * (which calls decryptJsonFields internally).
         */
        @Test
        void create_withEncryptionEnabled_roundTripRestoresOriginalSecret() throws Exception {
            String apiKeyAuth = "{\"apiKey\":\"my-super-secret-key\",\"headerName\":\"X-API-Key\"}";
            ApiConnectorCreateRequest r = new ApiConnectorCreateRequest();
            r.setName("apikey-svc");
            r.setBaseUrl("https://api.example.com/");
            r.setAuthType("api_key");
            r.setAuthConfig(apiKeyAuth);
            r.setEnabled(true);

            ApiConnector entity = realService.create(r);

            // Simulate a read-back: the entity's auth_config now has ENC: in secret fields.
            // Decrypt only the secret fields to get back the original JSON.
            String decrypted = realEnc.decryptJsonFields(
                    entity.getAuthConfig(),
                    ApiConnectorServiceImpl.AUTH_SECRET_FIELDS_FOR_TEST);

            JsonNode decryptedNode = objectMapper.readTree(decrypted);
            assertThat(decryptedNode.get("apiKey").asText())
                    .as("round-trip decryption must restore original secret value")
                    .isEqualTo("my-super-secret-key");
            assertThat(decryptedNode.get("headerName").asText())
                    .as("non-secret field must be unchanged after round-trip")
                    .isEqualTo("X-API-Key");
        }

        /**
         * update() must apply the same in-envelope encryption as create().
         */
        @Test
        void update_withEncryptionEnabled_authConfigRemainsValidJson() throws Exception {
            ApiConnector existing = new ApiConnector();
            existing.setPid("p");
            when(connectorMapper.findByPid(1L, "p")).thenReturn(existing);

            String bearerAuth = "{\"token\":\"bearer-token-value\"}";
            ApiConnectorCreateRequest r = new ApiConnectorCreateRequest();
            r.setName("bearer-svc");
            r.setBaseUrl("https://api.example.com/");
            r.setAuthType("bearer");
            r.setAuthConfig(bearerAuth);
            r.setEnabled(true);

            ApiConnector updated = realService.update("p", r);

            JsonNode node = objectMapper.readTree(updated.getAuthConfig());
            assertThat(node.isObject())
                    .as("updated auth_config must be valid JSON")
                    .isTrue();
            assertThat(node.get("token").asText())
                    .as("secret field 'token' must be individually encrypted")
                    .startsWith("ENC:");
        }

        /**
         * When authConfig is null or empty, create() must not throw.
         */
        @Test
        void create_withNullAuthConfig_doesNotThrow() {
            ApiConnectorCreateRequest r = new ApiConnectorCreateRequest();
            r.setName("no-auth-svc");
            r.setBaseUrl("https://api.example.com/");
            r.setAuthType("none");
            r.setAuthConfig(null);
            r.setEnabled(true);

            ApiConnector entity = realService.create(r);
            assertThat(entity.getAuthConfig()).isNull();
        }

        /**
         * Passthrough: when authConfig contains no secret fields, stored JSON
         * is identical to the input (possibly re-serialized but semantically equal).
         */
        @Test
        void create_withNoSecretFields_jsonIsPreserved() throws Exception {
            String noSecrets = "{\"headerName\":\"X-Tenant\",\"scope\":\"read:data\"}";
            ApiConnectorCreateRequest r = new ApiConnectorCreateRequest();
            r.setName("no-secret-svc");
            r.setBaseUrl("https://api.example.com/");
            r.setAuthType("api_key");
            r.setAuthConfig(noSecrets);
            r.setEnabled(true);

            ApiConnector entity = realService.create(r);
            JsonNode stored = objectMapper.readTree(entity.getAuthConfig());

            assertThat(stored.get("headerName").asText()).isEqualTo("X-Tenant");
            assertThat(stored.get("scope").asText()).isEqualTo("read:data");
            // No field should have an ENC: prefix since none are in AUTH_SECRET_FIELDS
            stored.fields().forEachRemaining(e ->
                    assertThat(e.getValue().asText())
                            .as("field '%s' should not be encrypted", e.getKey())
                            .doesNotStartWith("ENC:")
            );
        }
    }
}
