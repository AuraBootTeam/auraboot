package com.auraboot.framework.agent.provider;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for McpToolProvider with full JSON-RPC client.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Provider identity and routing</li>
 *   <li>Discovery with no MCP servers returns empty</li>
 *   <li>Execution with missing/invalid server fails gracefully</li>
 *   <li>Invalid tool code format handling</li>
 * </ul>
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@Transactional
@Rollback(true)
class McpToolProviderFullTest extends BaseIntegrationTest {

    @Autowired
    private McpToolProvider provider;

    // ========== providerCode() ==========

    @Test
    void providerCode_isMcp() {
        assertThat(provider.providerCode()).isEqualTo("mcp");
    }

    // ========== handles() ==========

    @Test
    void handles_mcpPrefix_returnsTrue() {
        assertThat(provider.handles("mcp:github:search_repos")).isTrue();
        assertThat(provider.handles("mcp:local:read_file")).isTrue();
        assertThat(provider.handles("mcp:a:b")).isTrue();
    }

    @Test
    void handles_nonMcpPrefix_returnsFalse() {
        assertThat(provider.handles("platform.list_models")).isFalse();
        assertThat(provider.handles("cmd:create")).isFalse();
        assertThat(provider.handles("dsl.something")).isFalse();
        assertThat(provider.handles(null)).isFalse();
        assertThat(provider.handles("")).isFalse();
    }

    // ========== discover() ==========

    @Test
    void discover_withNoServers_returnsEmptyList() {
        // Test environment has no MCP servers registered, so discovery should return empty
        ToolDiscoveryContext ctx = ToolDiscoveryContext.builder()
                .tenantId(getTestTenant().getId())
                .build();

        List<ToolDefinition> tools = provider.discover(ctx);

        assertThat(tools).isNotNull();
        assertThat(tools).isEmpty();
    }

    @Test
    void discover_withNullContext_returnsEmptyList() {
        List<ToolDefinition> tools = provider.discover(null);
        assertThat(tools).isNotNull();
        assertThat(tools).isEmpty();
    }

    @Test
    void discover_withNullTenantId_returnsEmptyList() {
        ToolDiscoveryContext ctx = ToolDiscoveryContext.builder()
                .tenantId(null)
                .build();

        List<ToolDefinition> tools = provider.discover(ctx);

        assertThat(tools).isNotNull();
        assertThat(tools).isEmpty();
    }

    // ========== execute() — error cases ==========

    @Test
    void execute_withNoServer_failsGracefully() {
        // No MCP server named 'nonexistent' exists for the test tenant
        ProviderExecutionResult result = provider.execute(
                getTestTenant().getId(),
                "mcp:nonexistent:some_tool",
                Map.of("query", "test"));

        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("not found");
        assertThat(result.getErrorMessage()).contains("nonexistent");
        assertThat(result.getDurationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void execute_invalidToolCodeFormat_failsGracefully() {
        // Only one part after 'mcp:' — missing tool name
        ProviderExecutionResult result = provider.execute(
                getTestTenant().getId(),
                "mcp:onlyonepart",
                Map.of());

        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("Invalid MCP tool code format");
        assertThat(result.getErrorMessage()).contains("mcp:onlyonepart");
        assertThat(result.getDurationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void execute_barePrefix_failsGracefully() {
        // Just "mcp:" with nothing after
        ProviderExecutionResult result = provider.execute(
                getTestTenant().getId(),
                "mcp:",
                Map.of());

        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getDurationMs()).isGreaterThanOrEqualTo(0);
    }
}
