package com.auraboot.framework.agent.provider;

import com.auraboot.framework.agent.service.McpServerConfigChangedEvent;
import com.auraboot.framework.agent.service.McpServerConfigService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpToolProviderCacheTest {

    @Test
    void repeatedDiscoveryUsesCacheAndPersistsRealSyncFacts() {
        McpClient client = mock(McpClient.class);
        McpServerConfigService config = mock(McpServerConfigService.class);
        McpClient.McpToolInfo tool = new McpClient.McpToolInfo();
        tool.setName("search");
        tool.setDescription("Search");
        tool.setInputSchema(Map.of("type", "object"));
        when(config.listActiveServers(7L)).thenReturn(List.of(serverRow()));
        when(client.listTools(any(McpServerTarget.class))).thenReturn(List.of(tool));
        McpToolProvider provider =
                new McpToolProvider(client, config, Duration.ofMinutes(1));
        ToolDiscoveryContext context =
                ToolDiscoveryContext.builder().tenantId(7L).build();

        assertThat(provider.discover(context)).hasSize(1);
        assertThat(provider.discover(context)).hasSize(1);

        verify(client, times(1)).listTools(any(McpServerTarget.class));
        verify(config, times(1)).updateSyncResult(7L, "server-pid", 1);

        provider.onServerConfigChanged(
                new McpServerConfigChangedEvent(7L, "server-pid", "updated"));
        assertThat(provider.discover(context)).hasSize(1);
        verify(client, times(2)).listTools(any(McpServerTarget.class));
    }

    @Test
    void executionUsesDirectTenantAndServerLookup() {
        McpClient client = mock(McpClient.class);
        McpServerConfigService config = mock(McpServerConfigService.class);
        when(config.findActiveServer(7L, "vendor")).thenReturn(serverRow());
        when(client.callTool(any(McpServerTarget.class), org.mockito.ArgumentMatchers.eq("search"),
                org.mockito.ArgumentMatchers.eq(Map.of("q", "x"))))
                .thenReturn(Map.of("content", List.of()));
        McpToolProvider provider = new McpToolProvider(client, config);

        ProviderExecutionResult result =
                provider.execute(7L, "mcp:vendor:search", Map.of("q", "x"));

        assertThat(result.isSuccess()).isTrue();
        verify(config).findActiveServer(7L, "vendor");
        verify(config, never()).listActiveServers(7L);
    }

    @Test
    void discoveryFailureRedactsConfiguredSecretsBeforePersistence() {
        McpClient client = mock(McpClient.class);
        McpServerConfigService config = mock(McpServerConfigService.class);
        Map<String, Object> row = new java.util.LinkedHashMap<>(serverRow());
        row.put("auth_config", Map.of("token", "auth-secret-value"));
        row.put("stdio_env", Map.of("VENDOR_TOKEN", "env-secret-value"));
        when(config.listActiveServers(7L)).thenReturn(List.of(row));
        when(client.listTools(any(McpServerTarget.class))).thenThrow(
                new McpClient.McpClientException(
                        "remote echoed auth-secret-value and env-secret-value"));
        McpToolProvider provider = new McpToolProvider(client, config);

        assertThat(provider.discover(
                ToolDiscoveryContext.builder().tenantId(7L).build())).isEmpty();

        verify(config).updateSyncFailure(
                eq(7L), eq("server-pid"),
                argThat(message -> message.contains("[redacted]")
                        && !message.contains("auth-secret-value")
                        && !message.contains("env-secret-value")));
    }

    private static Map<String, Object> serverRow() {
        return Map.of(
                "tenant_id", 7L,
                "pid", "server-pid",
                "server_name", "vendor",
                "server_url", "https://vendor.example/mcp",
                "transport_type", "streamable_http");
    }
}
