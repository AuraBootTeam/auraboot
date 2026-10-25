package com.auraboot.framework.agent.port;

import java.util.List;
import java.util.Map;

/**
 * Port interface for tool discovery via ToolProviderRegistry.
 * <p>
 * Allows AuraBot chat to discover provider-aware tools without compile-time
 * coupling to a specific registry implementation. Execution is intentionally not
 * exposed here; all chat tool calls must enter the canonical ToolLoopService.
 * <p>
 * The registry-backed implementation registers as a Spring bean in the shared AI runtime.
 */
public interface ToolDiscoveryPort {

    /**
     * Only the tools a provider requires on every turn of this channel — nothing discovered.
     *
     * <p>For a RAG-only channel the answer must come from retrieved knowledge, so no business tool
     * (execute_sql, chat-bi, fill_form) may be offered. That is not a reason to drop the channel's
     * always-on fallback too: "hand this visitor to a human" is needed precisely when the model
     * cannot answer, and a customer-facing bot with no way out is worse than one with no tools.
     *
     * @param tenantId tenant scope
     * @param channel  request channel; providers gate their always-on tools on it
     * @return always-on tools for this channel, possibly empty
     */
    List<ToolDef> discoverAlwaysOnTools(Long tenantId, String channel);

    /**
     * Discover available tools matching the given criteria.
     *
     * @param tenantId        current tenant ID
     * @param candidateSkills list of candidate skill codes from grounding (may be empty)
     * @param modelHint       target model code hint (nullable)
     * @param intentHint      resolved intent hint (nullable, e.g., "create", "query")
     * @param maxTools        maximum number of tools to return
     * @param channel         request channel (e.g. {@code cs_widget}, {@code web}); lets a provider
     *                        contribute always-on tools for its own channel. Nullable.
     * @return list of matching tool definitions, ordered by relevance. Always-on tools
     *         ({@link com.auraboot.framework.agent.provider.ToolProvider#discoverAlwaysOn}) come
     *         first and are exempt from both the grounding and the read-intent filters.
     */
    List<ToolDef> discoverTools(Long tenantId, List<String> candidateSkills, String modelHint, String intentHint,
                                int maxTools, String channel);

    /**
     * Definition of a discoverable tool.
     *
     * @param code        tool code including provider prefix (e.g., "cmd:crm_lead_create")
     * @param name        human-readable tool name
     * @param description tool description for LLM function-calling
     * @param inputSchema JSON Schema describing the tool's input parameters
     * @param readOnly    true if the tool only reads data (no side effects)
     * @param toolType    canonical ToolLoopService tool type
     * @param sourceCode  canonical source code used by the executor
     * @param requiresApproval whether Approval Gate must run before execution
     * @param requiresConfirmation whether user confirmation is required
     * @param riskLevel risk level carried from provider metadata
     * @param confirmationPolicy provider confirmation policy
     */
    record ToolDef(
            String code,
            String name,
            String description,
            Map<String, Object> inputSchema,
            boolean readOnly,
            String toolType,
            String sourceCode,
            boolean requiresApproval,
            boolean requiresConfirmation,
            String riskLevel,
            String confirmationPolicy
    ) {
        public ToolDef(String code,
                       String name,
                       String description,
                       Map<String, Object> inputSchema,
                       boolean readOnly) {
            this(code, name, description, inputSchema, readOnly,
                    null, null, false, false, null, null);
        }
    }
}
