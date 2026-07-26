package com.auraboot.framework.agent.handler;

import com.auraboot.framework.agent.service.McpServerConfigService;
import com.auraboot.framework.meta.service.CommandHandlerContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class McpServerCommandHandlerTest {

    @Test
    void createRoutesTransientSecretsThroughEncryptedServiceBoundary() {
        McpServerConfigService service = mock(McpServerConfigService.class);
        when(service.registerServer(
                7L, "GitHub", "node", "stdio", "bearer",
                Map.of("token", "secret"),
                List.of("fixture.mjs"),
                Map.of("TOKEN", "env-secret")))
                .thenReturn("new-pid");
        McpServerCommandHandler handler =
                new McpServerCommandHandler(service, new ObjectMapper());
        CommandHandlerContext context = CommandHandlerContext.builder()
                .tenantId(7L)
                .commandCode("acp:create_mcp_server")
                .payload(Map.of(
                        "server_name", "GitHub",
                        "server_url", "node",
                        "transport_type", "stdio",
                        "auth_type", "bearer",
                        "mcp_auth_secret", "secret",
                        "stdio_args", "[\"fixture.mjs\"]",
                        "mcp_stdio_env", "{\"TOKEN\":\"env-secret\"}"))
                .build();

        Map<String, Object> result = handler.execute(context);

        assertThat(result)
                .containsEntry("action", "create")
                .containsEntry("mcpServerPid", "new-pid");
        verify(service).registerServer(
                7L, "GitHub", "node", "stdio", "bearer",
                Map.of("token", "secret"),
                List.of("fixture.mjs"),
                Map.of("TOKEN", "env-secret"));
    }

    @Test
    void updateWithBlankSecretsPreservesExistingValues() {
        McpServerConfigService service = mock(McpServerConfigService.class);
        McpServerCommandHandler handler =
                new McpServerCommandHandler(service, new ObjectMapper());
        CommandHandlerContext context = CommandHandlerContext.builder()
                .tenantId(7L)
                .targetRecordId("existing-pid")
                .commandCode("acp:update_mcp_server")
                .payload(Map.of(
                        "server_name", "Vendor",
                        "server_url", "https://vendor.example/mcp",
                        "transport_type", "streamable_http",
                        "auth_type", "bearer"))
                .build();

        handler.execute(context);

        verify(service).updateServer(
                7L, "existing-pid", "Vendor", "https://vendor.example/mcp",
                "streamable_http", "bearer", null, null, null);
    }

    @Test
    void dryRunHasNoPersistenceSideEffects() {
        McpServerConfigService service = mock(McpServerConfigService.class);
        McpServerCommandHandler handler =
                new McpServerCommandHandler(service, new ObjectMapper());
        CommandHandlerContext context = CommandHandlerContext.builder()
                .tenantId(7L)
                .commandCode("acp:create_mcp_server")
                .dryRun(true)
                .payload(Map.of(
                        "server_name", "Public",
                        "server_url", "https://public.example/mcp",
                        "transport_type", "streamable_http",
                        "auth_type", "none"))
                .build();

        assertThat(handler.execute(context))
                .containsEntry("dryRun", true)
                .containsEntry("action", "create");
        verifyNoInteractions(service);
    }

    @Test
    void updateWithoutTargetNeverFallsBackToCreate() {
        McpServerConfigService service = mock(McpServerConfigService.class);
        McpServerCommandHandler handler =
                new McpServerCommandHandler(service, new ObjectMapper());
        CommandHandlerContext context = CommandHandlerContext.builder()
                .tenantId(7L)
                .commandCode("acp:update_mcp_server")
                .payload(Map.of(
                        "server_name", "Vendor",
                        "server_url", "https://vendor.example/mcp",
                        "transport_type", "streamable_http"))
                .build();

        assertThatThrownBy(() -> handler.execute(context))
                .hasMessageContaining("pid is required");
        verifyNoInteractions(service);
    }
}
