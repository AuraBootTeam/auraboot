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

    /**
     * Tools this provider requires to be offered to the model on <em>every</em> turn of a matching
     * channel, whatever the user said.
     *
     * <p>Ordinary discovery is driven by grounding: the user's message yields an intent and a set of
     * candidate skills, and tools are looked up from those. It then drops every non-read-only tool
     * when the intent reads as a question ({@code ToolDiscoveryPortImpl#isReadIntent}). Both are the
     * right defaults — they keep the tool list small and stop a mutating tool from firing on "how
     * many leads do we have?".
     *
     * <p>They are also exactly wrong for a small class of tools: the ones that exist <em>because</em>
     * the model cannot answer. "Hand this visitor to a human" is needed precisely when the visitor
     * asked a question the model cannot answer — a read intent, which is when grounding-driven
     * discovery would have dropped it. Such a tool must be present regardless, or it is present only
     * when it is not needed.
     *
     * <p>Tools returned here bypass both the grounding filter and the read-intent filter, so keep the
     * list tiny and gate it on {@link ToolDiscoveryContext#getChannel()} — a tool that is always on
     * for every channel is a tool in every prompt, and a bigger prompt makes the model worse at
     * everything else. Default: none.
     */
    default List<ToolDefinition> discoverAlwaysOn(ToolDiscoveryContext ctx) {
        return List.of();
    }

    /** Execute a tool by code with the given parameters. */
    ProviderExecutionResult execute(Long tenantId, String toolCode, Map<String, Object> params);

    /** Check if this provider handles the given tool code. */
    boolean handles(String toolCode);
}
