package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.dto.BusinessIntentFrame;
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
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final Pattern NAMED_QUERY_PARAM_PATTERN =
            Pattern.compile("#\\{params\\.([A-Za-z0-9_]+)}");
    private static final Set<String> NAMED_QUERY_SYSTEM_PARAMS =
            Set.of("tenantId", "currentUserId", "currentUserPid", "page", "pageSize", "offset", "limit");

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

    /**
     * Named-agent tool execution must use the same guarded kernel as ACP runs:
     * unknown-tool rejection, Tool ACL, BIF risk escalation, approval gate, tool
     * stats, and trace handling all live behind {@link ToolLoopService}.
     *
     * <p>Optional only to keep isolated unit construction cheap; if Spring cannot
     * wire it, tool execution fails closed instead of falling back to a direct
     * provider call.
     */
    @Autowired(required = false)
    private ToolLoopService toolLoopService;

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
    public Map<String, Object> executeApprovedPendingTool(Long tenantId, String approvalPid) {
        if (approvalPid == null || approvalPid.isBlank()) {
            return Map.of("handled", false);
        }

        ChatSessionStore.PendingTool pending = chatSessionStore.consumePending(approvalPid);
        if (pending == null) {
            return Map.of("handled", false);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("handled", true);
        response.put("approvalPid", approvalPid);
        response.put("toolName", pending.getToolName());

        if (tenantId == null || pending.getTenantId() == null || !tenantId.equals(pending.getTenantId())) {
            response.put("success", false);
            response.put("error", "Tenant mismatch for approved pending tool. No tool was executed.");
            return response;
        }
        if (toolLoopService == null) {
            response.put("success", false);
            response.put("error", "Agent tool execution kernel is not available. No tool was executed.");
            return response;
        }
        if (pending.getAgentToolDefinitions() == null || pending.getAgentToolDefinitions().isEmpty()) {
            response.put("success", false);
            response.put("error", "Approved pending tool has no tool definition snapshot. No tool was executed.");
            return response;
        }

        try {
            List<AgentToolDefinition> approvedDefs = markToolApproved(
                    pending.getAgentToolDefinitions(), pending.getToolName());
            String rawResult = toolLoopService.executeToolCall(
                    tenantId,
                    pending.getRunPid(),
                    pending.getTaskPid(),
                    pending.getAgentCode(),
                    pending.getToolName(),
                    pending.getInput() != null ? pending.getInput() : Map.of(),
                    approvedDefs,
                    null);
            Map<String, Object> result = normalizeToolLoopResult(rawResult);
            response.put("success", Boolean.TRUE.equals(result.get("success")));
            response.put("result", result);
            if (result.get("error") != null) {
                response.put("error", result.get("error"));
            }
            return response;
        } catch (Exception e) {
            log.warn("Approved pending tool execution failed: errorType={}", e.getClass().getSimpleName());
            response.put("success", false);
            response.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            return response;
        }
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
            toolDefs = discoverToolDefinitions(tenantId, ctx.userId(), agentCode, request.getMessage(), agentDef);
        }
        List<ToolDefinition> extraToolsForMerge = overrides != null ? overrides.extraTools() : null;
        if (extraToolsForMerge != null && !extraToolsForMerge.isEmpty()) {
            recordOverrideUsage("extraTools");
        }
        // DC.1 (Q-DC.1=β) merge contract preserved: name collisions resolve
        // to extraTools (caller knows conversation-scope semantics).
        toolDefs = mergeExtraTools(toolDefs, extraToolsForMerge);
        toolDefs = toLlmFacingToolDefinitions(toolDefs);
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
                    boolean auraBotSkill = isAuraBotSkillTool(def);
                    boolean requiresConfirmation = def != null && def.isRequiresConfirmation();

                    if (auraBotSkill) {
                        sink.onToolStart(toolId, toolName, input);
                        Map<String, Object> result = executeToolSafely(ctx, agentCode, toolName, input, toolDefs);
                        boolean success = Boolean.TRUE.equals(result.get("success"));
                        if (isAuraBotSkillPreviewPending(result)) {
                            sink.onConfirmRequired(toolId, toolName, buildToolDescription(toolName, input),
                                    input, ctx.turnId());
                            storeAuraBotSkillPendingTool(result, ctx, agentCode, sessionId, toolId, toolName, input,
                                    toolDefs, messages, providerCode, config, model, systemPrompt, maxTokens, round, persistTape);
                            confirmationRequired = true;
                            pendingToolId = toolId;
                            break;
                        }
                        sink.onToolResult(toolId, result, success);
                        if (isApprovalRequiredResult(result)) {
                            storeApprovalPendingTool(result, ctx, agentCode, sessionId, toolId, toolName, input,
                                    toolDefs, messages, providerCode, config, model, systemPrompt, maxTokens, round);
                            if (persistTape) persistMessages(sessionId, messages);
                            return buildApprovalRequiredOutcome(result, toolName, input, sink);
                        }
                        toolResultBlocks.add(buildToolResultBlock(toolId, result));
                        continue;
                    }

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

                    // Read-only tool: auto-execute through the ACP tool kernel
                    // and feed the result back to the LLM.
                    sink.onToolStart(toolId, toolName, input);
                    Map<String, Object> result = executeToolSafely(ctx, agentCode, toolName, input, toolDefs);
                    boolean success = Boolean.TRUE.equals(result.get("success"));
                    sink.onToolResult(toolId, result, success);
                    if (isApprovalRequiredResult(result)) {
                        storeApprovalPendingTool(result, ctx, agentCode, sessionId, toolId, toolName, input,
                                toolDefs, messages, providerCode, config, model, systemPrompt, maxTokens, round);
                        if (persistTape) persistMessages(sessionId, messages);
                        return buildApprovalRequiredOutcome(result, toolName, input, sink);
                    }
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

    private List<ToolDefinition> discoverToolDefinitions(Long tenantId, Long userId,
                                                         String agentCode, String userMessage,
                                                         Map<String, Object> agentDef) {
        try {
            BusinessIntentFrame bif = groundingService.ground(
                    tenantId, userMessage,
                    GroundingService.GroundingContext.builder().build());

            List<ToolDefinition> explicitDefs = discoverExplicitAgentTools(tenantId, userId, agentCode, agentDef, bif);

            ToolDiscoveryContext ctx = ToolDiscoveryContext.builder()
                    .tenantId(tenantId)
                    .userId(userId)
                    .agentCode(agentCode)
                    .modelHint(bif != null ? bif.getObject() : null)
                    .intentHint(bif != null ? bif.getIntent() : null)
                    .maxResults(20)
                    .build();

            List<ToolDefinition> defs = toolProviderRegistry.discoverAll(ctx);
            return mergeExplicitTools(explicitDefs, defs);
        } catch (Exception e) {
            log.warn("Tool discovery failed for agent {}: {}", agentCode, e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<ToolDefinition> discoverExplicitAgentTools(Long tenantId, Long userId, String agentCode,
                                                            Map<String, Object> agentDef, BusinessIntentFrame bif) {
        List<String> explicitCodes = explicitToolCodes(agentDef);
        if (explicitCodes.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> discoveryHints = new LinkedHashSet<>();
        for (String code : explicitCodes) {
            String hint = resolveExplicitToolModelHint(tenantId, code);
            if (hint != null && !hint.isBlank()) {
                discoveryHints.add(hint);
            }
        }
        if (discoveryHints.isEmpty() && bif != null && bif.getObject() != null && !bif.getObject().isBlank()) {
            discoveryHints.add(bif.getObject());
        }
        if (discoveryHints.isEmpty()) {
            discoveryHints.add(null);
        }

        Map<String, ToolDefinition> byCode = new LinkedHashMap<>();
        Set<String> explicitSet = new LinkedHashSet<>(explicitCodes);
        for (String modelHint : discoveryHints) {
            ToolDiscoveryContext ctx = ToolDiscoveryContext.builder()
                    .tenantId(tenantId)
                    .userId(userId)
                    .agentCode(agentCode)
                    .modelHint(modelHint)
                    .intentHint(bif != null ? bif.getIntent() : null)
                    .maxResults(100)
                    .build();
            List<ToolDefinition> discovered = toolProviderRegistry.discoverAll(ctx);
            if (discovered == null) {
                continue;
            }
            for (ToolDefinition def : discovered) {
                if (def == null || def.getToolCode() == null) {
                    continue;
                }
                if (explicitSet.contains(def.getToolCode())) {
                    byCode.putIfAbsent(def.getToolCode(), def);
                }
            }
        }

        for (String code : explicitCodes) {
            if (!byCode.containsKey(code)) {
                ToolDefinition direct = loadDirectExplicitTool(tenantId, code);
                if (direct != null) {
                    byCode.putIfAbsent(code, direct);
                } else {
                    log.warn("Explicit agent tool was not discoverable: agent={}, tool={}", agentCode, code);
                }
            }
        }
        return new ArrayList<>(byCode.values());
    }

    private ToolDefinition loadDirectExplicitTool(Long tenantId, String toolCode) {
        if (toolCode == null || !toolCode.startsWith("nq:")) {
            return null;
        }
        String queryCode = toolCode.substring("nq:".length());
        try {
            String sql = "SELECT code, title, description, purpose, from_sql, parameter_schema " +
                    "FROM ab_named_query " +
                    "WHERE tenant_id = #{params.tenantId} " +
                    "AND code = #{params.queryCode} " +
                    "AND status = 'published' " +
                    "AND (deleted_flag = FALSE OR deleted_flag IS NULL) " +
                    "LIMIT 1";
            List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                    Map.of("tenantId", tenantId, "queryCode", queryCode));
            if (rows == null || rows.isEmpty()) {
                return null;
            }
            Map<String, Object> row = rows.get(0);
            String title = stringValue(row.get("title"));
            String purpose = stringValue(row.get("purpose"));
            String description = purpose != null ? purpose : stringValue(row.get("description"));
            return ToolDefinition.builder()
                    .toolCode(toolCode)
                    .toolName(title != null ? title : queryCode)
                    .description(description)
                    .providerCode("dsl")
                    .toolType("dsl_query")
                    .sourceCode(queryCode)
                    .riskLevel("L0")
                    .confirmationPolicy("none")
                    .requiresApproval(false)
                    .requiresConfirmation(false)
                    .parameterSchema(buildNamedQueryParameterSchema(
                            row.get("parameter_schema"), row.get("from_sql")))
                    .build();
        } catch (Exception e) {
            log.warn("Failed to load explicit named query tool {}: {}", queryCode, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildNamedQueryParameterSchema(Object rawParameterSchema, Object fromSql) {
        Map<String, Object> parsed = parseJsonObject(rawParameterSchema);
        if (isUsableObjectSchema(parsed)) {
            return parsed;
        }

        Set<String> params = new LinkedHashSet<>();
        if (fromSql != null) {
            Matcher matcher = NAMED_QUERY_PARAM_PATTERN.matcher(String.valueOf(fromSql));
            while (matcher.find()) {
                String param = matcher.group(1);
                if (!NAMED_QUERY_SYSTEM_PARAMS.contains(param)) {
                    params.add(param);
                }
            }
        }

        Map<String, Object> properties = new LinkedHashMap<>();
        for (String param : params) {
            properties.put(param, Map.of("type", "string", "description", "NamedQuery parameter " + param));
        }
        return Map.of("type", "object", "properties", properties);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonObject(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        if (value == null) {
            return Map.of();
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(text, Map.class);
        } catch (Exception e) {
            log.debug("Failed to parse explicit named query parameter schema: {}", e.getMessage());
            return Map.of();
        }
    }

    private boolean isUsableObjectSchema(Map<String, Object> schema) {
        Object properties = schema.get("properties");
        return "object".equals(schema.get("type"))
                && properties instanceof Map<?, ?> map
                && !map.isEmpty();
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private List<ToolDefinition> mergeExplicitTools(List<ToolDefinition> explicitTools,
                                                    List<ToolDefinition> discoveredTools) {
        Map<String, ToolDefinition> byCode = new LinkedHashMap<>();
        if (explicitTools != null) {
            for (ToolDefinition tool : explicitTools) {
                if (tool != null && tool.getToolCode() != null) {
                    byCode.put(tool.getToolCode(), tool);
                }
            }
        }
        if (discoveredTools != null) {
            for (ToolDefinition tool : discoveredTools) {
                if (tool != null && tool.getToolCode() != null) {
                    byCode.putIfAbsent(tool.getToolCode(), tool);
                }
            }
        }
        return new ArrayList<>(byCode.values());
    }

    @SuppressWarnings("unchecked")
    private List<String> explicitToolCodes(Map<String, Object> agentDef) {
        if (agentDef == null || agentDef.get("tools") == null) {
            return Collections.emptyList();
        }
        Object raw = agentDef.get("tools");
        List<Object> values = new ArrayList<>();
        if (raw instanceof List<?> list) {
            values.addAll((List<Object>) list);
        } else {
            String text = String.valueOf(raw).trim();
            if (text.isBlank()) {
                return Collections.emptyList();
            }
            if (text.startsWith("[")) {
                try {
                    values.addAll(objectMapper.readValue(text, List.class));
                } catch (Exception e) {
                    log.warn("Failed to parse agent tools JSON: {}", e.getMessage());
                    return Collections.emptyList();
                }
            } else {
                for (String item : text.split(",")) {
                    values.add(item);
                }
            }
        }

        Set<String> codes = new LinkedHashSet<>();
        for (Object value : values) {
            String code = null;
            if (value instanceof Map<?, ?> map) {
                Object rawCode = map.get("toolCode");
                if (rawCode == null) rawCode = map.get("code");
                if (rawCode == null) rawCode = map.get("name");
                if (rawCode != null) code = String.valueOf(rawCode);
            } else if (value != null) {
                code = String.valueOf(value);
            }
            if (code != null && !code.isBlank()) {
                codes.add(code.trim());
            }
        }
        return new ArrayList<>(codes);
    }

    private String resolveExplicitToolModelHint(Long tenantId, String toolCode) {
        if (toolCode == null || toolCode.isBlank()) {
            return null;
        }
        if (toolCode.startsWith("cmd:")) {
            return loadCommandModelCode(tenantId, toolCode.substring("cmd:".length()));
        }
        if (toolCode.startsWith("nq:")) {
            return inferNamedQueryModelCode(tenantId, toolCode.substring("nq:".length()));
        }
        if (toolCode.startsWith("list:")) {
            return toolCode.substring("list:".length());
        }
        if (toolCode.startsWith("get:")) {
            return toolCode.substring("get:".length());
        }
        return null;
    }

    private String loadCommandModelCode(Long tenantId, String commandCode) {
        try {
            String sql = "SELECT model_code FROM ab_command_definition " +
                    "WHERE tenant_id = #{params.tenantId} " +
                    "AND code = #{params.commandCode} " +
                    "AND (deleted_flag = FALSE OR deleted_flag IS NULL) " +
                    "AND (is_current = TRUE OR is_current IS NULL) " +
                    "LIMIT 1";
            List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                    Map.of("tenantId", tenantId, "commandCode", commandCode));
            return firstString(rows, "model_code");
        } catch (Exception e) {
            log.warn("Failed to resolve command model for explicit tool {}: {}", commandCode, e.getMessage());
            return null;
        }
    }

    private String inferNamedQueryModelCode(Long tenantId, String queryCode) {
        try {
            String sql = "SELECT code FROM ab_meta_model " +
                    "WHERE tenant_id = #{params.tenantId} " +
                    "AND #{params.queryCode} LIKE code || '%' " +
                    "AND (deleted_flag = FALSE OR deleted_flag IS NULL) " +
                    "AND (is_current = TRUE OR is_current IS NULL) " +
                    "ORDER BY length(code) DESC " +
                    "LIMIT 1";
            List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                    Map.of("tenantId", tenantId, "queryCode", queryCode));
            return firstString(rows, "code");
        } catch (Exception e) {
            log.warn("Failed to infer named-query model for explicit tool {}: {}", queryCode, e.getMessage());
            return null;
        }
    }

    private String firstString(List<Map<String, Object>> rows, String key) {
        if (rows == null || rows.isEmpty() || rows.get(0) == null || rows.get(0).get(key) == null) {
            return null;
        }
        String value = String.valueOf(rows.get(0).get(key));
        return value.isBlank() ? null : value;
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

    private List<ToolDefinition> toLlmFacingToolDefinitions(List<ToolDefinition> defs) {
        if (defs == null || defs.isEmpty()) {
            return Collections.emptyList();
        }
        List<ToolDefinition> mapped = new ArrayList<>();
        for (ToolDefinition def : defs) {
            if (def == null || def.getToolCode() == null || def.getToolCode().isBlank()) {
                continue;
            }
            String originalCode = def.getToolCode();
            String llmName = toLlmSafeToolName(originalCode);
            String sourceCode = def.getSourceCode();
            if (sourceCode == null || sourceCode.isBlank()) {
                sourceCode = originalCode;
            }
            mapped.add(ToolDefinition.builder()
                    .toolCode(llmName)
                    .toolName(def.getToolName())
                    .description(def.getDescription())
                    .providerCode(def.getProviderCode())
                    .toolType(def.getToolType())
                    .sourceCode(sourceCode)
                    .riskLevel(def.getRiskLevel())
                    .confirmationPolicy(def.getConfirmationPolicy())
                    .requiresApproval(def.isRequiresApproval())
                    .requiresConfirmation(def.isRequiresConfirmation())
                    .parameterSchema(def.getParameterSchema())
                    .build());
        }
        return mapped;
    }

    private String toLlmSafeToolName(String toolCode) {
        return toolCode.replace(':', '_').replace('.', '_');
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

    private Map<String, Object> executeToolSafely(TurnContext ctx, String agentCode, String toolName,
                                                  Map<String, Object> input, List<ToolDefinition> toolDefs) {
        try {
            if (findToolDef(toolDefs, toolName) == null) {
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("success", false);
                response.put("error", unknownToolMessage(toolName, toolDefs));
                response.put("durationMs", 0L);
                return response;
            }
            if (toolLoopService == null) {
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("success", false);
                response.put("error", "Agent tool execution kernel is not available. No tool was executed.");
                response.put("durationMs", 0L);
                return response;
            }
            log.debug("Agent chat tool call via ToolLoopService: tool={}, input={}", toolName, input);
            String rawResult = toolLoopService.executeToolCall(
                    ctx.tenantId(),
                    ctx.turnId(),
                    ctx.taskPid(),
                    agentCode,
                    toolName,
                    input != null ? input : Map.of(),
                    toAgentToolDefinitions(toolDefs),
                    null);
            return normalizeToolLoopResult(rawResult);
        } catch (Exception e) {
            log.warn("Tool execution failed in agent chat: tool={}, error={}", toolName, e.getMessage());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", false);
            response.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            response.put("durationMs", 0L);
            return response;
        }
    }

    private List<AgentToolDefinition> toAgentToolDefinitions(List<ToolDefinition> toolDefs) {
        if (toolDefs == null || toolDefs.isEmpty()) {
            return Collections.emptyList();
        }
        List<AgentToolDefinition> result = new ArrayList<>();
        for (ToolDefinition def : toolDefs) {
            if (def == null || def.getToolCode() == null) continue;
            result.add(AgentToolDefinition.builder()
                    .name(def.getToolCode())
                    .description(def.getDescription())
                    .inputSchema(def.getParameterSchema())
                    .toolType(def.getToolType())
                    .sourceCode(def.getSourceCode())
                    .requiresApproval(def.isRequiresApproval())
                    .requiresConfirmation(def.isRequiresConfirmation())
                    .riskLevel(def.getRiskLevel())
                    .confirmationPolicy(def.getConfirmationPolicy())
                    .build());
        }
        return result;
    }

    private List<AgentToolDefinition> markToolApproved(List<AgentToolDefinition> toolDefs, String approvedToolName) {
        if (toolDefs == null || toolDefs.isEmpty()) {
            return Collections.emptyList();
        }
        List<AgentToolDefinition> result = new ArrayList<>();
        for (AgentToolDefinition def : toolDefs) {
            if (def == null) continue;
            boolean approvedTarget = approvedToolName != null && approvedToolName.equals(def.getName());
            result.add(AgentToolDefinition.builder()
                    .name(def.getName())
                    .description(def.getDescription())
                    .inputSchema(def.getInputSchema())
                    .toolType(def.getToolType())
                    .sourceCode(def.getSourceCode())
                    .requiresApproval(approvedTarget ? false : def.isRequiresApproval())
                    .requiresConfirmation(def.isRequiresConfirmation())
                    .riskLevel(def.getRiskLevel())
                    .confirmationPolicy(def.getConfirmationPolicy())
                    .nativeToolConfig(def.getNativeToolConfig())
                    .build());
        }
        return result;
    }

    private boolean isAuraBotSkillTool(ToolDefinition def) {
        if (def == null) return false;
        if ("AURABOT_SKILL".equals(def.getToolType())) return true;
        String code = def.getToolCode();
        return code != null && code.startsWith("aurabot:");
    }

    private boolean isAuraBotSkillPreviewPending(Map<String, Object> result) {
        if (result == null) return false;
        return Boolean.TRUE.equals(result.get("approvalRequired"))
                && result.get("previewToken") instanceof String token
                && !token.isBlank();
    }

    private void storeAuraBotSkillPendingTool(Map<String, Object> result, TurnContext ctx, String agentCode,
                                              String sessionId, String toolId, String toolName,
                                              Map<String, Object> input, List<ToolDefinition> toolDefs,
                                              List<LlmChatRequest.Message> messages,
                                              String providerCode, LlmProviderFactory.ProviderConfig config,
                                              String model, String systemPrompt, int maxTokens, int round,
                                              boolean persistTape) {
        String description = buildToolDescription(toolName, input);
        Map<String, Object> extension = new LinkedHashMap<>();
        extension.put("_aurabot_skill", true);
        extension.put("previewToken", result.get("previewToken"));
        extension.put("preview", result.get("preview"));
        extension.put("riskLevel", result.get("riskLevel"));

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
                        .input(input != null ? input : Map.of())
                        .description(description)
                        .runPid(ctx.turnId())
                        .taskPid(ctx.taskPid())
                        .agentToolDefinitions(toAgentToolDefinitions(toolDefs))
                        .messages(serializeMessages(messages))
                        .providerCode(providerCode)
                        .apiKey(config != null ? config.getApiKey() : null)
                        .baseUrl(config != null ? config.getBaseUrl() : null)
                        .model(model)
                        .systemPrompt(systemPrompt)
                        .maxTokens(maxTokens)
                        .currentLoop(round)
                        .extension(extension)
                        .build());
        if (persistTape) {
            persistMessages(sessionId, messages);
        }
    }

    private void storeApprovalPendingTool(Map<String, Object> result, TurnContext ctx, String agentCode,
                                          String sessionId, String toolId, String toolName,
                                          Map<String, Object> input, List<ToolDefinition> toolDefs,
                                          List<LlmChatRequest.Message> messages,
                                          String providerCode, LlmProviderFactory.ProviderConfig config,
                                          String model, String systemPrompt, int maxTokens, int round) {
        String approvalPid = approvalPidFrom(result);
        if (approvalPid == null) {
            return;
        }
        chatSessionStore.storePending(approvalPid,
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
                        .input(input != null ? input : Map.of())
                        .description(buildToolDescription(toolName, input))
                        .runPid(ctx.turnId())
                        .taskPid(ctx.taskPid())
                        .agentToolDefinitions(toAgentToolDefinitions(toolDefs))
                        .messages(serializeMessages(messages))
                        .providerCode(providerCode)
                        .apiKey(config != null ? config.getApiKey() : null)
                        .baseUrl(config != null ? config.getBaseUrl() : null)
                        .model(model)
                        .systemPrompt(systemPrompt)
                        .maxTokens(maxTokens)
                        .currentLoop(round)
                        .build());
    }

    private String unknownToolMessage(String toolName, List<ToolDefinition> toolDefs) {
        String available = toolDefs == null || toolDefs.isEmpty()
                ? "<none>"
                : toolDefs.stream()
                .filter(t -> t != null && t.getToolCode() != null)
                .map(ToolDefinition::getToolCode)
                .limit(10)
                .reduce((left, right) -> left + ", " + right)
                .orElse("<none>");
        return "Unknown tool '" + toolName + "'. Available tools: " + available;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeToolLoopResult(String rawResult) {
        Map<String, Object> response = new LinkedHashMap<>();
        if (rawResult == null || rawResult.isBlank()) {
            response.put("success", false);
            response.put("error", "Tool execution returned no result");
            response.put("durationMs", 0L);
            return response;
        }
        String trimmed = rawResult.trim();
        if (trimmed.startsWith("{")) {
            try {
                Map<String, Object> parsed = objectMapper.readValue(trimmed, Map.class);
                if (!parsed.containsKey("success")) {
                    parsed = new LinkedHashMap<>(parsed);
                    parsed.put("success", !parsed.containsKey("error"));
                }
                return parsed;
            } catch (Exception e) {
                log.debug("Failed to parse tool loop result as JSON: {}", e.getMessage());
            }
        }
        boolean success = !trimmed.startsWith("Error:");
        response.put("success", success);
        if (success) {
            response.put("data", trimmed);
        } else {
            response.put("error", trimmed);
        }
        response.put("durationMs", 0L);
        return response;
    }

    private boolean isApprovalRequiredResult(Map<String, Object> result) {
        return result != null && Boolean.TRUE.equals(result.get("approvalRequired"));
    }

    private String approvalPidFrom(Map<String, Object> result) {
        if (result == null) return null;
        Object rawPid = result.get("approvalPid");
        String approvalPid = rawPid != null ? String.valueOf(rawPid) : null;
        return approvalPid == null || approvalPid.isBlank() ? null : approvalPid;
    }

    private TurnOutcome buildApprovalRequiredOutcome(Map<String, Object> result, String toolName,
                                                     Map<String, Object> input, ResponseSink sink) {
        String approvalPid = approvalPidFrom(result);
        if (approvalPid == null) {
            String error = String.valueOf(result.getOrDefault("error",
                    "Approval required but no approval pid available. No data was changed."));
            sink.onError(error, null);
            return new TurnOutcome.Failed(error, null);
        }

        String description = String.valueOf(result.getOrDefault("message", "Approval required"));
        Map<String, Object> confirmInput = new LinkedHashMap<>();
        confirmInput.put("toolName", toolName);
        confirmInput.put("input", input != null ? input : Map.of());
        sink.onConfirmRequired(
                approvalPid,
                "agent_approval_gate",
                description,
                confirmInput,
                approvalPid);
        return new TurnOutcome.PendingConfirmation(approvalPid, "", approvalPid);
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
        // HandoffToolProvider's tool input schema names the field "agent_code"
        // (snake_case for LLM-friendliness). The chokepoint reads ONLY the
        // canonical schema field — legacy "targetAgentCode" was never declared
        // by the real tool schema and accepting it would mask drift between
        // schema and reader (DC.3d Fix 4 regression guard).
        // Surface on meta as _handoff_to so the chokepoint contract is stable.
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
