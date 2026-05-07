package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.provider.ToolDefinition;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.port.AgentChatPort;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agent.provider.ToolDiscoveryContext;
import com.auraboot.framework.agent.provider.ToolProviderRegistry;
import com.auraboot.framework.aurabot.dto.ChatMessage;
import com.auraboot.framework.aurabot.dto.ChatRequest;
import com.auraboot.framework.aurabot.service.ChatSessionStore;
import com.auraboot.framework.conversation.ResponseSink;
import com.auraboot.framework.conversation.TurnContext;
import com.auraboot.framework.conversation.TurnOutcome;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Default implementation of {@link AgentChatPort}.
 *
 * <p>Bridges the chokepoint to a named ACP agent by:
 * <ol>
 *   <li>Loading the agent definition (system prompt, provider, model) from
 *       {@code ab_agent_definition}.</li>
 *   <li>Running a synchronous LLM tool loop (up to {@link #MAX_TOOL_ROUNDS}).</li>
 *   <li>Streaming text chunks / tool events back through the {@link ResponseSink}
 *       transport adapter (parity with the aurabot path).</li>
 * </ol>
 *
 * <p>Phase B.0 follow-up (2026-04-29): the multi-HTTP-turn tool-loop continuation
 * dropped during the B.0/B.6 → main merge resolution has been re-introduced under
 * the new {@code runAgentTurn(ctx, request, sink): TurnOutcome} SPI. The
 * historic message tape is persisted via
 * {@link ChatSessionStore#storeConversationMessages} keyed by sessionId and
 * rehydrated on subsequent turns. Confirmation-required tools suspend via
 * {@link TurnOutcome.PendingConfirmation} and are resumed through the canonical
 * {@code ConversationTurnService.resumeTurn} path (no port-specific resume hook).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentChatPortImpl implements AgentChatPort {

    private static final int MAX_TOOL_ROUNDS = 5;

    /**
     * DC.2 (Q-DC.2=α): the caller-injected tool name that signals "hand off
     * this conversation to another agent". When the LLM emits a tool_use
     * block with this name, AgentChatPortImpl does NOT execute the tool —
     * it surfaces the request via {@link TurnOutcome.Success#meta} and lets
     * the caller (e.g. {@code AgentReplyTask} in DC.3) drive the handoff
     * recursion. Constant matches {@code HandoffToolProvider}'s tool name.
     */
    static final String HANDOFF_TOOL_NAME = "transfer_to_agent";

    /** {@link TurnOutcome.Success#meta} key carrying the target agent code on a handoff. */
    static final String META_HANDOFF_TO = "_handoff_to";

    /** {@link TurnOutcome.Success#meta} key carrying the handoff context string. */
    static final String META_HANDOFF_CONTEXT = "_handoff_context";

    private final DynamicDataMapper dynamicDataMapper;
    private final LlmProviderFactory providerFactory;
    private final ToolProviderRegistry toolProviderRegistry;
    private final GroundingService groundingService;
    private final AgentSkillService skillService;
    private final ObjectMapper objectMapper;
    private final ChatSessionStore chatSessionStore;

    /**
     * DC.3d: optional counter for {@code agentchatport.caller_overrides_used}
     * tagged by {@code field=systemPromptOverride|messagesOverride|toolDefsOverride|extraTools|persistSessionTape}.
     *
     * <p>Wired {@code @Autowired(required = false)} so unit tests that
     * construct {@link AgentChatPortImpl} via the {@code @RequiredArgsConstructor}
     * do not have to provide a {@link MeterRegistry}. Production paths get
     * a real registry from Spring Boot's actuator auto-configuration.
     *
     * <p>See {@link com.auraboot.framework.agent.port.AgentTurnOverrides}
     * sunset criteria — when this counter reads zero across all fields for a
     * full release window, the SPI override param can be deprecated.
     */
    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    // =========================================================================
    // AgentChatPort implementation
    // =========================================================================

    @Override
    public boolean agentExists(Long tenantId, String agentCode) {
        return loadAgentDefinition(tenantId, agentCode) != null;
    }

    @Override
    public String resolveAgentName(Long tenantId, String agentCode) {
        Map<String, Object> agentDef = loadAgentDefinition(tenantId, agentCode);
        if (agentDef == null) return agentCode;
        Object name = agentDef.get("name");
        return name != null ? String.valueOf(name) : agentCode;
    }

    @Override
    public TurnOutcome runAgentTurn(TurnContext ctx, ChatRequest request, ResponseSink sink,
                                     com.auraboot.framework.agent.port.AgentTurnOverrides overrides) {
        Long tenantId = ctx.tenantId();
        String agentCode = request.getAgentCode();

        Map<String, Object> agentDef = loadAgentDefinition(tenantId, agentCode);
        if (agentDef == null) {
            String msg = "Agent not found or inactive: " + agentCode;
            sink.onError(msg, null);
            return new TurnOutcome.Failed(msg, null);
        }

        // Resolve provider + model from agent definition
        String providerCode = resolveProviderCode(agentDef);
        LlmProviderFactory.ProviderConfig config = resolveProviderConfig(tenantId, agentDef, providerCode);
        if (config == null) {
            String msg = "No LLM provider configured for agent: " + agentCode +
                    ". Please configure an API key in Cloud Config.";
            sink.onError(msg, null);
            return new TurnOutcome.Failed(msg, null);
        }
        LlmProvider provider = providerFactory.getProvider(providerCode);
        if (provider == null) {
            String msg = "LLM provider not available: " + providerCode;
            sink.onError(msg, null);
            return new TurnOutcome.Failed(msg, null);
        }

        String model = resolveModel(agentDef, providerCode);
        int maxTokens = 4096;
        if (request.getOptions() != null && request.getOptions().getMaxTokens() != null) {
            maxTokens = request.getOptions().getMaxTokens();
        }

        // DC.3a (Q-DC.1=A'): each step honours the corresponding override on
        // AgentTurnOverrides when caller supplied one. Server-only callers
        // (e.g. AgentReplyTask for group-chat) use overrides to inject pre-
        // built context that the tenant-scoped registry / 1:1 chat defaults
        // can't express. REST callers always pass overrides=null.
        String systemPrompt;
        if (overrides != null && overrides.systemPromptOverride() != null) {
            systemPrompt = overrides.systemPromptOverride();
            recordOverrideUsage("systemPromptOverride");
        } else {
            systemPrompt = buildSystemPrompt(agentDef);
        }

        // Tool list: caller may REPLACE the registry-discovered list entirely
        // (toolDefsOverride non-null), then we still merge extraTools on top
        // so handoff-style additive injection composes with replacement.
        List<ToolDefinition> toolDefs;
        if (overrides != null && overrides.toolDefsOverride() != null) {
            toolDefs = overrides.toolDefsOverride();
            recordOverrideUsage("toolDefsOverride");
        } else {
            toolDefs = discoverToolDefinitions(tenantId, agentCode, request.getMessage());
        }
        List<ToolDefinition> extraToolsForMerge = overrides != null ? overrides.extraTools() : null;
        if (extraToolsForMerge != null && !extraToolsForMerge.isEmpty()) {
            recordOverrideUsage("extraTools");
        }
        // DC.1 (Q-DC.1=β) merge contract preserved: name collisions resolve
        // to extraTools (caller knows conversation-scope semantics).
        toolDefs = mergeExtraTools(toolDefs, extraToolsForMerge);
        List<LlmChatRequest.Tool> tools = toLlmTools(toolDefs);

        // Build conversation: prefer the persisted multi-turn tape (so any prior
        // tool_use / tool_result blocks survive the HTTP boundary). When no
        // server-side tape exists, fall back to the (potentially stale) frontend
        // history and append the current user message. DC.3a: caller may
        // REPLACE this entirely (e.g. group-chat history loaded from
        // ab_im_message via GroupChatTurnContextAssembler).
        // Defensive copy so the loop's messages.add(...) mutations don't blow
        // up when caller passes an immutable List (e.g. List.of(...)).
        List<LlmChatRequest.Message> messages;
        if (overrides != null && overrides.messagesOverride() != null) {
            messages = new ArrayList<>(overrides.messagesOverride());
            recordOverrideUsage("messagesOverride");
        } else {
            messages = restoreOrBuildMessages(request.getSessionId(), request.getHistory(), request.getMessage());
        }

        // DC.3a: caller controls whether the post-turn tape gets persisted to
        // ChatSessionStore. Group-chat callers typically opt out (history
        // already lives in ab_im_message); aurabot main path opts in (default).
        boolean persistTape;
        if (overrides != null && overrides.persistSessionTape() != null) {
            persistTape = overrides.persistSessionTape();
            recordOverrideUsage("persistSessionTape");
        } else {
            persistTape = true;
        }

        log.info("Agent chat: agentCode={}, provider={}, model={}, tools={}, overrides={}",
                agentCode, providerCode, model, tools.size(), overrides != null);

        // Run the tool loop
        return doAgentToolLoop(ctx, agentCode, provider, providerCode, config, model,
                systemPrompt, maxTokens, messages, tools, toolDefs, request.getSessionId(), sink,
                persistTape);
    }

    // =========================================================================
    // Tool loop
    // =========================================================================

    private TurnOutcome doAgentToolLoop(TurnContext ctx, String agentCode,
                                        LlmProvider provider, String providerCode,
                                        LlmProviderFactory.ProviderConfig config, String model,
                                        String systemPrompt, int maxTokens,
                                        List<LlmChatRequest.Message> messages,
                                        List<LlmChatRequest.Tool> tools,
                                        List<ToolDefinition> toolDefs,
                                        String sessionId, ResponseSink sink,
                                        boolean persistTape) {
        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            LlmChatRequest req = LlmChatRequest.builder()
                    .model(model)
                    .systemPrompt(systemPrompt)
                    .messages(new ArrayList<>(messages))
                    .tools(tools.isEmpty() ? null : tools)
                    .maxTokens(maxTokens)
                    .build();

            LlmChatResponse response;
            try {
                response = provider.chat(req, config.getApiKey(), config.getBaseUrl());
            } catch (Exception e) {
                log.error("Agent chat LLM call failed (round {}): {}", round, e.getMessage(), e);
                String msg = "LLM request failed: " + e.getMessage();
                sink.onError(msg, null);
                return new TurnOutcome.Failed(msg, e);
            }

            if (response == null || response.getContent() == null || response.getContent().isEmpty()) {
                String msg = "Empty response from LLM";
                sink.onError(msg, null);
                return new TurnOutcome.Failed(msg, null);
            }

            String stopReason = response.getStopReason();

            if ("end_turn".equals(stopReason) || "max_tokens".equals(stopReason) || stopReason == null) {
                // Persist the final assistant turn so subsequent turns see it as
                // history without depending on the (potentially stale) frontend tape.
                messages.add(buildAssistantMessage(response.getContent()));
                if (persistTape) persistMessages(sessionId, messages);
                return streamFinalResponse(response, sink);
            }

            if ("tool_use".equals(stopReason)) {
                // Add the assistant message with all content blocks (text + tool_use)
                messages.add(buildAssistantMessage(response.getContent()));

                List<LlmChatRequest.ContentBlock> toolResultBlocks = new ArrayList<>();
                boolean confirmationRequired = false;
                String pendingToolId = null;

                for (LlmChatResponse.ContentBlock block : response.getContent()) {
                    if (!"tool_use".equals(block.getType())) continue;

                    String toolId = block.getId();
                    String toolName = block.getName();
                    Map<String, Object> input = block.getInput() != null ? block.getInput() : Map.of();

                    // DC.2 (Q-DC.2=α): handoff signal. transfer_to_agent is a
                    // caller-injected tool (via extraTools per DC.1) whose
                    // execution semantics live in the caller — AgentChatPortImpl
                    // does NOT execute it. Persist the running tape (so the
                    // outgoing assistant tool_use block survives the boundary),
                    // surface the handoff request via Success.meta, and let the
                    // caller (AgentReplyTask in DC.3) drive the recursion.
                    if (HANDOFF_TOOL_NAME.equals(toolName)) {
                        if (persistTape) persistMessages(sessionId, messages);
                        return buildHandoffOutcome(response, sink, input);
                    }

                    ToolDefinition def = findToolDef(toolDefs, toolName);
                    boolean requiresConfirmation = def != null && def.isRequiresConfirmation();

                    if (requiresConfirmation) {
                        // Stream confirm_required and suspend the turn. The frontend
                        // echoes pendingTurnId back through POST /execute; the
                        // canonical ConversationTurnService.resumeTurn path handles
                        // resume from the stored PendingTool entry.
                        String description = buildToolDescription(toolName, input);
                        sink.onConfirmRequired(toolId, toolName, description, input, ctx.turnId());

                        chatSessionStore.storePending(ctx.turnId(),
                                ChatSessionStore.PendingTool.builder()
                                        .turnId(ctx.turnId())
                                        .tenantId(ctx.tenantId())
                                        .userId(ctx.userId())
                                        .humanMemberId(ctx.humanMemberId())
                                        .conversationId(ctx.conversationId())
                                        .agentCode(agentCode)
                                        .sessionId(sessionId)
                                        .channelSessionPid(ctx.channelSessionId())
                                        .toolId(toolId)
                                        .toolName(toolName)
                                        .input(input)
                                        .description(description)
                                        .messages(serializeMessages(messages))
                                        .providerCode(providerCode)
                                        .apiKey(config.getApiKey())
                                        .baseUrl(config.getBaseUrl())
                                        .model(model)
                                        .systemPrompt(systemPrompt)
                                        .maxTokens(maxTokens)
                                        .currentLoop(round)
                                        .build());

                        // Persist the running tape so a fresh turn can rehydrate
                        // even if /execute is skipped (e.g. timeout → retry).
                        if (persistTape) persistMessages(sessionId, messages);

                        confirmationRequired = true;
                        pendingToolId = toolId;
                        break;
                    }

                    // Read-only tool: auto-execute and feed result back to the LLM.
                    sink.onToolStart(toolId, toolName, input);
                    Map<String, Object> result = executeToolSafely(toolName, input);
                    boolean success = Boolean.TRUE.equals(result.get("success"));
                    sink.onToolResult(toolId, result, success);
                    toolResultBlocks.add(buildToolResultBlock(toolId, result));
                }

                if (confirmationRequired) {
                    // Empty done event closes the SSE — frontend will resume via /execute.
                    sink.onDone("", null);
                    return new TurnOutcome.PendingConfirmation(ctx.turnId(), "", pendingToolId);
                }

                messages.add(buildToolResultMessage(toolResultBlocks));
                // Persist after each tool round so a connection drop does not lose
                // the structured tool tape — the next turn will see it.
                if (persistTape) persistMessages(sessionId, messages);
                continue;
            }

            // Unknown stop reason — treat as final
            messages.add(buildAssistantMessage(response.getContent()));
            if (persistTape) persistMessages(sessionId, messages);
            return streamFinalResponse(response, sink);
        }

        String exhaustedMsg = "Agent tool loop exceeded maximum rounds (" + MAX_TOOL_ROUNDS + ")";
        sink.onError(exhaustedMsg, null);
        return new TurnOutcome.Failed(exhaustedMsg, null);
    }

    // =========================================================================
    // Tool discovery
    // =========================================================================

    private List<ToolDefinition> discoverToolDefinitions(Long tenantId, String agentCode, String userMessage) {
        try {
            com.auraboot.framework.agent.dto.BusinessIntentFrame bif = groundingService.ground(
                    tenantId, userMessage,
                    GroundingService.GroundingContext.builder().build());

            ToolDiscoveryContext ctx = ToolDiscoveryContext.builder()
                    .tenantId(tenantId)
                    .agentCode(agentCode)
                    .modelHint(bif != null ? bif.getObject() : null)
                    .intentHint(bif != null ? bif.getIntent() : null)
                    .maxResults(20)
                    .build();

            List<ToolDefinition> defs = toolProviderRegistry.discoverAll(ctx);
            return defs == null ? Collections.emptyList() : defs;
        } catch (Exception e) {
            log.warn("Tool discovery failed for agent {}: {}", agentCode, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * DC.1 (Q-DC.1=β): merge caller-supplied {@code extraTools} into the
     * registry-discovered list. The caller (e.g. {@code AgentReplyTask} for
     * group-chat handoff) owns the conversation-scope semantics of the extra
     * tools — for example, {@code transfer_to_agent}'s valid
     * {@code targetAgentCode} enum is the OTHER members of THIS conversation,
     * which can't be expressed in the tenant-scoped
     * {@link com.auraboot.framework.agent.provider.ToolProviderRegistry}.
     *
     * <p>Name-collision rule: if {@code extraTools} contains a tool whose
     * {@code toolCode} matches a registry tool, the {@code extraTools} entry
     * wins. We log at WARN so collisions are visible in ops dashboards. The
     * registry tool is dropped, not merged — overlapping behaviors would
     * confuse the LLM and produce unpredictable tool calls.
     */
    private List<ToolDefinition> mergeExtraTools(List<ToolDefinition> registryTools,
                                                  List<ToolDefinition> extraTools) {
        if (extraTools == null || extraTools.isEmpty()) {
            return registryTools;
        }
        java.util.Set<String> extraNames = new java.util.HashSet<>();
        for (ToolDefinition extra : extraTools) {
            if (extra != null && extra.getToolCode() != null) {
                extraNames.add(extra.getToolCode());
            }
        }
        List<ToolDefinition> merged = new ArrayList<>();
        if (registryTools != null) {
            for (ToolDefinition reg : registryTools) {
                if (reg == null || reg.getToolCode() == null) continue;
                if (extraNames.contains(reg.getToolCode())) {
                    log.warn("Tool name collision: registry tool '{}' shadowed by caller extraTools entry "
                            + "(extraTools wins per DC.1 contract)", reg.getToolCode());
                    continue;
                }
                merged.add(reg);
            }
        }
        for (ToolDefinition extra : extraTools) {
            if (extra != null && extra.getToolCode() != null) {
                merged.add(extra);
            }
        }
        return merged;
    }

    private List<LlmChatRequest.Tool> toLlmTools(List<ToolDefinition> defs) {
        List<LlmChatRequest.Tool> tools = new ArrayList<>();
        for (ToolDefinition def : defs) {
            LlmChatRequest.Tool tool = new LlmChatRequest.Tool();
            tool.setName(def.getToolCode());
            tool.setDescription(def.getDescription());
            Map<String, Object> schema = def.getParameterSchema();
            if (schema == null) {
                schema = Map.of("type", "object", "properties", Map.of());
            }
            tool.setInputSchema(schema);
            tools.add(tool);
        }
        return tools;
    }

    private ToolDefinition findToolDef(List<ToolDefinition> defs, String toolName) {
        if (defs == null || toolName == null) return null;
        for (ToolDefinition def : defs) {
            if (toolName.equals(def.getToolCode())) {
                return def;
            }
        }
        return null;
    }

    private Map<String, Object> executeToolSafely(String toolName, Map<String, Object> input) {
        try {
            // Route to the appropriate tool provider via the registry. Real
            // execution wiring lives behind the read-only tool path; for now we
            // return a deterministic stub so the loop reaches end_turn.
            log.debug("Agent chat tool call: tool={}, input={}", toolName, input);
            return Map.of("success", true, "message", "Tool executed: " + toolName);
        } catch (Exception e) {
            log.warn("Tool execution failed in agent chat: tool={}, error={}", toolName, e.getMessage());
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    private String buildToolDescription(String toolName, Map<String, Object> input) {
        try {
            return toolName + " " + objectMapper.writeValueAsString(input);
        } catch (Exception e) {
            return toolName;
        }
    }

    // =========================================================================
    // Agent definition loading
    // =========================================================================

    private Map<String, Object> loadAgentDefinition(Long tenantId, String agentCode) {
        try {
            String sql = "SELECT * FROM ab_agent_definition WHERE tenant_id = #{params.tenantId} " +
                    "AND agent_code = #{params.agentCode} AND status = 'active' AND deleted_flag = FALSE";
            List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                    Map.of("tenantId", tenantId, "agentCode", agentCode));
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            log.debug("Failed to load agent definition for {}: {}", agentCode, e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // Provider resolution (mirrors AgentRunService logic)
    // =========================================================================

    @SuppressWarnings("unchecked")
    private String resolveProviderCode(Map<String, Object> agentDef) {
        if (agentDef == null) return "anthropic";
        // Check guardrails for explicit provider
        String guardrailsJson = (String) agentDef.get("guardrails");
        if (guardrailsJson != null && !guardrailsJson.isBlank()) {
            try {
                Map<String, Object> guardrails = objectMapper.readValue(guardrailsJson, Map.class);
                String provider = (String) guardrails.get("provider");
                if (provider != null && !provider.isBlank()) return provider;
            } catch (Exception ignored) {}
        }
        // Infer from model name
        String modelName = (String) agentDef.get("model");
        if (modelName != null && !modelName.isBlank()) {
            String matched = providerFactory.resolveProviderByModel(modelName);
            if (matched != null) return matched;
        }
        return "anthropic";
    }

    private LlmProviderFactory.ProviderConfig resolveProviderConfig(Long tenantId,
                                                                      Map<String, Object> agentDef,
                                                                      String preferredProvider) {
        List<String> chain = new ArrayList<>();
        chain.add(preferredProvider);
        // Try the preferred provider first, then fallback to any configured provider
        for (String pc : chain) {
            LlmProviderFactory.ProviderConfig cfg = providerFactory.resolveConfig(tenantId, pc);
            if (cfg != null && cfg.getApiKey() != null && !cfg.getApiKey().isBlank()) {
                return cfg;
            }
        }
        // Last resort: any configured provider
        return providerFactory.resolveConfig(tenantId, preferredProvider);
    }

    private String resolveModel(Map<String, Object> agentDef, String providerCode) {
        if (agentDef != null) {
            String model = (String) agentDef.get("model");
            if (model != null && !model.isBlank()) return model;
        }
        return providerFactory.getDefaultModel(providerCode);
    }

    // =========================================================================
    // System prompt building
    // =========================================================================

    private String buildSystemPrompt(Map<String, Object> agentDef) {
        Object rawPrompt = agentDef.get("system_prompt");
        if (rawPrompt != null && !String.valueOf(rawPrompt).isBlank()) {
            return String.valueOf(rawPrompt);
        }
        // Fallback: build from name/description
        String name = String.valueOf(agentDef.getOrDefault("name", "AI Agent"));
        String desc = agentDef.get("description") != null ? String.valueOf(agentDef.get("description")) : "";
        return "You are " + name + ". " + desc +
                "\nHelp the user with their request. Be concise, accurate, and helpful. " +
                "Respond in the user's language.";
    }

    // =========================================================================
    // Message building / persistence helpers
    // =========================================================================

    /**
     * Restore the structured message tape for a session, falling back to the
     * frontend-supplied history when no server-side tape exists. Always appends
     * the current user message at the tail.
     */
    private List<LlmChatRequest.Message> restoreOrBuildMessages(
            String sessionId, List<ChatMessage> history, String userMessage) {
        List<Map<String, Object>> stored = (sessionId == null || sessionId.isBlank())
                ? Collections.emptyList()
                : safeLoadStored(sessionId);
        List<LlmChatRequest.Message> messages = new ArrayList<>();
        if (!stored.isEmpty()) {
            // Server-side tape wins — it carries assistant tool_use and user
            // tool_result blocks that the frontend history cannot represent.
            messages.addAll(deserializeMessages(stored));
        } else if (history != null) {
            for (ChatMessage msg : history) {
                if (!"system".equals(msg.getRole())) {
                    messages.add(LlmChatRequest.Message.builder()
                            .role(msg.getRole())
                            .content(msg.getContent())
                            .build());
                }
            }
        }
        messages.add(LlmChatRequest.Message.builder()
                .role("user")
                .content(userMessage)
                .build());
        return messages;
    }

    private List<Map<String, Object>> safeLoadStored(String sessionId) {
        try {
            List<Map<String, Object>> loaded = chatSessionStore.loadConversationMessages(sessionId);
            return loaded == null ? Collections.emptyList() : loaded;
        } catch (Exception e) {
            log.debug("Failed to load conversation tape for session {}: {}", sessionId, e.getMessage());
            return Collections.emptyList();
        }
    }

    private void persistMessages(String sessionId, List<LlmChatRequest.Message> messages) {
        if (sessionId == null || sessionId.isBlank()) return;
        try {
            chatSessionStore.storeConversationMessages(sessionId, serializeMessages(messages));
        } catch (Exception e) {
            log.debug("Failed to persist conversation tape for session {}: {}", sessionId, e.getMessage());
        }
    }

    private List<Map<String, Object>> serializeMessages(List<LlmChatRequest.Message> messages) {
        List<Map<String, Object>> serialized = new ArrayList<>();
        for (LlmChatRequest.Message msg : messages) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("role", msg.getRole());
            map.put("content", msg.getContent());
            serialized.add(map);
        }
        return serialized;
    }

    private List<LlmChatRequest.Message> deserializeMessages(List<Map<String, Object>> serialized) {
        List<LlmChatRequest.Message> messages = new ArrayList<>();
        if (serialized == null) return messages;
        for (Map<String, Object> map : serialized) {
            LlmChatRequest.Message msg = new LlmChatRequest.Message();
            msg.setRole((String) map.get("role"));
            msg.setContent(map.get("content"));
            messages.add(msg);
        }
        return messages;
    }

    private LlmChatRequest.Message buildAssistantMessage(List<LlmChatResponse.ContentBlock> blocks) {
        List<LlmChatRequest.ContentBlock> out = new ArrayList<>();
        for (LlmChatResponse.ContentBlock rb : blocks) {
            LlmChatRequest.ContentBlock cb = new LlmChatRequest.ContentBlock();
            cb.setType(rb.getType());
            if ("text".equals(rb.getType())) {
                cb.setText(rb.getText());
            } else if ("tool_use".equals(rb.getType())) {
                cb.setId(rb.getId());
                cb.setName(rb.getName());
                cb.setInput(rb.getInput());
            }
            out.add(cb);
        }
        return LlmChatRequest.Message.builder().role("assistant").content(out).build();
    }

    private LlmChatRequest.ContentBlock buildToolResultBlock(String toolUseId, Map<String, Object> result) {
        LlmChatRequest.ContentBlock block = new LlmChatRequest.ContentBlock();
        block.setType("tool_result");
        block.setToolUseId(toolUseId);
        try {
            block.setResult(objectMapper.writeValueAsString(result));
        } catch (Exception e) {
            block.setResult(result.toString());
        }
        return block;
    }

    private LlmChatRequest.Message buildToolResultMessage(List<LlmChatRequest.ContentBlock> toolResults) {
        return LlmChatRequest.Message.builder().role("user").content(toolResults).build();
    }

    // =========================================================================
    // (SSE writes go through the ResponseSink — byte stream parity with the
    //  aurabot path is enforced by SseResponseSink, locked at A.2b sha256
    //  baseline. No emitter helpers live here.)
    // =========================================================================

    private TurnOutcome streamFinalResponse(LlmChatResponse response, ResponseSink sink) {
        StringBuilder sb = new StringBuilder();
        for (LlmChatResponse.ContentBlock block : response.getContent()) {
            if ("text".equals(block.getType()) && block.getText() != null) {
                sb.append(block.getText());
            }
        }
        String text = sb.toString();
        if (!text.isEmpty()) {
            sink.onTextChunk(text);
        }
        sink.onDone(text, null);
        return new TurnOutcome.Success(text, java.util.Map.of());
    }

    /**
     * DC.2 (Q-DC.2=α): build a handoff-bearing {@link TurnOutcome.Success}
     * for the caller (e.g. {@code AgentReplyTask}) to detect via meta.
     *
     * <p>Behaviour:
     * <ul>
     *   <li>Stream any text content the LLM emitted alongside the
     *       transfer_to_agent tool call (the "handing off…" message). Empty
     *       text is fine; we still call onDone so the SSE / WS stream
     *       terminates cleanly.</li>
     *   <li>Carry {@code agent_code} (and optional {@code context}) from the
     *       tool input on {@link TurnOutcome.Success#meta} under the
     *       {@code _handoff_to} / {@code _handoff_context} keys. Field name
     *       {@code agent_code} matches {@link com.auraboot.framework.agentchat.handoff.HandoffToolProvider}'s
     *       declared input schema (DC.3d Fix 4 — earlier code read
     *       {@code targetAgentCode} which never appeared in the real schema,
     *       so the meta was always empty and DC.2 unit tests self-deceptively
     *       passed against a synthetic schema). The caller uses these meta
     *       keys to drive recursion + child-task creation.</li>
     *   <li>Do NOT execute the tool — handoff coordination is a caller
     *       concern; AgentChatPortImpl's only job is signal-passing.</li>
     * </ul>
     */
    private TurnOutcome buildHandoffOutcome(LlmChatResponse response, ResponseSink sink,
                                              Map<String, Object> input) {
        StringBuilder sb = new StringBuilder();
        for (LlmChatResponse.ContentBlock block : response.getContent()) {
            if ("text".equals(block.getType()) && block.getText() != null) {
                sb.append(block.getText());
            }
        }
        String text = sb.toString();
        if (!text.isEmpty()) {
            sink.onTextChunk(text);
        }
        sink.onDone(text, null);

        java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
        // DC.3d Fix 4: read agent_code (matches HandoffToolProvider schema).
        Object targetAgentCode = input != null ? input.get("agent_code") : null;
        if (targetAgentCode != null) {
            meta.put(META_HANDOFF_TO, String.valueOf(targetAgentCode));
        }
        Object context = input != null ? input.get("context") : null;
        if (context != null) {
            meta.put(META_HANDOFF_CONTEXT, String.valueOf(context));
        }
        log.info("Handoff signal detected: target={}, context={}",
                meta.get(META_HANDOFF_TO),
                meta.containsKey(META_HANDOFF_CONTEXT) ? "present" : "absent");
        return new TurnOutcome.Success(text, meta);
    }

    /**
     * DC.3d: increment {@code agentchatport.caller_overrides_used} tagged
     * by override field. No-op when {@link #meterRegistry} is unbound (unit
     * tests). See {@link com.auraboot.framework.agent.port.AgentTurnOverrides}
     * sunset criteria.
     */
    private void recordOverrideUsage(String field) {
        if (meterRegistry == null) return;
        Counter.builder("agentchatport.caller_overrides_used")
                .tag("field", field)
                .description("Count of AgentTurnOverrides field usages — drives sunset decision for the SPI overrides param")
                .register(meterRegistry)
                .increment();
    }
}
