package com.auraboot.framework.agent.provider;

import java.util.List;
import java.util.Map;

/**
 * Unified interface for tool discovery and execution.
 * Each tool source (DSL, Platform, MCP, Custom) implements this interface.
 */
public interface ToolProvider {

    /** Unique provider identifier: "dsl", "platform", "mcp", "custom" */
    String providerCode();

    /** Discover available tools in this provider, filtered by context. */
    List<ToolDefinition> discover(ToolDiscoveryContext ctx);

    /** Execute a tool by code with the given parameters. */
    ProviderExecutionResult execute(Long tenantId, String toolCode, Map<String, Object> params);

    /** Check if this provider handles the given tool code. */
    boolean handles(String toolCode);
}
