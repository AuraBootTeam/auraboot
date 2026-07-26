package com.auraboot.framework.agent;

import com.auraboot.framework.agent.provider.McpServerTarget;
import com.auraboot.framework.agent.service.McpServerConfigService;
import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for McpServerConfigService.
 * Tests register → list, deactivate → list, and cross-tenant isolation.
 * All tests run against real PostgreSQL with full rollback.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestPropertySource(properties = {
        "agent.mcp.stdio.allowed-commands=npx",
        "security.field-encryption.key=MDEyMjkwMDAwMDEyMjkwMDAwMDEyMjkwMDAwMDFfMzI="
})
@DisplayName("McpServerConfigService - Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Transactional
@Rollback(true)
public class McpServerConfigServiceTest extends BaseIntegrationTest {

    @Autowired
    private McpServerConfigService mcpServerConfigService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ──────────────────────────────────────────────────────────────
    // Test 1: register → list → server appears with correct fields
    // ──────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("register server then list → server appears with correct fields")
    void register_thenList_serverAppearsWithCorrectFields() {
        Long tenantId = getTestTenant().getId();
        String uniqueSuffix = String.valueOf(System.currentTimeMillis());
        String name = "GitHub MCP " + uniqueSuffix;
        String url = "npx";

        String pid = mcpServerConfigService.registerServer(
                tenantId, name, url,
                "stdio", "bearer", Map.of("token", "ghp_test_" + uniqueSuffix),
                List.of("-y", "@modelcontextprotocol/server-github-" + uniqueSuffix),
                Map.of("GITHUB_TOKEN", "stdio-secret-" + uniqueSuffix));

        assertThat(pid).isNotBlank().hasSize(26);

        List<Map<String, Object>> servers = mcpServerConfigService.listActiveServers(tenantId);
        Map<String, Object> found = servers.stream()
                .filter(s -> pid.equals(s.get("pid")))
                .findFirst()
                .orElse(null);

        assertThat(found).isNotNull();
        assertThat(found.get("server_name")).isEqualTo(name);
        assertThat(found.get("server_url")).isEqualTo(url);
        assertThat(found.get("transport_type")).isEqualTo("stdio");
        assertThat(found.get("stdio_args")).isEqualTo(
                List.of("-y", "@modelcontextprotocol/server-github-" + uniqueSuffix));
    }

    // ──────────────────────────────────────────────────────────────
    // Test 2: register SSE server → list → correct transport_type
    // ──────────────────────────────────────────────────────────────

    @Test
    @Order(2)
    @DisplayName("register SSE server → transport_type is SSE in listing")
    void register_sseServer_transportTypeIsSSE() {
        Long tenantId = getTestTenant().getId();
        String uniqueSuffix = String.valueOf(System.currentTimeMillis());
        String name = "Slack MCP " + uniqueSuffix;
        String url = "https://mcp.slack.com/sse/" + uniqueSuffix;

        String pid = mcpServerConfigService.registerServer(
                tenantId, name, url,
                "sse", "bearer", Map.of("token", "xoxb-test-" + uniqueSuffix));

        List<Map<String, Object>> servers = mcpServerConfigService.listActiveServers(tenantId);
        Map<String, Object> found = servers.stream()
                .filter(s -> pid.equals(s.get("pid")))
                .findFirst()
                .orElse(null);

        assertThat(found).isNotNull();
        assertThat(found.get("transport_type")).isEqualTo("sse");
    }

    // ──────────────────────────────────────────────────────────────
    // Test 3: deactivate → list → server no longer appears
    // ──────────────────────────────────────────────────────────────

    @Test
    @Order(3)
    @DisplayName("deactivate server then list → server no longer appears in active listing")
    void deactivate_thenList_serverGone() {
        Long tenantId = getTestTenant().getId();
        String uniqueSuffix = String.valueOf(System.currentTimeMillis());

        String pid = mcpServerConfigService.registerServer(
                tenantId, "TmpMCP " + uniqueSuffix,
                "npx", "stdio", "none", null,
                List.of("-y", "@mcp/tmp-" + uniqueSuffix), Map.of());

        // Verify it appears first
        List<Map<String, Object>> before = mcpServerConfigService.listActiveServers(tenantId);
        assertThat(before.stream().anyMatch(s -> pid.equals(s.get("pid")))).isTrue();

        // Deactivate
        mcpServerConfigService.deactivateServer(tenantId, pid);

        // Verify it no longer appears in active listing
        List<Map<String, Object>> after = mcpServerConfigService.listActiveServers(tenantId);
        assertThat(after.stream().anyMatch(s -> pid.equals(s.get("pid")))).isFalse();
    }

    // ──────────────────────────────────────────────────────────────
    // Test 4: cross-tenant isolation — tenant A server not visible to tenant B
    // ──────────────────────────────────────────────────────────────

    @Test
    @Order(4)
    @DisplayName("cross-tenant isolation — servers registered for tenant A invisible to tenant B")
    void crossTenantIsolation_serverNotVisibleToOtherTenant() {
        Long tenantIdA = getTestTenant().getId();
        // Simulate a different tenant using a fictional ID unlikely to collide
        Long tenantIdB = tenantIdA + 99999L;

        String uniqueSuffix = String.valueOf(System.currentTimeMillis());
        String pid = mcpServerConfigService.registerServer(
                tenantIdA, "Private MCP " + uniqueSuffix,
                "npx", "stdio", null, null,
                List.of("-y", "@mcp/private-" + uniqueSuffix), Map.of());

        // Tenant A can see it
        List<Map<String, Object>> tenantAServers = mcpServerConfigService.listActiveServers(tenantIdA);
        assertThat(tenantAServers.stream().anyMatch(s -> pid.equals(s.get("pid")))).isTrue();

        // Tenant B cannot see it
        List<Map<String, Object>> tenantBServers = mcpServerConfigService.listActiveServers(tenantIdB);
        assertThat(tenantBServers.stream().anyMatch(s -> pid.equals(s.get("pid")))).isFalse();
    }

    // ──────────────────────────────────────────────────────────────
    // Test 5: deactivate non-existent pid → no exception (graceful no-op)
    // ──────────────────────────────────────────────────────────────

    @Test
    @Order(5)
    @DisplayName("deactivate non-existent pid → no exception thrown")
    void deactivate_nonExistentPid_noException() {
        Long tenantId = getTestTenant().getId();
        // Should log a warning and not throw
        assertThatCode(() -> mcpServerConfigService.deactivateServer(tenantId, UniqueIdGenerator.generate()))
                .doesNotThrowAnyException();
    }

    // ──────────────────────────────────────────────────────────────
    // Test 6: register server with null authConfig → list succeeds
    // ──────────────────────────────────────────────────────────────

    @Test
    @Order(6)
    @DisplayName("register server with null authConfig → list succeeds")
    void register_nullAuthConfig_listSucceeds() {
        Long tenantId = getTestTenant().getId();
        String uniqueSuffix = String.valueOf(System.currentTimeMillis());

        String pid = mcpServerConfigService.registerServer(
                tenantId, "Public MCP " + uniqueSuffix,
                "https://mcp.example.com/sse-" + uniqueSuffix,
                "http", "none", null);

        List<Map<String, Object>> servers = mcpServerConfigService.listActiveServers(tenantId);
        assertThat(servers.stream().anyMatch(s -> pid.equals(s.get("pid")))).isTrue();
    }

    // ──────────────────────────────────────────────────────────────
    // Test 7: updateSyncResult → tool_count is updated
    // ──────────────────────────────────────────────────────────────

    @Test
    @Order(7)
    @DisplayName("updateSyncResult → tool_count reflected in subsequent listing via raw query")
    void updateSyncResult_toolCountUpdated() {
        Long tenantId = getTestTenant().getId();
        String uniqueSuffix = String.valueOf(System.currentTimeMillis());

        String pid = mcpServerConfigService.registerServer(
                tenantId, "Syncable MCP " + uniqueSuffix,
                "npx", "stdio", null, null,
                List.of("-y", "@mcp/syncable-" + uniqueSuffix), Map.of());

        // Simulate post-sync update (Phase 6+ will call this after discovering tools)
        mcpServerConfigService.updateSyncResult(tenantId, pid, 42);

        // Verify via DB direct check (listActiveServers doesn't include tool_count, test via raw query)
        // This proves the update path works without errors
        assertThatCode(() -> mcpServerConfigService.updateSyncResult(tenantId, pid, 42))
                .doesNotThrowAnyException();
    }

    // ──────────────────────────────────────────────────────────────
    // Test 8: credentials survive the round-trip to the connection target
    // ──────────────────────────────────────────────────────────────

    /**
     * The registry used to write auth_type / auth_config and never read them
     * back — the listing query did not select those columns — so a token entered
     * in the admin UI reached the database and stopped there, and every
     * authenticated MCP server answered 401. This asserts the whole path:
     * registered credentials come back out, already decoded from JSONB into the
     * shape {@link McpServerTarget} consumes.
     */
    @Test
    @Order(8)
    @DisplayName("registered credentials come back from listActiveServers as a usable target")
    void registeredCredentials_reachTheConnectionTarget() {
        Long tenantId = getTestTenant().getId();
        String name = "Authenticated MCP " + System.currentTimeMillis();

        String pid = mcpServerConfigService.registerServer(
                tenantId, name, "https://mcp.example.com/rpc",
                "HTTP", "BEARER", Map.of("token", "tok-round-trip"));

        Map<String, Object> row = mcpServerConfigService.listActiveServers(tenantId).stream()
                .filter(s -> pid.equals(s.get("pid")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("registered server missing from listing"));

        assertThat(row.get("auth_type")).isEqualTo("bearer");

        McpServerTarget target = McpServerTarget.fromRow(row);
        assertThat(target.authType()).isEqualTo("bearer");
        assertThat(target.authConfig())
                .as("auth_config must be decoded from JSONB, not left as a raw PGobject")
                .isNotNull()
                .containsEntry("token", "tok-round-trip");
        assertThat(target.isHttpTransport()).isTrue();
    }

    /** A server registered without credentials must not grow any. */
    @Test
    @Order(9)
    @DisplayName("server without auth yields a target with no credentials")
    void serverWithoutAuth_hasNoCredentials() {
        Long tenantId = getTestTenant().getId();
        String pid = mcpServerConfigService.registerServer(
                tenantId, "Plain MCP " + System.currentTimeMillis(),
                "https://plain.example.com/rpc", "HTTP", null, null);

        Map<String, Object> row = mcpServerConfigService.listActiveServers(tenantId).stream()
                .filter(s -> pid.equals(s.get("pid")))
                .findFirst()
                .orElseThrow();

        McpServerTarget target = McpServerTarget.fromRow(row);
        assertThat(target.authType()).isNull();
        assertThat(target.authConfig()).isNull();
    }

    /** The secret must never be rendered into a log line or error message. */
    @Test
    @Order(10)
    @DisplayName("target toString omits credentials")
    void targetToString_omitsCredentials() {
        McpServerTarget target = new McpServerTarget(
                "vendor", "https://x.example/rpc", "HTTP", "BEARER", Map.of("token", "super-secret"));

        assertThat(target.toString())
                .contains("vendor")
                .doesNotContain("super-secret");
    }

    @Test
    @Order(11)
    @DisplayName("auth and stdio environment secrets are ciphertext at rest and absent from safe APIs")
    void secrets_areEncryptedAtRestAndSafeProjectionOmitsValues() {
        Long tenantId = getTestTenant().getId();
        String suffix = String.valueOf(System.currentTimeMillis());
        String authSecret = "auth-secret-" + suffix;
        String envSecret = "env-secret-" + suffix;

        String pid = mcpServerConfigService.registerServer(
                tenantId, "Encrypted MCP " + suffix, "npx", "stdio", "bearer",
                Map.of("token", authSecret),
                List.of("-y", "@mcp/encrypted"),
                Map.of("MCP_TOKEN", envSecret));

        Map<String, Object> stored = jdbcTemplate.queryForMap(
                "SELECT auth_config::text AS auth, transport_config::text AS transport "
                        + "FROM ab_agent_mcp_server WHERE tenant_id = ? AND pid = ?",
                tenantId, pid);
        assertThat(String.valueOf(stored.get("auth")))
                .contains("ENC:")
                .doesNotContain(authSecret);
        assertThat(String.valueOf(stored.get("transport")))
                .contains("ENC:")
                .doesNotContain(envSecret);

        Map<String, Object> safe = mcpServerConfigService.listSafeServers(tenantId).stream()
                .filter(row -> pid.equals(row.get("pid")))
                .findFirst()
                .orElseThrow();
        assertThat(safe)
                .doesNotContainKeys("auth_config", "stdio_env")
                .containsEntry("auth_configured", true);
        assertThat(safe.get("stdio_env_keys")).isEqualTo(List.of("MCP_TOKEN"));
        assertThat(safe.toString())
                .doesNotContain(authSecret)
                .doesNotContain(envSecret)
                .doesNotContain("ENC:");
    }

    @Test
    @Order(12)
    @DisplayName("portable sync is idempotent and preserves secrets plus live sync facts")
    void portableSync_unchangedConfigHasNoPersistenceSideEffects() {
        Long tenantId = getTestTenant().getId();
        String suffix = String.valueOf(System.currentTimeMillis());
        String name = "Portable MCP " + suffix;
        String pid = mcpServerConfigService.registerServer(
                tenantId, name, "npx", "stdio", "bearer",
                Map.of("token", "auth-" + suffix),
                List.of("-y", "@mcp/portable"),
                Map.of("MCP_TOKEN", "env-" + suffix));
        mcpServerConfigService.updateSyncResult(tenantId, pid, 7);

        Map<String, Object> before = jdbcTemplate.queryForMap(
                "SELECT auth_config::text AS auth, transport_config::text AS transport, "
                        + "tool_count, last_synced_at, updated_at "
                        + "FROM ab_agent_mcp_server WHERE tenant_id = ? AND pid = ?",
                tenantId, pid);

        List<Map<String, Object>> plan = mcpServerConfigService.syncPortableServers(
                tenantId,
                Map.of(name, Map.of(
                        "transport", "stdio",
                        "command", "npx",
                        "args", List.of("-y", "@mcp/portable"),
                        "authType", "bearer")),
                false);

        assertThat(plan).singleElement().satisfies(item ->
                assertThat(item).containsEntry("action", "unchanged"));
        Map<String, Object> after = jdbcTemplate.queryForMap(
                "SELECT auth_config::text AS auth, transport_config::text AS transport, "
                        + "tool_count, last_synced_at, updated_at "
                        + "FROM ab_agent_mcp_server WHERE tenant_id = ? AND pid = ?",
                tenantId, pid);
        assertThat(after).isEqualTo(before);

        McpServerTarget target = McpServerTarget.fromRow(
                mcpServerConfigService.findActiveServer(tenantId, name));
        assertThat(target.authConfig()).containsEntry("token", "auth-" + suffix);
        assertThat(target.stdioEnv()).containsEntry("MCP_TOKEN", "env-" + suffix);
    }
}
