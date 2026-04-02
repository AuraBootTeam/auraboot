package com.auraboot.framework.agent.provider;

import com.auraboot.framework.agent.service.McpServerConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ToolProvider implementation for MCP (Model Context Protocol) servers.
 * <p>
 * Discovers tools from all active MCP servers registered for the tenant,
 * and routes tool execution via JSON-RPC 2.0 to the correct server.
 * <p>
 * Tool codes follow the format {@code mcp:{serverName}:{toolName}}.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class McpToolProvider implements ToolProvider {

    private static final String PROVIDER_CODE = "mcp";
    private static final String PREFIX = "mcp:";
    private static final int EXPECTED_PARTS = 3; // mcp:serverName:toolName

    private final McpClient mcpClient;
    private final McpServerConfigService mcpServerConfigService;

    @Override
    public String providerCode() {
        return PROVIDER_CODE;
    }

    @Override
    public boolean handles(String toolCode) {
        return toolCode != null && toolCode.startsWith(PREFIX);
    }

    /**
     * Discover tools from all active MCP servers for the tenant.
     * One server failure does not block discovery from other servers.
     */
    @Override
    public List<ToolDefinition> discover(ToolDiscoveryContext ctx) {
        if (ctx == null || ctx.getTenantId() == null) {
            return List.of();
        }

        List<Map<String, Object>> servers = mcpServerConfigService.listActiveServers(ctx.getTenantId());
        if (servers.isEmpty()) {
            return List.of();
        }

        List<ToolDefinition> allTools = new ArrayList<>();
        for (Map<String, Object> server : servers) {
            String serverName = (String) server.get("server_name");
            String serverUrl = (String) server.get("server_url");

            try {
                List<McpClient.McpToolInfo> tools = mcpClient.listTools(serverUrl);
                for (McpClient.McpToolInfo tool : tools) {
                    String toolCode = PREFIX + serverName + ":" + tool.getName();
                    allTools.add(ToolDefinition.builder()
                            .toolCode(toolCode)
                            .toolName(tool.getName())
                            .description(tool.getDescription())
                            .providerCode(PROVIDER_CODE)
                            .toolType("mcp")
                            .parameterSchema(tool.getInputSchema())
                            .build());
                }
                log.debug("Discovered {} tools from MCP server '{}'", tools.size(), serverName);
            } catch (Exception e) {
                log.warn("Failed to discover tools from MCP server '{}' at {}: {}",
                        serverName, serverUrl, e.getMessage());
                // Continue with other servers — one failure should not block all discovery
            }
        }
        return allTools;
    }

    /**
     * Execute a tool on the appropriate MCP server.
     * Tool code format: {@code mcp:{serverName}:{toolName}}.
     */
    @Override
    public ProviderExecutionResult execute(Long tenantId, String toolCode, Map<String, Object> params) {
        long startTime = System.currentTimeMillis();

        // Parse tool code
        String[] parts = toolCode.split(":", EXPECTED_PARTS);
        if (parts.length < EXPECTED_PARTS) {
            return ProviderExecutionResult.builder()
                    .success(false)
                    .errorMessage("Invalid MCP tool code format: '" + toolCode
                            + "'. Expected format: mcp:{serverName}:{toolName}")
                    .durationMs(System.currentTimeMillis() - startTime)
                    .build();
        }

        String serverName = parts[1];
        String toolName = parts[2];

        // Resolve server URL
        String serverUrl = resolveServerUrl(tenantId, serverName);
        if (serverUrl == null) {
            return ProviderExecutionResult.builder()
                    .success(false)
                    .errorMessage("MCP server '" + serverName + "' not found or inactive for tenant " + tenantId)
                    .durationMs(System.currentTimeMillis() - startTime)
                    .build();
        }

        // Execute via MCP client
        try {
            Map<String, Object> result = mcpClient.callTool(serverUrl, toolName, params);
            return ProviderExecutionResult.builder()
                    .success(true)
                    .data(result)
                    .durationMs(System.currentTimeMillis() - startTime)
                    .build();
        } catch (Exception e) {
            log.error("MCP tool execution failed: server='{}', tool='{}', error={}",
                    serverName, toolName, e.getMessage());
            return ProviderExecutionResult.builder()
                    .success(false)
                    .errorMessage("MCP tool execution failed: " + e.getMessage())
                    .durationMs(System.currentTimeMillis() - startTime)
                    .build();
        }
    }

    /**
     * Resolve server URL by server name for the given tenant.
     */
    private String resolveServerUrl(Long tenantId, String serverName) {
        List<Map<String, Object>> servers = mcpServerConfigService.listActiveServers(tenantId);
        for (Map<String, Object> server : servers) {
            if (serverName.equals(server.get("server_name"))) {
                return (String) server.get("server_url");
            }
        }
        return null;
    }
}
