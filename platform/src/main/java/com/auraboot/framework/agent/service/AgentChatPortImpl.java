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
import com.auraboot.framework.agent.runtime.AgentErrorFrame;
import com.auraboot.framework.agent.runtime.AgentExecutionState;
import com.auraboot.framework.agent.runtime.AgentReducer;
import com.auraboot.framework.agent.runtime.AgentRuntimeEvent;
import com.auraboot.framework.agent.runtime.AgentRuntimeStateFactory;
import com.auraboot.framework.agent.runtime.ChatMessageTapeStore;
import com.auraboot.framework.agent.runtime.ChatTurnRuntime;
import com.auraboot.framework.agent.runtime.LlmMessageTapeSupport;
import com.auraboot.framework.agent.runtime.LlmRuntimeResolver;
import com.auraboot.framework.agent.runtime.PendingToolExecutionClaim;
import com.auraboot.framework.agent.runtime.PendingToolExecutionRecord;
import com.auraboot.framework.agent.runtime.PendingToolExecutionStatus;
import com.auraboot.framework.agent.runtime.PendingToolStore;
import com.auraboot.framework.agent.runtime.PendingToolSnapshot;
import com.auraboot.framework.agent.runtime.PendingToolSnapshotFactory;
import com.auraboot.framework.agent.runtime.ToolLoopResultNormalizer;
import com.auraboot.framework.agent.runtime.context.AgentContextAssembler;
import com.auraboot.framework.agent.runtime.context.AgentContextBlock;
import com.auraboot.framework.agent.runtime.context.AgentContextBundle;
import com.auraboot.framework.agent.runtime.policy.AgentProfile;
import com.auraboot.framework.agent.runtime.policy.AgentProfileResolver;
import com.auraboot.framework.agent.runtime.policy.DefaultAgentProfileResolver;
import com.auraboot.framework.aurabot.dto.ChatMessage;
import com.auraboot.framework.aurabot.dto.ChatRequest;
import com.auraboot.framework.common.util.LogSanitizer;
import com.auraboot.framework.conversation.ResponseSink;
import com.auraboot.framework.conversation.TurnContext;
import com.auraboot.framework.conversation.TurnOutcome;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.permission.service.UserPermissionService;
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
 *   <li>Adapting named-agent chat inputs to {@link ChatTurnRuntime}'s synchronous LLM tool loop.</li>
 *   <li>Streaming text chunks / tool events back through the {@link ResponseSink}
 *       transport adapter (parity with the aurabot path).</li>
 * </ol>
 *
 * <p>Phase B.0 follow-up (2026-04-29): the multi-HTTP-turn tool-loop continuation
 * dropped during the B.0/B.6 → main merge resolution has been re-introduced under
 * the new {@code runAgentTurn(ctx, request, sink): TurnOutcome} SPI. The
 * historic message tape is persisted via
 * {@link ChatMessageTapeStore} keyed by sessionId and rehydrated on
 * subsequent turns. Confirmation-required tools suspend via
 * {@link TurnOutcome.PendingConfirmation} and are resumed through the canonical
 * {@code ConversationTurnService.resumeTurn} path (no port-specific resume hook).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentChatPortImpl implements AgentChatPort {

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
    private final ChatMessageTapeStore chatMessageTapeStore;
    private final PendingToolStore pendingToolStore;
    private final ToolLoopService toolLoopService;
    private final AgentRuntimeStateFactory runtimeStateFactory;
    private final AgentReducer agentReducer;
    private final ChatTurnRuntime chatTurnRuntime;
    private final PendingToolSnapshotFactory pendingToolSnapshotFactory;
    private final AgentProfileResolver agentProfileResolver = DefaultAgentProfileResolver.INSTANCE;

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

    @Autowired(required = false)
    private UserPermissionService userPermissionService;

    @Autowired(required = false)
    private ToolAclChecker toolAclChecker;

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

        PendingToolSnapshot pending = pendingToolStore.consumePendingForOwner(approvalPid, tenantId, null);
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

        PendingToolExecutionClaim executionClaim = pendingToolStore.claimExecution(pending);
        if (executionClaim == null) {
            executionClaim = PendingToolExecutionClaim.acquired(PendingToolStore.executionKey(pending));
        }
        if (!executionClaim.acquired()) {
            response.putAll(replayPendingExecution(executionClaim.record()));
            return response;
        }
        String executionKey = executionClaim.record() != null
                ? executionClaim.record().executionKey()
                : PendingToolStore.executionKey(pending);

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
            Map<String, Object> result = ToolLoopResultNormalizer.normalize(
                    objectMapper, rawResult, pending.getToolName(), pending.getInput());
            response.put("success", Boolean.TRUE.equals(result.get("success")));
            response.put("result", result);
            if (result.get("error") != null) {
                response.put("error", result.get("error"));
            }
            if (Boolean.TRUE.equals(result.get("success"))) {
                pendingToolStore.completeExecution(pending, executionKey, result);
            } else {
                pendingToolStore.failExecution(pending, executionKey, result,
                        result.get("error") != null ? String.valueOf(result.get("error")) : "Tool execution failed");
            }
            return response;
        } catch (Exception e) {
            log.warn("Approved pending tool execution failed: errorType={}", e.getClass().getSimpleName());
            String safeError = safeExceptionMessage(e);
            pendingToolStore.failExecution(pending, executionKey,
                    Map.of("success", false, "error", safeError),
                    safeError);
            response.put("success", false);
            response.put("error", safeError);
            return response;
        }
    }

    private Map<String, Object> replayPendingExecution(PendingToolExecutionRecord record) {
        Map<String, Object> replay = new LinkedHashMap<>();
        replay.put("replayed", true);
        if (record == null || record.status() == PendingToolExecutionStatus.RUNNING) {
            replay.put("success", false);
            replay.put("error", "Pending tool execution is already running.");
            return replay;
        }
        replay.put("success", record.status() == PendingToolExecutionStatus.SUCCEEDED);
        if (record.result() != null && !record.result().isEmpty()) {
            replay.put("result", record.result());
        }
        if (record.status() == PendingToolExecutionStatus.FAILED) {
            replay.put("error", record.errorMessage() != null ? record.errorMessage() : "Tool execution failed.");
        }
        return replay;
    }

    @Override
    public TurnOutcome runAgentTurn(TurnContext ctx, ChatRequest request, ResponseSink sink,
                                     com.auraboot.framework.agent.port.AgentTurnOverrides overrides) {
        Long tenantId = ctx.tenantId();
        String agentCode = request.getAgentCode();

        Map<String, Object> agentDef;
        try {
            agentDef = loadAgentDefinition(tenantId, agentCode);
        } catch (IllegalStateException e) {
            String msg = safeExceptionMessage(e);
            sink.onError(msg, null);
            return new TurnOutcome.Failed(msg, e);
        }
        if (agentDef == null) {
            String msg = "Agent not found or inactive: " + agentCode;
            sink.onError(msg, null);
            return new TurnOutcome.Failed(msg, null);
        }
        AgentProfile profile = agentProfileResolver.resolve(objectMapper, agentDef);

        // Resolve provider + model from agent definition
        String providerCode = LlmRuntimeResolver.resolveAgentProviderCode(objectMapper, providerFactory, agentDef);
        if (providerCode == null || providerCode.isBlank()) {
            String msg = "No LLM provider configured for agent: " + agentCode +
                    ". Define guardrails.provider, guardrails.preferredProvider, or a known model.";
            sink.onError(msg, null);
            return new TurnOutcome.Failed(msg, null);
        }
        LlmProviderFactory.ProviderConfig config = resolveProviderConfig(tenantId, providerCode);
        if (config == null) {
            String msg = "No LLM provider configured for agent: " + agentCode +
                    ". Please configure an API key in Cloud Config.";
            sink.onError(msg, null);
            return new TurnOutcome.Failed(msg, null);
        }
        providerCode = LlmProviderFactory.effectiveProviderCode(providerCode, config);
        LlmProvider provider = providerFactory.getProvider(providerCode);
        if (provider == null) {
            String msg = "LLM provider not available: " + providerCode;
            sink.onError(msg, null);
            return new TurnOutcome.Failed(msg, null);
        }

        String model = LlmRuntimeResolver.resolveAgentModel(providerFactory, agentDef, providerCode);
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
            try {
                toolDefs = discoverToolDefinitions(tenantId, ctx.userId(), agentCode, request.getMessage(), agentDef);
            } catch (IllegalStateException e) {
                String msg = safeExceptionMessage(e);
                sink.onError(msg, null);
                return new TurnOutcome.Failed(msg, e);
            }
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
        // ChatMessageTapeStore. Group-chat callers typically opt out (history
        // already lives in ab_im_message); aurabot main path opts in (default).
        boolean persistTape;
        if (overrides != null && overrides.persistSessionTape() != null) {
            persistTape = overrides.persistSessionTape();
            recordOverrideUsage("persistSessionTape");
        } else {
            persistTape = true;
        }
        Set<String> overridePermissions = overrides != null ? overrides.effectivePermissions() : null;
        if (overridePermissions != null) {
            recordOverrideUsage("effectivePermissions");
        }
        Set<String> effectivePermissions = overridePermissions != null
                ? overridePermissions
                : resolveProfileEffectivePermissions(
                        ctx,
                        profile.profilePermissions(),
                        toolDefs);
        boolean requireInitialToolCall = profile.evidenceFirst();
        List<AgentContextBlock> contextBlocks = assembleContextBlocks(ctx, request);

        log.info("Agent chat: agentCode={}, provider={}, model={}, tools={}, overrides={}",
                agentCode, providerCode, model, tools.size(), overrides != null);

        // Run the tool loop
        return doAgentToolLoop(ctx, agentCode, provider, providerCode, config, model,
                systemPrompt, maxTokens, messages, tools, toolDefs, request.getSessionId(), sink,
                effectivePermissions, profile, persistTape, requireInitialToolCall, contextBlocks);
    }

    // =========================================================================
    // Tool loop
    // =========================================================================

    private List<AgentContextBlock> assembleContextBlocks(TurnContext ctx, ChatRequest request) {
        if (request == null || request.getPageContext() == null) {
            return List.of();
        }
        AgentContextBundle bundle = new AgentContextAssembler(objectMapper).assemble(
                new AgentContextAssembler.Request(
                        ctx != null ? ctx.tenantId() : null,
                        ctx != null ? ctx.channel() : null,
                        request.getPageContext(),
                        null,
                        null,
                        List.of()));
        return bundle.blocks();
    }

    private TurnOutcome doAgentToolLoop(TurnContext ctx, String agentCode,
                                        LlmProvider provider, String providerCode,
                                        LlmProviderFactory.ProviderConfig config, String model,
                                        String systemPrompt, int maxTokens,
                                        List<LlmChatRequest.Message> messages,
                                        List<LlmChatRequest.Tool> tools,
                                        List<ToolDefinition> toolDefs,
                                        String sessionId, ResponseSink sink,
                                        Set<String> effectivePermissions,
                                        AgentProfile profile,
                                        boolean persistTape,
                                        boolean requireInitialToolCall,
                                        List<AgentContextBlock> contextBlocks) {
        return chatTurnRuntime.runToolLoop(
                new ChatTurnRuntime.ChatToolLoopSpec(
                        ctx,
                        agentCode,
                        provider,
                        providerCode,
                        config.getApiKey(),
                        config.getBaseUrl(),
                        "named-agent chat",
                        model,
                        systemPrompt,
                        maxTokens,
                        messages,
                        tools,
                        toolDefs,
                        effectivePermissions,
                        profile,
                        sessionId,
                        sink,
                        persistTape,
                        requireInitialToolCall,
                        ChatTurnRuntime.DEFAULT_MAX_TOOL_ROUNDS,
                        HANDOFF_TOOL_NAME,
                        null,
                        objectMapper),
                agentChatToolLoopCallbacks(contextBlocks));
    }

    private ChatTurnRuntime.ChatToolLoopCallbacks agentChatToolLoopCallbacks(List<AgentContextBlock> contextBlocks) {
        return new ChatTurnRuntime.ChatToolLoopCallbacks() {
            @Override
            public AgentExecutionState buildRoundState(ChatTurnRuntime.ChatToolLoopRound round) {
                return runtimeStateFactory.chatTurnState(
                        round.ctx(),
                        round.agentCode(),
                        round.sessionId(),
                        round.providerCode(),
                        round.model(),
                        round.round(),
                        round.toolChoice(),
                        round.effectiveSystemPrompt(),
                        round.maxTokens(),
                        round.messages(),
                        round.tools(),
                        round.toolDefinitions(),
                        Map.of());
            }

            @Override
            public AgentExecutionState reduce(AgentExecutionState state, AgentRuntimeEvent event) {
                return reduceRuntimeState(state, event);
            }

            @Override
            public boolean allowToolInCatalog(ChatTurnRuntime.ChatToolLoopRound round, ToolDefinition definition) {
                return AgentChatPortImpl.this.allowToolInCatalog(round, definition);
            }

            @Override
            public List<AgentContextBlock> contextBlocks(ChatTurnRuntime.ChatToolLoopRound round) {
                return contextBlocks != null ? contextBlocks : List.of();
            }

            @Override
            public Map<String, Object> executeTool(ChatTurnRuntime.ChatToolCall call) {
                return executeToolSafely(call.ctx(), call.agentCode(), call.toolName(), call.input(),
                        call.toolDefinitions());
            }

            @Override
            public void storeConfirmationPending(ChatTurnRuntime.PendingChatTool pending) {
                String description = buildToolDescription(pending.toolName(), pending.input());
                pendingToolStore.storePending(pending.ctx().turnId(), pendingToolSnapshotFactory.build(
                        PendingToolSnapshotFactory.Snapshot.builder()
                                .ctx(pending.ctx())
                                .agentCode(pending.agentCode())
                                .sessionId(pending.sessionId())
                                .toolId(pending.toolId())
                                .toolName(pending.toolName())
                                .input(pending.input())
                                .toolVersion(pending.toolVersion())
                                .argsHash(pending.argsHash())
                                .idempotencyKey(pending.idempotencyKey())
                                .expiresAt(pending.expiresAt() != null ? pending.expiresAt().toEpochMilli() : null)
                                .policyDecisionReason(pending.policyDecisionReason())
                                .toolSchemaHash(pending.toolSchemaHash())
                                .preview(pending.preview())
                                .description(description)
                                .toolDefinitions(pending.toolDefinitions())
                                .contextBlocks(pending.contextBlocks())
                                .messages(pending.messages())
                                .providerCode(pending.providerCode())
                                .model(pending.model())
                                .systemPrompt(pending.systemPrompt())
                                .runtimeSystemPrompt(pending.runtimeSystemPrompt())
                                .maxTokens(pending.maxTokens())
                                .currentLoop(pending.round())
                                .toolChoice(pending.toolChoice())
                                .build()));
            }

            @Override
            public void storeAuraBotSkillPending(ChatTurnRuntime.PendingChatTool pending,
                                                 Map<String, Object> result) {
                storeAuraBotSkillPendingTool(result, pending.ctx(), pending.agentCode(), pending.sessionId(),
                        pending.toolId(), pending.toolName(), pending.input(), pending.toolDefinitions(),
                        pending.contextBlocks(),
                        pending.messages(), pending.providerCode(), pending.model(), pending.systemPrompt(),
                        pending.runtimeSystemPrompt(), pending.maxTokens(), pending.round(), pending.toolChoice(),
                        pending.persistTape());
            }

            @Override
            public void storeApprovalPending(ChatTurnRuntime.PendingChatTool pending, Map<String, Object> result) {
                storeApprovalPendingTool(result, pending.ctx(), pending.agentCode(), pending.sessionId(),
                        pending.toolId(), pending.toolName(), pending.input(), pending.toolDefinitions(),
                        pending.contextBlocks(),
                        pending.messages(), pending.providerCode(), pending.model(), pending.systemPrompt(),
                        pending.runtimeSystemPrompt(), pending.maxTokens(), pending.round(), pending.toolChoice());
            }

            @Override
            public void persistMessages(String sessionId, List<LlmChatRequest.Message> messages) {
                AgentChatPortImpl.this.persistMessages(sessionId, messages);
            }

            @Override
            public TurnOutcome buildApprovalRequiredOutcome(Map<String, Object> result,
                                                            String toolName,
                                                            Map<String, Object> input,
                                                            ResponseSink sink) {
                return AgentChatPortImpl.this.buildApprovalRequiredOutcome(result, toolName, input, sink);
            }

            @Override
            public TurnOutcome buildHandoffOutcome(LlmChatResponse response,
                                                   ResponseSink sink,
                                                   Map<String, Object> input) {
                return AgentChatPortImpl.this.buildHandoffOutcome(response, sink, input);
            }

            @Override
            public String buildToolDescription(String toolName, Map<String, Object> input) {
                return AgentChatPortImpl.this.buildToolDescription(toolName, input);
            }
        };
    }

    private boolean allowToolInCatalog(ChatTurnRuntime.ChatToolLoopRound round, ToolDefinition definition) {
        if (toolAclChecker == null || definition == null) {
            return true;
        }
        String toolRef = definition.getToolCode();
        if (toolRef == null || toolRef.isBlank()) {
            return false;
        }
        try {
            BusinessIntentFrame bif = BifContext.getCurrentBif();
            String profileId = firstNonBlank(
                    round.ctx() != null ? round.ctx().profileId() : null,
                    bif != null ? bif.getProfileId() : null,
                    round.agentCode());
            String channel = firstNonBlank(
                    round.ctx() != null ? round.ctx().channel() : null,
                    bif != null ? bif.getChannel() : null);
            ToolAclChecker.Decision acl = toolAclChecker.check(
                    round.ctx() != null ? round.ctx().tenantId() : null,
                    profileId,
                    channel,
                    "interactive",
                    toolRef);
            if (!acl.isAllowed()) {
                log.info("Tool ACL hidden from model catalog: tenant={} profile={} channel={} tool={} reason={}",
                        round.ctx() != null ? round.ctx().tenantId() : null,
                        LogSanitizer.safe(profileId),
                        LogSanitizer.safe(channel),
                        LogSanitizer.safe(toolRef),
                        LogSanitizer.safe(acl.getReason()));
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            log.warn("Tool ACL catalog evaluation failed closed: tool={}, errorType={}",
                    LogSanitizer.safe(toolRef), e.getClass().getSimpleName());
            return false;
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private AgentExecutionState reduceRuntimeState(AgentExecutionState state, AgentRuntimeEvent event) {
        if (state == null || event == null || agentReducer == null) {
            return state;
        }
        try {
            AgentReducer.Result result = agentReducer.reduce(state, event);
            return result != null && result.state() != null ? result.state() : state;
        } catch (RuntimeException e) {
            // Runtime state is diagnostic instrumentation; reducer failure must not change turn semantics.
            log.debug("Agent runtime reducer failed: event={}, error={}", event.type(), e.getMessage());
            return state;
        }
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
            String error = safeExceptionMessage(e);
            log.error("Tool discovery failed for agent {}: {}", agentCode, error, e);
            throw new IllegalStateException("Tool discovery failed for agent " + agentCode + ": " + error, e);
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

    private Set<String> resolveProfileEffectivePermissions(TurnContext ctx,
                                                           Set<String> profilePermissions,
                                                           List<ToolDefinition> toolDefs) {
        Set<String> userGranted = resolveUserGrantedRequiredPermissions(ctx, toolDefs);
        if (profilePermissions == null) {
            return userGranted;
        }
        if (userGranted == null) {
            return profilePermissions;
        }
        LinkedHashSet<String> intersection = new LinkedHashSet<>();
        for (String permission : profilePermissions) {
            if (permission != null && userGranted.contains(permission)) {
                intersection.add(permission);
            }
        }
        return Set.copyOf(intersection);
    }

    private Set<String> resolveUserGrantedRequiredPermissions(TurnContext ctx, List<ToolDefinition> toolDefs) {
        if (userPermissionService == null || ctx == null || toolDefs == null || toolDefs.isEmpty()) {
            return null;
        }
        Long userId = ctx.userId();
        if (userId == null) {
            return null;
        }
        LinkedHashSet<String> requiredPermissions = new LinkedHashSet<>();
        for (ToolDefinition toolDef : toolDefs) {
            if (toolDef == null || toolDef.getRequiredPermissions() == null) {
                continue;
            }
            for (String permission : toolDef.getRequiredPermissions()) {
                if (permission != null && !permission.isBlank()) {
                    requiredPermissions.add(permission);
                }
            }
        }
        if (requiredPermissions.isEmpty()) {
            return null;
        }
        LinkedHashSet<String> granted = new LinkedHashSet<>();
        for (String permission : requiredPermissions) {
            try {
                if (userPermissionService.hasPermission(userId, permission)) {
                    granted.add(permission);
                }
            } catch (RuntimeException e) {
                log.warn("Failed to resolve user tool permission: userId={}, permission={}, errorType={}",
                        userId, LogSanitizer.safe(permission), e.getClass().getSimpleName());
            }
        }
        return Set.copyOf(granted);
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
                    .requiredPermissions(def.getRequiredPermissions())
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
                return errorResult(validationErrorFrame(toolName, input, toolDefs), 0L);
            }
            if (toolLoopService == null) {
                return errorResult(AgentErrorFrame.of(
                        AgentErrorFrame.CATEGORY_TOOL,
                        toolName,
                        input,
                        "ToolKernelUnavailable",
                        false,
                        "Tool execution kernel is unavailable.",
                        "Stop the turn and ask an operator to check the agent tool runtime."),
                        0L);
            }
            log.debug("Agent chat tool call via ToolLoopService: tool={}, input={}",
                    toolName, LogSanitizer.safe(input));
            String rawResult = toolLoopService.executeToolCall(
                    ctx.tenantId(),
                    ctx.turnId(),
                    ctx.taskPid(),
                    agentCode,
                    toolName,
                    input != null ? input : Map.of(),
                    toAgentToolDefinitions(toolDefs),
                    null);
            return ToolLoopResultNormalizer.normalize(objectMapper, rawResult, toolName, input);
        } catch (Exception e) {
            log.warn("Tool execution failed in agent chat: tool={}, errorType={}, message={}",
                    toolName, e.getClass().getSimpleName(), safeExceptionMessage(e));
            return errorResult(AgentErrorFrame.of(
                    AgentErrorFrame.CATEGORY_TOOL,
                    toolName,
                    input,
                    e.getClass().getSimpleName(),
                    true,
                    "Tool execution failed.",
                    "Use corrected arguments or summarize the failure to the user."),
                    0L);
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
                    .requiredPermissions(def.getRequiredPermissions())
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
                    .requiredPermissions(def.getRequiredPermissions())
                    .confirmationPolicy(def.getConfirmationPolicy())
                    .nativeToolConfig(def.getNativeToolConfig())
                    .build());
        }
        return result;
    }

    private void storeAuraBotSkillPendingTool(Map<String, Object> result, TurnContext ctx, String agentCode,
                                              String sessionId, String toolId, String toolName,
                                              Map<String, Object> input, List<ToolDefinition> toolDefs,
                                              List<AgentContextBlock> contextBlocks,
                                              List<LlmChatRequest.Message> messages,
                                              String providerCode, String model, String systemPrompt, String runtimeSystemPrompt,
                                              int maxTokens, int round, String toolChoice, boolean persistTape) {
        String description = buildToolDescription(toolName, input);
        Map<String, Object> extension = new LinkedHashMap<>();
        extension.put("_aurabot_skill", true);
        extension.put("previewToken", result.get("previewToken"));
        extension.put("preview", result.get("preview"));
        extension.put("riskLevel", result.get("riskLevel"));

        pendingToolStore.storePending(ctx.turnId(), pendingToolSnapshotFactory.build(
                PendingToolSnapshotFactory.Snapshot.builder()
                        .ctx(ctx)
                        .agentCode(agentCode)
                        .sessionId(sessionId)
                        .toolId(toolId)
                        .toolName(toolName)
                        .input(input)
                        .description(description)
                        .toolDefinitions(toolDefs)
                        .contextBlocks(contextBlocks)
                        .messages(messages)
                        .providerCode(providerCode)
                        .model(model)
                        .systemPrompt(systemPrompt)
                        .runtimeSystemPrompt(runtimeSystemPrompt)
                        .maxTokens(maxTokens)
                        .currentLoop(round)
                        .toolChoice(toolChoice)
                        .extension(extension)
                        .build()));
        if (persistTape) {
            persistMessages(sessionId, messages);
        }
    }

    private void storeApprovalPendingTool(Map<String, Object> result, TurnContext ctx, String agentCode,
                                          String sessionId, String toolId, String toolName,
                                          Map<String, Object> input, List<ToolDefinition> toolDefs,
                                          List<AgentContextBlock> contextBlocks,
                                          List<LlmChatRequest.Message> messages,
                                          String providerCode, String model, String systemPrompt, String runtimeSystemPrompt,
                                          int maxTokens, int round, String toolChoice) {
        String approvalPid = approvalPidFrom(result);
        if (approvalPid == null) {
            return;
        }
        pendingToolStore.storePending(approvalPid, pendingToolSnapshotFactory.build(
                PendingToolSnapshotFactory.Snapshot.builder()
                        .ctx(ctx)
                        .agentCode(agentCode)
                        .sessionId(sessionId)
                        .toolId(toolId)
                        .toolName(toolName)
                        .input(input)
                        .description(buildToolDescription(toolName, input))
                        .toolDefinitions(toolDefs)
                        .contextBlocks(contextBlocks)
                        .messages(messages)
                        .providerCode(providerCode)
                        .model(model)
                        .systemPrompt(systemPrompt)
                        .runtimeSystemPrompt(runtimeSystemPrompt)
                        .maxTokens(maxTokens)
                        .currentLoop(round)
                        .toolChoice(toolChoice)
                        .approvalPid(approvalPid)
                        .build()));
    }

    private AgentErrorFrame validationErrorFrame(String toolName, Map<String, Object> input,
                                                 List<ToolDefinition> toolDefs) {
        return AgentErrorFrame.of(
                AgentErrorFrame.CATEGORY_VALIDATION,
                toolName,
                input,
                "UnknownTool",
                true,
                "The model requested an unavailable tool.",
                "Call one of the available tools: " + availableToolNames(toolDefs) + ".");
    }

    private Map<String, Object> errorResult(AgentErrorFrame errorFrame, Object durationMs) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false);
        response.put("error", errorFrame.userSafeMessage());
        response.put("errorFrame", errorFrame.toSnapshotMap());
        response.put("retryable", errorFrame.retryable());
        response.put("durationMs", durationMs instanceof Number ? durationMs : 0L);
        return response;
    }

    private String availableToolNames(List<ToolDefinition> toolDefs) {
        if (toolDefs == null || toolDefs.isEmpty()) {
            return "<none>";
        }
        return toolDefs.stream()
                .filter(t -> t != null && t.getToolCode() != null)
                .map(ToolDefinition::getToolCode)
                .limit(10)
                .reduce((left, right) -> left + ", " + right)
                .orElse("<none>");
    }

    private String safeExceptionMessage(Exception e) {
        if (e == null) {
            return "Unknown error";
        }
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return LogSanitizer.safe(message);
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
            log.warn("Agent definition lookup failed: agentCode={}, errorType={}",
                    agentCode, e.getClass().getSimpleName());
            throw new IllegalStateException("Agent definition lookup failed for agentCode=" + agentCode, e);
        }
    }

    private LlmProviderFactory.ProviderConfig resolveProviderConfig(Long tenantId, String preferredProvider) {
        return providerFactory.resolveConfig(tenantId, preferredProvider);
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
        return LlmMessageTapeSupport.restoreOrBuildTextMessages(
                stored,
                history,
                ChatMessage::getRole,
                ChatMessage::getContent,
                userMessage);
    }

    private List<Map<String, Object>> safeLoadStored(String sessionId) {
        try {
            List<Map<String, Object>> loaded = chatMessageTapeStore.loadConversationMessages(sessionId);
            return loaded == null ? Collections.emptyList() : loaded;
        } catch (Exception e) {
            log.debug("Failed to load conversation tape for session {}: {}", sessionId, e.getMessage());
            return Collections.emptyList();
        }
    }

    private void persistMessages(String sessionId, List<LlmChatRequest.Message> messages) {
        if (sessionId == null || sessionId.isBlank()) return;
        try {
            chatMessageTapeStore.storeConversationMessages(sessionId, LlmMessageTapeSupport.serializeMessages(messages));
        } catch (Exception e) {
            log.debug("Failed to persist conversation tape for session {}: {}", sessionId, e.getMessage());
        }
    }

    // =========================================================================
    // (SSE writes go through the ResponseSink — byte stream parity with the
    //  aurabot path is enforced by SseResponseSink, locked at A.2b sha256
    //  baseline. No emitter helpers live here.)
    // =========================================================================

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
        String text = chatTurnRuntime.finalResponseText(response);
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
