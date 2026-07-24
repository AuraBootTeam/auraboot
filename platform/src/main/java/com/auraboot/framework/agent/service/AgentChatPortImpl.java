package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.BusinessIntentFrame;
import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.provider.ToolDefinition;
import com.auraboot.framework.agent.port.AgentChatPort;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agent.provider.ToolProviderRegistry;
import com.auraboot.framework.agent.runtime.AgentExecutionState;
import com.auraboot.framework.agent.runtime.AgentReducer;
import com.auraboot.framework.agent.runtime.AgentRuntimeEvent;
import com.auraboot.framework.agent.runtime.AgentRuntimeStateFactory;
import com.auraboot.framework.agent.runtime.ChatMessageTapeStore;
import com.auraboot.framework.agent.runtime.ChatTurnRuntime;
import com.auraboot.framework.agent.runtime.LlmMessageTapeSupport;
import com.auraboot.framework.agent.runtime.LlmRuntimeResolver;
import com.auraboot.framework.agent.runtime.PendingToolStore;
import com.auraboot.framework.agent.runtime.PendingToolSnapshotFactory;
import com.auraboot.framework.agent.runtime.context.AgentContextBlock;
import com.auraboot.framework.agent.runtime.context.AgentContextAssembler;
import com.auraboot.framework.agent.runtime.policy.AgentProfile;
import com.auraboot.framework.agent.runtime.policy.AgentProfileResolver;
import com.auraboot.framework.agent.runtime.policy.DefaultAgentProfileResolver;
import com.auraboot.framework.agent.dto.ChatMessage;
import com.auraboot.framework.agent.dto.ChatRequest;
import com.auraboot.framework.common.util.LogSanitizer;
import com.auraboot.framework.conversation.ResponseSink;
import com.auraboot.framework.conversation.TurnContext;
import com.auraboot.framework.conversation.TurnOutcome;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
public class AgentChatPortImpl implements AgentChatPort {

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
    private final AgentProfileResolver agentProfileResolver;
    private final AgentChatApprovedPendingToolAdapter approvedPendingToolAdapter;
    private final AgentChatToolDiscoveryAdapter toolDiscoveryAdapter;
    private final AgentChatContextAdapter contextAdapter;
    private final AgentChatToolRuntimeAdapterFactory toolRuntimeAdapterFactory;

    /**
     * Max tool-call rounds for the named-agent path. Shares the aurabot config key so a
     * named agent gets the same 20-round ceiling as the default agent (IMPL-07). This
     * path used to be hardwired to {@code ChatTurnRuntime.DEFAULT_MAX_TOOL_ROUNDS} (5) —
     * a silent 4x cap versus the aurabot path's configurable 20.
     */
    @Value("${aurabot.max-tool-rounds:20}")
    private int maxToolRounds;

    public AgentChatPortImpl(DynamicDataMapper dynamicDataMapper,
                             LlmProviderFactory providerFactory,
                             ToolProviderRegistry toolProviderRegistry,
                             GroundingService groundingService,
                             AgentSkillService skillService,
                             ObjectMapper objectMapper,
                             ChatMessageTapeStore chatMessageTapeStore,
                             PendingToolStore pendingToolStore,
                             ToolLoopService toolLoopService,
                             AgentRuntimeStateFactory runtimeStateFactory,
                             AgentReducer agentReducer,
                             ChatTurnRuntime chatTurnRuntime,
                             PendingToolSnapshotFactory pendingToolSnapshotFactory) {
        this(dynamicDataMapper,
                providerFactory,
                toolProviderRegistry,
                groundingService,
                skillService,
                objectMapper,
                chatMessageTapeStore,
                pendingToolStore,
                toolLoopService,
                runtimeStateFactory,
                agentReducer,
                chatTurnRuntime,
                pendingToolSnapshotFactory,
                DefaultAgentProfileResolver.INSTANCE,
                fallbackContextAdapter(objectMapper));
    }

    public AgentChatPortImpl(DynamicDataMapper dynamicDataMapper,
                             LlmProviderFactory providerFactory,
                             ToolProviderRegistry toolProviderRegistry,
                             GroundingService groundingService,
                             AgentSkillService skillService,
                             ObjectMapper objectMapper,
                             ChatMessageTapeStore chatMessageTapeStore,
                             PendingToolStore pendingToolStore,
                             ToolLoopService toolLoopService,
                             AgentRuntimeStateFactory runtimeStateFactory,
                             AgentReducer agentReducer,
                             ChatTurnRuntime chatTurnRuntime,
                             PendingToolSnapshotFactory pendingToolSnapshotFactory,
                             AgentProfileResolver agentProfileResolver) {
        this(dynamicDataMapper,
                providerFactory,
                toolProviderRegistry,
                groundingService,
                skillService,
                objectMapper,
                chatMessageTapeStore,
                pendingToolStore,
                toolLoopService,
                runtimeStateFactory,
                agentReducer,
                chatTurnRuntime,
                pendingToolSnapshotFactory,
                agentProfileResolver,
                fallbackContextAdapter(objectMapper));
    }

    @Autowired
    public AgentChatPortImpl(DynamicDataMapper dynamicDataMapper,
                             LlmProviderFactory providerFactory,
                             ToolProviderRegistry toolProviderRegistry,
                             GroundingService groundingService,
                             AgentSkillService skillService,
                             ObjectMapper objectMapper,
                             ChatMessageTapeStore chatMessageTapeStore,
                             PendingToolStore pendingToolStore,
                             ToolLoopService toolLoopService,
                             AgentRuntimeStateFactory runtimeStateFactory,
                             AgentReducer agentReducer,
                             ChatTurnRuntime chatTurnRuntime,
                             PendingToolSnapshotFactory pendingToolSnapshotFactory,
                             AgentProfileResolver agentProfileResolver,
                             AgentChatContextAdapter contextAdapter) {
        this.dynamicDataMapper = dynamicDataMapper;
        this.providerFactory = providerFactory;
        this.toolProviderRegistry = toolProviderRegistry;
        this.groundingService = groundingService;
        this.skillService = skillService;
        this.objectMapper = objectMapper;
        this.chatMessageTapeStore = chatMessageTapeStore;
        this.pendingToolStore = pendingToolStore;
        this.toolLoopService = toolLoopService;
        this.runtimeStateFactory = runtimeStateFactory;
        this.agentReducer = agentReducer;
        this.chatTurnRuntime = chatTurnRuntime;
        this.pendingToolSnapshotFactory = pendingToolSnapshotFactory;
        this.agentProfileResolver = agentProfileResolver != null
                ? agentProfileResolver
                : DefaultAgentProfileResolver.INSTANCE;
        this.approvedPendingToolAdapter = new AgentChatApprovedPendingToolAdapter(
                pendingToolStore,
                toolLoopService,
                objectMapper);
        this.toolDiscoveryAdapter = new AgentChatToolDiscoveryAdapter(
                dynamicDataMapper,
                toolProviderRegistry,
                groundingService,
                objectMapper,
                skillService);
        this.contextAdapter = contextAdapter != null
                ? contextAdapter
                : fallbackContextAdapter(objectMapper);
        AgentChatTurnOutcomeAdapter turnOutcomeAdapter =
                new AgentChatTurnOutcomeAdapter(chatTurnRuntime, objectMapper);
        AgentChatToolExecutionAdapter toolExecutionAdapter =
                new AgentChatToolExecutionAdapter(toolLoopService, objectMapper);
        this.toolRuntimeAdapterFactory = new AgentChatToolRuntimeAdapterFactory(
                runtimeStateFactory,
                pendingToolStore,
                pendingToolSnapshotFactory,
                turnOutcomeAdapter,
                toolExecutionAdapter);
    }

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

    private static AgentChatContextAdapter fallbackContextAdapter(ObjectMapper mapper) {
        return new AgentChatContextAdapter(new AgentContextAssembler(mapper));
    }

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
        return approvedPendingToolAdapter.execute(tenantId, approvalPid);
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
            // Two very different situations shared one sentence, and it named the
            // agent_code — an internal identifier the reader did not choose and
            // cannot act on (§2.2 forbids raw codes in user-facing text). The
            // common case by far is an operator having suspended this colleague
            // on purpose; being told it "was not found" sends them looking for a
            // deleted record instead of the Resume button.
            String msg = agentDefinitionExists(tenantId, agentCode)
                    ? "This AI colleague is suspended and is not taking new work. "
                      + "An administrator can resume it from its profile page."
                    : "This AI colleague is no longer available.";
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
                toolDefs = toolDiscoveryAdapter.discover(
                        tenantId, ctx.userId(), agentCode, ctx.channel(), request.getMessage(), agentDef);
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
        List<AgentContextBlock> contextBlocks = contextAdapter.assemble(ctx, request);

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
                        maxToolRounds,
                        AgentChatTurnOutcomeAdapter.HANDOFF_TOOL_NAME,
                        null,
                        objectMapper),
                toolRuntimeAdapterFactory.callbacks(this, contextBlocks));
    }

    boolean defersPolicyUntilToolResult(ToolDefinition definition) {
        if (definition == null) {
            return false;
        }
        if ("AURABOT_SKILL".equals(definition.getToolType())) {
            return true;
        }
        String code = definition.getToolCode();
        return code != null && code.startsWith("aurabot:");
    }

    boolean isPreviewConfirmationResult(Map<String, Object> result) {
        if (result == null) {
            return false;
        }
        if (Boolean.TRUE.equals(result.get("_aurabot_skill_pending"))) {
            return true;
        }
        return Boolean.TRUE.equals(result.get("approvalRequired"))
                && result.get("previewToken") instanceof String token
                && !token.isBlank();
    }

    boolean allowToolInCatalog(ChatTurnRuntime.ChatToolLoopRound round, ToolDefinition definition) {
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

    AgentExecutionState reduceRuntimeState(AgentExecutionState state, AgentRuntimeEvent event) {
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

    // =========================================================================
    // Agent definition loading
    // =========================================================================


    /**
     * Whether a definition exists at all, regardless of status — the difference
     * between "an operator suspended this colleague" and "this colleague is
     * gone", which the caller needs in order to say something useful.
     */
    private boolean agentDefinitionExists(Long tenantId, String agentCode) {
        try {
            String sql = "SELECT pid FROM ab_agent_definition WHERE tenant_id = #{params.tenantId} "
                    + "AND agent_code = #{params.agentCode} AND deleted_flag = FALSE";
            return !dynamicDataMapper.selectByQuery(sql,
                    Map.of("tenantId", tenantId, "agentCode", agentCode)).isEmpty();
        } catch (Exception e) {
            // Never let the nicety of a better message become a second failure.
            return false;
        }
    }

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

    void persistMessages(String sessionId, List<LlmChatRequest.Message> messages) {
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
