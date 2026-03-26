package com.auraboot.framework.connector.service;

import com.auraboot.framework.connector.dto.ApiConnectorCreateRequest;
import com.auraboot.framework.connector.entity.ApiConnector;
import com.auraboot.framework.connector.entity.ApiConnectorEndpoint;
import com.auraboot.framework.connector.mapper.ApiConnectorEndpointMapper;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.service.TenantService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ApiConnectorService.
 *
 * @since 5.1.0
 */
@DisplayName("P5-5b: API Connector Service Integration Tests")
class ApiConnectorServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ApiConnectorService apiConnectorService;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private ApiConnectorEndpointMapper endpointMapper;

    @BeforeEach
    void setUp() {
        super.setupTenantContext();
    }

    // ==================== Connector CRUD ====================

    @Test
    @DisplayName("Create connector with NONE auth")
    void testCreateConnectorNoneAuth() {
        ApiConnectorCreateRequest request = buildRequest("Public API", "https://api.example.com");

        ApiConnector connector = apiConnectorService.create(request);

        assertNotNull(connector);
        assertNotNull(connector.getPid());
        assertEquals("Public API", connector.getName());
        assertEquals("https://api.example.com", connector.getBaseUrl());
        assertEquals("none", connector.getAuthType());
        assertTrue(connector.getEnabled());
    }

    @Test
    @DisplayName("Create connector with API_KEY auth")
    void testCreateConnectorApiKey() {
        ApiConnectorCreateRequest request = new ApiConnectorCreateRequest();
        request.setName("API Key Connector");
        request.setBaseUrl("https://api.example.com/v2");
        request.setAuthType("api_key");
        request.setAuthConfig("{\"headerName\":\"X-Api-Key\",\"apiKey\":\"test-key-123\"}");
        request.setTimeoutMs(15000);

        ApiConnector connector = apiConnectorService.create(request);

        assertNotNull(connector);
        assertEquals("api_key", connector.getAuthType());
        assertNotNull(connector.getAuthConfig());
        assertEquals(15000, connector.getTimeoutMs());
    }

    @Test
    @DisplayName("Create connector with BEARER auth")
    void testCreateConnectorBearer() {
        ApiConnectorCreateRequest request = new ApiConnectorCreateRequest();
        request.setName("Bearer Connector");
        request.setBaseUrl("https://secure.example.com");
        request.setAuthType("bearer");
        request.setAuthConfig("{\"token\":\"eyJhbGciOiJIUzI1NiJ9.test\"}");

        ApiConnector connector = apiConnectorService.create(request);

        assertNotNull(connector);
        assertEquals("bearer", connector.getAuthType());
    }

    @Test
    @DisplayName("Create connector with BASIC auth")
    void testCreateConnectorBasic() {
        ApiConnectorCreateRequest request = new ApiConnectorCreateRequest();
        request.setName("Basic Auth Connector");
        request.setBaseUrl("https://basic.example.com");
        request.setAuthType("basic");
        request.setAuthConfig("{\"username\":\"user\",\"password\":\"pass\"}");

        ApiConnector connector = apiConnectorService.create(request);

        assertNotNull(connector);
        assertEquals("basic", connector.getAuthType());
    }

    @Test
    @DisplayName("Create connector with default headers and retry policy")
    void testCreateWithHeadersAndRetry() {
        ApiConnectorCreateRequest request = new ApiConnectorCreateRequest();
        request.setName("Full Config Connector");
        request.setBaseUrl("https://api.example.com");
        request.setDefaultHeaders("{\"Accept\":\"application/json\",\"X-Tenant\":\"test\"}");
        request.setRetryPolicy("{\"maxRetries\":3,\"backoffMs\":1000}");

        ApiConnector connector = apiConnectorService.create(request);

        assertNotNull(connector);
        assertNotNull(connector.getDefaultHeaders());
        assertNotNull(connector.getRetryPolicy());
    }

    @Test
    @DisplayName("Get connector by PID")
    void testGetByPid() {
        ApiConnector created = createTestConnector("Find Me");
        ApiConnector found = apiConnectorService.getByPid(created.getPid());

        assertNotNull(found);
        assertEquals(created.getPid(), found.getPid());
        assertEquals("Find Me", found.getName());
    }

    @Test
    @DisplayName("List all connectors")
    void testListAll() {
        createTestConnector("List A");
        createTestConnector("List B");

        List<ApiConnector> all = apiConnectorService.listAll();
        assertTrue(all.size() >= 2);
    }

    @Test
    @DisplayName("Update connector")
    void testUpdateConnector() {
        ApiConnector created = createTestConnector("Update Me");

        ApiConnectorCreateRequest updateReq = new ApiConnectorCreateRequest();
        updateReq.setName("Updated Connector");
        updateReq.setBaseUrl("https://updated.example.com");
        updateReq.setAuthType("bearer");
        updateReq.setAuthConfig("{\"token\":\"new-token\"}");
        updateReq.setTimeoutMs(20000);

        ApiConnector updated = apiConnectorService.update(created.getPid(), updateReq);

        assertEquals("Updated Connector", updated.getName());
        assertEquals("https://updated.example.com", updated.getBaseUrl());
        assertEquals("bearer", updated.getAuthType());
        assertEquals(20000, updated.getTimeoutMs());
    }

    @Test
    @DisplayName("Delete connector")
    void testDeleteConnector() {
        ApiConnector created = createTestConnector("Delete Me");
        apiConnectorService.delete(created.getPid());

        assertNull(apiConnectorService.getByPid(created.getPid()));
    }

    @Test
    @DisplayName("Update non-existent connector throws exception")
    void testUpdateNonExistent() {
        ApiConnectorCreateRequest req = buildRequest("x", "https://x.com");
        assertThrows(IllegalArgumentException.class, () ->
                apiConnectorService.update("nonexistent-pid", req));
    }

    @Test
    @DisplayName("Connector access is isolated by tenant")
    void testTenantIsolationForReadAndList() {
        ApiConnector connector = createTestConnector("Tenant Scoped Connector");
        Tenant otherTenant = createAdditionalTenant();

        switchToTenant(otherTenant);

        assertAll(
                () -> assertNull(apiConnectorService.getByPid(connector.getPid())),
                () -> assertTrue(apiConnectorService.listAll().stream()
                        .noneMatch(item -> connector.getPid().equals(item.getPid())))
        );
    }

    @Test
    @DisplayName("Cross-tenant update delete and invoke are rejected")
    void testTenantIsolationForMutations() {
        ApiConnector connector = createTestConnector("Tenant Scoped Mutation");
        createEndpoint(connector.getPid(), "health-check");
        Tenant otherTenant = createAdditionalTenant();

        switchToTenant(otherTenant);

        ApiConnectorCreateRequest updateReq = buildRequest("Updated Elsewhere", "https://updated.example.com");

        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> apiConnectorService.update(connector.getPid(), updateReq)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> apiConnectorService.invoke(connector.getPid(), "health-check", Map.of())),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> apiConnectorService.testConnection(connector.getPid())),
                () -> assertDoesNotThrow(() -> apiConnectorService.delete(connector.getPid()))
        );

        switchToTenant(getTestTenant());
        assertNotNull(apiConnectorService.getByPid(connector.getPid()),
                "Deleting from another tenant must not remove the original connector");
    }

    // ==================== Connection Test ====================

    @Test
    @DisplayName("Test connection to unreachable URL returns false")
    void testConnectionUnreachable() {
        ApiConnectorCreateRequest request = new ApiConnectorCreateRequest();
        request.setName("Unreachable");
        request.setBaseUrl("https://nonexistent.invalid.example.com");
        request.setTimeoutMs(3000);
        ApiConnector connector = apiConnectorService.create(request);

        boolean result = apiConnectorService.testConnection(connector.getPid());
        assertFalse(result);
    }

    @Test
    @DisplayName("Test connection to non-existent connector throws exception")
    void testConnectionNonExistent() {
        assertThrows(IllegalArgumentException.class, () ->
                apiConnectorService.testConnection("nonexistent-pid"));
    }

    @Test
    @DisplayName("Create connector rejects unsafe internal URL")
    void testCreateRejectsUnsafeUrl() {
        ApiConnectorCreateRequest request = buildRequest("Unsafe Connector", "http://127.0.0.1:6443");

        assertThrows(IllegalArgumentException.class, () -> apiConnectorService.create(request));
    }

    @Test
    @DisplayName("Update connector rejects unsafe internal URL")
    void testUpdateRejectsUnsafeUrl() {
        ApiConnector connector = createTestConnector("Safe Connector");
        ApiConnectorCreateRequest request = buildRequest("Unsafe Update", "http://127.0.0.1:6443");

        assertThrows(IllegalArgumentException.class,
                () -> apiConnectorService.update(connector.getPid(), request));
    }

    // ==================== Invoke ====================

    @Test
    @DisplayName("Invoke with non-existent connector throws exception")
    void testInvokeNonExistentConnector() {
        assertThrows(IllegalArgumentException.class, () ->
                apiConnectorService.invoke("nonexistent-pid", "endpoint", Map.of()));
    }

    @Test
    @DisplayName("Invoke with non-existent endpoint throws exception")
    void testInvokeNonExistentEndpoint() {
        ApiConnector connector = createTestConnector("Invoke Test");

        // Endpoint doesn't exist for this connector
        assertThrows(Exception.class, () ->
                apiConnectorService.invoke(connector.getPid(), "nonexistent-endpoint", Map.of()));
    }

    // ==================== Helpers ====================

    private ApiConnector createTestConnector(String name) {
        ApiConnectorCreateRequest request = buildRequest(name, "https://api.example.com");
        return apiConnectorService.create(request);
    }

    private ApiConnectorCreateRequest buildRequest(String name, String baseUrl) {
        ApiConnectorCreateRequest request = new ApiConnectorCreateRequest();
        request.setName(name);
        request.setBaseUrl(baseUrl);
        request.setTimeoutMs(5000);
        return request;
    }

    private Tenant createAdditionalTenant() {
        Tenant tenant = new Tenant();
        tenant.setPid(UniqueIdGenerator.generate());
        tenant.setName("integration-test-tenant-" + UniqueIdGenerator.generate().substring(0, 8));
        tenant.setDisplayName("Integration Test Tenant Extra");
        tenant.setStatus("active");
        tenant.setContactEmail("extra-" + UniqueIdGenerator.generate().substring(0, 6) + "@integration-test.com");
        tenant.setDescription("Additional tenant for connector isolation tests");
        tenant.setDeletedFlag(false);
        tenant.setCreatedAt(Instant.now());
        tenant.setUpdatedAt(Instant.now());
        return tenantService.createTenant(tenant);
    }

    private void switchToTenant(Tenant tenant) {
        MetaContext.setContext(tenant.getId(), getTestUser().getId(), getTestUser().getPid(), getTestUser().getUserName());
    }

    private void createEndpoint(String connectorPid, String endpointCode) {
        ApiConnectorEndpoint endpoint = new ApiConnectorEndpoint();
        endpoint.setConnectorPid(connectorPid);
        endpoint.setCode(endpointCode);
        endpoint.setName("Health Check");
        endpoint.setMethod("get");
        endpoint.setPath("/health");
        endpointMapper.insert(endpoint);
    }
}
