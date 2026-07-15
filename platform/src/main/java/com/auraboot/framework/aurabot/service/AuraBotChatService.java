package com.auraboot.framework.aurabot.service;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agent.provider.LlmProviderFactory.ProviderConfig;
import com.auraboot.framework.agent.runtime.ChatTurnRuntime;
import com.auraboot.framework.agent.runtime.PendingToolSnapshotFactory;
import com.auraboot.framework.agent.runtime.PendingToolStore;
import com.auraboot.framework.agent.runtime.context.AgentContextAssembler;
import com.auraboot.framework.agent.runtime.context.AgentContextBundle;
import com.auraboot.framework.agent.runtime.LlmChatRuntimeSupport;
import com.auraboot.framework.agent.runtime.LlmMessageTapeSupport;
import com.auraboot.framework.agent.runtime.LlmRuntimeResolver;
import com.auraboot.framework.agent.trace.AiTraceService;
import com.auraboot.framework.agent.trace.SpanContext;
import com.auraboot.framework.agent.trace.TraceContext;
import com.auraboot.framework.aurabot.dto.ChatMessage;
import com.auraboot.framework.aurabot.dto.ChatRequest;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.conversation.ResponseSink;
import com.auraboot.framework.conversation.TurnContext;
import com.auraboot.framework.conversation.TurnOutcome;
import com.auraboot.framework.meta.constant.SystemFieldConstants;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import io.micrometer.observation.annotation.Observed;
import java.util.*;
import java.util.concurrent.Executor;

/**
 * AuraBot chat service that streams LLM responses via SSE.
 * <p>
 * Uses {@link LlmProviderFactory} for provider resolution/configuration and
 * {@link PromptTemplateService} for system prompt rendering. Provider chunk
 * streaming is delegated to {@link ChatTurnRuntime}.
 * <p>
 * ConversationTurnService owns agent-code routing. This service is only the
 * AuraBot light-chat adapter behind that chokepoint.
 *
 * @since 6.5.0
 */
@Slf4j
@Service
public class AuraBotChatService {

    private final LlmProviderFactory llmProviderFactory;
    private final PromptTemplateService promptTemplateService;
    private final ChatToolResolver chatToolResolver;
    private final ObjectMapper objectMapper;
    private final AiTraceService aiTraceService;
    private final MetaModelService metaModelService;
    private final ChatTurnRuntime chatTurnRuntime;
    private final AuraBotChatToolRuntimeAdapterFactory toolRuntimeAdapterFactory;
    private final AgentContextAssembler contextAssembler;
    @Qualifier("asyncTaskExecutor")
    private final Executor asyncTaskExecutor;

    /** Optional RAG context provider from the shared AI runtime. */
    @Autowired(required = false)
    private RagContextProvider ragContextProvider;

    // ConversationTurnServiceImpl owns agentCode dispatch. This service is the
    // aurabot-only turn implementation behind that chokepoint.

    /** Optional D1 Grounding service (computes BIF per turn). */
    @Autowired(required = false)
    private com.auraboot.framework.agent.service.GroundingService groundingService;

    /** Optional BIF persistence. */
    @Autowired(required = false)
    private com.auraboot.framework.agent.service.BifRecorder bifRecorder;

    /** Optional chat run persistence from the shared AI runtime. */
    @Autowired(required = false)
    private ChatRunPersistencePort chatRunPersistencePort;

    /** Optional pending-tool store. Required only when a policy-gated tool suspends the turn. */
    @Autowired(required = false)
    private PendingToolStore pendingToolStore;

    /** Optional snapshot factory. Required only when a policy-gated tool suspends the turn. */
    @Autowired(required = false)
    private PendingToolSnapshotFactory pendingToolSnapshotFactory;

    /** Optional permission resolver used to build the effective permission envelope for exposed tools. */
    @Autowired(required = false)
    private UserPermissionService userPermissionService;

    /**
     * Optional User Soul Profile reader (plan §5.5 / PR-77 Phase 3). When a profile
     * exists for the current user, a compact "About this user" block is prepended
     * to the chat system prompt. Made optional so focused tests that don't wire
     * the bean continue to compose prompts unchanged.
     */
    @Autowired(required = false)
    private com.auraboot.framework.agent.service.UserSoulProfileReader userSoulProfileReader;

    @Value("${aurabot.max-tool-rounds:20}")
    private int maxToolRounds;

    private static final String DEFAULT_SYSTEM_PROMPT =
            "You are AuraBot, an intelligent assistant for the AuraBoot platform. " +
            "Help users with their questions about the current page and data. " +
            "Be concise, accurate, and helpful. Respond in the user's language.";

    public AuraBotChatService(LlmProviderFactory llmProviderFactory,
                              PromptTemplateService promptTemplateService,
                              ChatToolResolver chatToolResolver,
                              ChatToolExecutor chatToolExecutor,
                              ObjectMapper objectMapper,
                              AiTraceService aiTraceService,
                              MetaModelService metaModelService,
                              ChatTurnRuntime chatTurnRuntime,
                              Executor asyncTaskExecutor) {
        this(llmProviderFactory,
                promptTemplateService,
                chatToolResolver,
                objectMapper,
                aiTraceService,
                metaModelService,
                chatTurnRuntime,
                asyncTaskExecutor,
                fallbackContextAssembler(objectMapper),
                new AuraBotChatToolRuntimeAdapterFactory(
                        chatTurnRuntime,
                        llmProviderFactory,
                        chatToolResolver,
                        chatToolExecutor,
                        objectMapper));
    }

    public AuraBotChatService(LlmProviderFactory llmProviderFactory,
                              PromptTemplateService promptTemplateService,
                              ChatToolResolver chatToolResolver,
                              ObjectMapper objectMapper,
                              AiTraceService aiTraceService,
                              MetaModelService metaModelService,
                              ChatTurnRuntime chatTurnRuntime,
                              @Qualifier("asyncTaskExecutor") Executor asyncTaskExecutor,
                              AuraBotChatToolRuntimeAdapterFactory toolRuntimeAdapterFactory) {
        this(llmProviderFactory,
                promptTemplateService,
                chatToolResolver,
                objectMapper,
                aiTraceService,
                metaModelService,
                chatTurnRuntime,
                asyncTaskExecutor,
                fallbackContextAssembler(objectMapper),
                toolRuntimeAdapterFactory);
    }

    @Autowired
    public AuraBotChatService(LlmProviderFactory llmProviderFactory,
                              PromptTemplateService promptTemplateService,
                              ChatToolResolver chatToolResolver,
                              ObjectMapper objectMapper,
                              AiTraceService aiTraceService,
                              MetaModelService metaModelService,
                              ChatTurnRuntime chatTurnRuntime,
                              @Qualifier("asyncTaskExecutor") Executor asyncTaskExecutor,
                              AgentContextAssembler contextAssembler,
                              AuraBotChatToolRuntimeAdapterFactory toolRuntimeAdapterFactory) {
        this.llmProviderFactory = llmProviderFactory;
        this.promptTemplateService = promptTemplateService;
        this.chatToolResolver = chatToolResolver;
        this.objectMapper = objectMapper;
        this.aiTraceService = aiTraceService;
        this.metaModelService = metaModelService;
        this.chatTurnRuntime = chatTurnRuntime;
        this.asyncTaskExecutor = asyncTaskExecutor;
        this.contextAssembler = contextAssembler != null
                ? contextAssembler
                : fallbackContextAssembler(objectMapper);
        this.toolRuntimeAdapterFactory = toolRuntimeAdapterFactory;
    }

    private static AgentContextAssembler fallbackContextAssembler(ObjectMapper mapper) {
        return new AgentContextAssembler(mapper);
    }

    static Map<String, Object> buildPromptSpanOutput(String systemPrompt) {
        return Map.of(
                "system_prompt", systemPrompt,
                "char_count", systemPrompt != null ? systemPrompt.length() : 0
        );
    }

    static Map<String, Object> buildGroundingSpanInput(String userMessage, String modelCode,
                                                       String recordPid, String sessionId) {
        return Map.of(
                "message", userMessage != null ? userMessage : "",
                "model_code", modelCode != null ? modelCode : "",
                "record_pid", recordPid != null ? recordPid : "",
                "session_id", sessionId != null ? sessionId : ""
        );
    }

    static Map<String, Object> buildResolveToolsSpanInput(String userMessage, String modelCode,
                                                          String recordPid) {
        return Map.of(
                "message", userMessage != null ? userMessage : "",
                "model_code", modelCode != null ? modelCode : "",
                "record_pid", recordPid != null ? recordPid : ""
        );
    }

    static Map<String, Object> buildResolveToolsSpanOutput(List<LlmChatRequest.Tool> tools) {
        List<Map<String, Object>> toolSummaries = tools == null ? List.of() : tools.stream()
                .map(tool -> Map.<String, Object>of(
                        "name", tool.getName() != null ? tool.getName() : "",
                        "description", tool.getDescription() != null ? tool.getDescription() : ""))
                .toList();
        return Map.of(
                "tool_count", tools != null ? tools.size() : 0,
                "tools", toolSummaries
        );
    }

    /**
     * Build an intent-aware tool hint based on the resolved tools metadata.
     * Replaces the old static TOOL_HINT constant with dynamic guidance.
     */
    private String buildToolHint(ChatToolResolver.ResolvedTools resolved) {
        if (resolved == null || resolved.tools().isEmpty()) return "";

        StringBuilder hint = new StringBuilder();
        hint.append("\n\nYou have access to tools. Follow this strategy:\n");

        if (resolved.isReadOnly()) {
            boolean sqlAvailable = LlmChatRuntimeSupport.isToolOffered(resolved.tools(), "platform_execute_sql");
            hint.append("- The user wants to QUERY data. Prefer nq_* (named query) tools — they are pre-built and optimized.\n");
            if (sqlAvailable) {
                hint.append("- Use platform_execute_sql ONLY if no named query or domain list/get tool matches the question.\n");
                hint.append("- Before calling platform_execute_sql on a table you have NOT already seen the schema for,\n");
                hint.append("  FIRST call platform_list_models with includeFields=true AND a keyword narrowing the scope\n");
                hint.append("  (e.g. keyword='crm_account' — NOT empty). Do NOT guess Chinese-to-English field names\n");
                hint.append("  (e.g. '行业' is not guaranteed to be 'industry'; it may be 'industry_type', 'trade',\n");
                hint.append("  'category', or absent). One schema call is enough — the response will contain the fields.\n");
                hint.append("- If platform_execute_sql returns an error with 'availableFields' and 'recovery', you MUST\n");
                hint.append("  read availableFields, pick the closest semantic match, and retry ONCE with the corrected\n");
                hint.append("  column. Only tell the user the dimension is unavailable if no field is a reasonable match,\n");
                hint.append("  and in that case suggest 2-3 alternative dimensions from availableFields.\n");
            } else {
                hint.append("- platform_execute_sql is not available in this context. Use only the listed domain tools.\n");
            }
        } else {
            hint.append("- The user wants to MODIFY data. Use the cmd_* tools to execute the operation.\n");
            hint.append("- Describe what you will do BEFORE calling the tool.\n");
        }

        hint.append("\nRules:\n");
        hint.append("- Table names use 'mt_' prefix (e.g., model 'crm_lead' → table 'mt_crm_lead').\n");
        hint.append("- Each tool may be called at most 5 times per turn; total tool rounds capped by the runtime.\n");
        hint.append("- NEVER call the same tool with identical parameters twice.\n");
        hint.append("- Present results as tables in Chinese.\n");
        hint.append("- If a tool fails, explain the error clearly.\n");
        if (LlmChatRuntimeSupport.isToolOffered(resolved.tools(), "platform_execute_sql")) {
            hint.append("- When using platform_execute_sql for analytics/statistics, ALWAYS set chartType:\n");
            hint.append("  - 'pie' for distribution/proportion queries (e.g., group by category)\n");
            hint.append("  - 'bar' for comparison/ranking queries (e.g., top N, amount by stage)\n");
            hint.append("  - 'line' for time-series/trend queries (e.g., monthly revenue)\n");
            hint.append("  - 'table' only for raw detail listings\n");
        }

        return hint.toString();
    }

    // Public conversation entrypoints are centralized in ConversationTurnService.
    // This class exposes the sync aurabot implementation invoked by runTurn and
    // resumeTurn, with ResponseSink carrying SSE / broadcast / future transports.

    // =========================================================================
    // Core sync entry (Phase A.3 — Q-A.4=A')
    // =========================================================================

    /**
     * Sync core for the {@code aurabot} main path. Named-agent execution is
     * handled by AgentChatPort from ConversationTurnServiceImpl.
     *
     * <p>Returns a real {@link TurnOutcome} reflecting actual completion; never returns null.
     * Sync internally — {@code ConversationTurnService.runTurn} owns the async boundary.
     *
     * <p>Side effects this method does NOT manage:
     * <ul>
     *     <li>{@link MetaContext} — caller's responsibility</li>
     *     <li>{@code asyncTaskExecutor.execute} — caller is already on a worker thread</li>
     *     <li>named-agent {@code AgentChatPort} routing — caller handles</li>
     * </ul>
     *
     * <p>What this method DOES manage internally:
     * <ul>
     *     <li>{@code ResponseSinkContext.set(sink)} so {@link com.auraboot.framework.agent.service.ResultContractEmitter}
     *         can publish {@code result_contract} events through the same sink (Phase C.3b — formerly
     *         the SSE-specific {@code ChatSseContext}; now transport-agnostic so WS / sync-JSON adapters
     *         work without an SSE-instanceof branch)</li>
     *     <li>{@code BifContext.clear} + {@code ResponseSinkContext.clear} in finally</li>
     *     <li>Top-level exception barrier — translates uncaught throws to {@link TurnOutcome.Failed}
     *         and surfaces them on the sink</li>
     * </ul>
     */
    public TurnOutcome executeAuraBotTurn(TurnContext ctx, ChatRequest request, ResponseSink sink) {
        com.auraboot.framework.conversation.ResponseSinkContext.set(sink);
        try {
            return doStreamChatInnerSinkAware(ctx, request, sink);
        } catch (Exception e) {
            log.error("executeAuraBotTurn failed: {}", e.getMessage(), e);
            sink.onError(e.getMessage(), null);
            return new TurnOutcome.Failed(e.getMessage(), e);
        } finally {
            com.auraboot.framework.agent.service.BifContext.clear();
            com.auraboot.framework.conversation.ResponseSinkContext.clear();
        }
    }

    private TurnOutcome doStreamChatInnerSinkAware(TurnContext ctx, ChatRequest request, ResponseSink sink) {
        // Aurabot-only path. Named-agent routing is owned by the conversation
        // chokepoint, so this method assumes agentCode is null/blank/"aurabot".
        Long tenantId = ctx.tenantId();

        // 1. Resolve provider and config
        ChatRequest.ChatOptions options = request.getOptions() != null ? request.getOptions() : new ChatRequest.ChatOptions();
        String providerCode = LlmRuntimeResolver.resolveChatProviderCode(
                llmProviderFactory, options.getProvider(), options.getModel());
        ProviderConfig config = llmProviderFactory.resolveConfig(tenantId, providerCode);
        if (config == null) {
            // User-facing: emit an $i18n: sentinel so the frontend (which knows the
            // browser locale) localizes it via useSmartText. The service layer has no
            // request locale here, mirroring the BusinessException.i18n contract. Keep
            // a readable English message on the outcome for logs / audit.
            sink.onError("$i18n:aurabot.error.no_llm_provider", null);
            return new TurnOutcome.Failed(
                    "No LLM provider configured. Please configure an API key in Cloud Config.", null);
        }
        // Use the resolved provider code (may differ from input when auto-discovered)
        providerCode = LlmProviderFactory.effectiveProviderCode(providerCode, config);

        // --- Trace: create trace ---
        Map<String, Object> traceMetadata = new HashMap<>();
        traceMetadata.put("provider_code", providerCode);
        if (request.getPageContext() != null) {
            traceMetadata.put("page_context", Map.of(
                    "kind", Objects.toString(request.getPageContext().getKind(), ""),
                    "modelCode", Objects.toString(request.getPageContext().getModelCode(), ""),
                    "recordPid", Objects.toString(request.getPageContext().getRecordPid(), "")));
        }
        TraceContext trace = aiTraceService.createTrace(tenantId, request.getSessionId(),
                request.getMessage(), MetaContext.getCurrentUserId(), traceMetadata,
                request.getOtelTraceId());

        // 2. Resolve model and options
        String model = options.getModel();
        if (model == null || model.isBlank()) {
            model = config.getDefaultModel();
        }
        int maxTokens = options.getMaxTokens() != null ? options.getMaxTokens() : config.getMaxTokens();

        // 3. Resolve tools from model context
        String modelCode = null;
        String recordPid = null;
        ChatRequest.PageContext pageCtx = request.getPageContext();
        if (pageCtx != null) {
            modelCode = pageCtx.getModelCode();
            recordPid = pageCtx.getRecordPid();
        }

        // --- D1 Grounding: compile user message → BIF, constrain tools, persist ---
        com.auraboot.framework.agent.dto.BusinessIntentFrame bif = null;
        String qualityIssue = null;
        if (groundingService != null) {
            SpanContext groundingSpan = aiTraceService.startSpan(
                    trace, null, "span", "d1_grounding",
                    buildGroundingSpanInput(request.getMessage(), modelCode, recordPid, request.getSessionId()));
            try {
                Long userIdForGrounding = MetaContext.getCurrentUserId();
                var gctx = com.auraboot.framework.agent.service.GroundingService.GroundingContext.builder()
                        .pageModel(modelCode)
                        .recordPid(recordPid)
                        .conversationId(request.getSessionId())
                        .sessionId(request.getSessionId())
                        .userId(userIdForGrounding == null ? null : userIdForGrounding.toString())
                        .agentCode(com.auraboot.framework.agent.service.ActiveMemoryService.DEFAULT_AGENT)
                        .build();
                bif = groundingService.ground(tenantId, request.getMessage(), gctx);
                com.auraboot.framework.agent.service.BifContext.setCurrentBif(bif);
                if (bifRecorder != null) {
                    bifRecorder.record(tenantId, request.getMessage(), bif, null, request.getSessionId());
                }
                // Spec §5.1 quality gate: record degradation but continue — the LLM
                // can still produce a useful answer; the hint we inject below nudges
                // it to ask for clarification when grounding is uncertain.
                qualityIssue = groundingService.checkQualityGate(bif);
                aiTraceService.endSpan(groundingSpan, Map.of(
                        "intent", bif.getIntent() != null ? bif.getIntent() : "",
                        "object", bif.getObject() != null ? bif.getObject() : "",
                        "mode", bif.getCandidateSkillsMode() != null ? bif.getCandidateSkillsMode() : "",
                        "risk", bif.getRiskLevel() != null ? bif.getRiskLevel() : "",
                        "quality_issue", qualityIssue != null ? qualityIssue : "ok"), "success");
            } catch (Exception e) {
                String error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                String msg = "D1 grounding failed: " + error;
                log.warn("D1 Grounding failed: {}", error, e);
                aiTraceService.endSpan(groundingSpan, Map.of("error", error), "error");
                aiTraceService.endTraceWithError(trace, msg);
                String traceId = trace != null ? trace.getTraceId() : null;
                sink.onError(msg, traceId);
                return new TurnOutcome.Failed(msg, e);
            }
        }

        // --- Trace: resolve tools span ---
        SpanContext resolveSpan = aiTraceService.startSpan(
                trace, null, "span", "resolve_tools",
                buildResolveToolsSpanInput(request.getMessage(), modelCode, recordPid));
        var resolved = chatToolResolver.resolveTools(request.getMessage(), modelCode, recordPid, ctx.channel());
        List<LlmChatRequest.Tool> tools = resolved.tools();
        if (bif != null) {
            tools = applyCandidateSkillsMode(tools, bif);
        }
        ChatToolResolver.ResolvedTools effectiveResolved = tools == resolved.tools()
                ? resolved
                : new ChatToolResolver.ResolvedTools(tools, resolved.intent(), resolved.object(), resolved.isReadOnly());
        String effectiveModelCode = firstNonBlank(modelCode, effectiveResolved.object());
        aiTraceService.endSpan(resolveSpan, buildResolveToolsSpanOutput(tools), "success");

        // --- Trace: render prompt span ---
        SpanContext promptSpan = aiTraceService.startSpan(trace, null, "span", "render_prompt", null);
        List<String> contextWarnings = new ArrayList<>();
        AgentContextBundle contextBundle = buildAgentContextBundle(tenantId, request, contextWarnings);
        // A knowledge base that could not be searched is not a detail to log and move on from: the
        // user is about to read a confident answer that was built without it, and nothing else on
        // screen would tell them apart.
        if (!contextWarnings.isEmpty()) {
            sink.onWarnings(contextWarnings);
        }
        String systemPrompt = buildSystemPrompt(tenantId, request, effectiveResolved, contextBundle);
        if (bif != null) {
            systemPrompt = systemPrompt + buildBifContextHint(bif);
            if (qualityIssue != null) {
                systemPrompt = systemPrompt + buildQualityIssueHint(qualityIssue);
            }
        }
        // PR-77 Phase 3: prepend User Soul Profile grounding section (plan §5.5) so
        // the LLM sees the derived "about this user" block before any other context.
        // Empty when no ACTIVE profile or when the caller is system/cron (null userId).
        if (userSoulProfileReader != null) {
            Long userIdForSoul = MetaContext.getCurrentUserId();
            String userIdStr = userIdForSoul == null ? null : userIdForSoul.toString();
            java.util.Optional<com.auraboot.framework.agent.service.UserSoulProfileReader.ProfileSection> soul =
                    userSoulProfileReader.loadForGrounding(tenantId, userIdStr);
            if (soul.isPresent()) {
                systemPrompt = soul.get().renderedPromptText() + "\n\n" + systemPrompt;
            }
        }
        aiTraceService.endSpan(promptSpan, buildPromptSpanOutput(systemPrompt), "success");

        try {
            TurnOutcome streamOutcome;
            if (tools != null && !tools.isEmpty()) {
                streamOutcome = toolRuntimeAdapterFactory.run(
                        ctx, providerCode, config, model, systemPrompt,
                        request.getHistory(), request.getMessage(), maxTokens, tools,
                        effectiveResolved, effectiveModelCode, request.getSessionId(), contextBundle, sink,
                        userPermissionService, pendingToolStore, pendingToolSnapshotFactory, maxToolRounds);
            } else {
                streamOutcome = streamProvider(providerCode, config, model, systemPrompt,
                        request.getHistory(), request.getMessage(), maxTokens, sink,
                        trace != null ? trace.getTraceId() : null);
            }
            endTraceForStreamOutcome(trace, streamOutcome);
            return streamOutcome;
        } catch (Exception e) {
            log.error("LLM streaming error for provider={}: {}", providerCode, e.getMessage(), e);
            aiTraceService.endTraceWithError(trace, e.getMessage());
            String tid = trace != null ? trace.getTraceId() : null;
            String errMsg = "LLM request failed: " + e.getMessage();
            sink.onError(errMsg, tid);
            return new TurnOutcome.Failed(errMsg, e);
        }
    }

    // =========================================================================
    // System prompt
    // =========================================================================

    /**
     * Build the system prompt, optionally enriched with page context and tool hint.
     * Tries to load the "aurabot_chat" prompt template from CloudConfig first.
     */
    String buildSystemPrompt(Long tenantId, ChatRequest request, ChatToolResolver.ResolvedTools resolved) {
        return buildSystemPrompt(tenantId, request, resolved, buildAgentContextBundle(tenantId, request));
    }

    String buildSystemPrompt(Long tenantId,
                             ChatRequest request,
                             ChatToolResolver.ResolvedTools resolved,
                             AgentContextBundle assembledContextBundle) {
        boolean hasTools = resolved != null && !resolved.tools().isEmpty();
        String toolHint = buildToolHint(resolved);

        // Try template-based prompt
        Map<String, Object> vars = new LinkedHashMap<>();
        ChatRequest.PageContext ctx = request.getPageContext();
        String modelSchemaText = ctx != null ? buildModelSchemaText(ctx.getModelCode()) : "";
        AgentContextBundle contextBundle = assembledContextBundle != null
                ? assembledContextBundle
                : buildAgentContextBundle(tenantId, request);
        String contextSection = contextBundle.renderPromptSection();
        if (ctx != null) {
            vars.put("hasPageContext", true);
            vars.put("pageType", ctx.getKind());
            vars.put("pageKey", ctx.getPageKey());
            vars.put("modelCode", ctx.getModelCode());
            vars.put("tableName", "mt_" + ctx.getModelCode());
            vars.put("modelSchema", modelSchemaText);
            vars.put("recordPid", ctx.getRecordPid());
            if (ctx.getRecordData() != null && !ctx.getRecordData().isEmpty()) {
                vars.put("hasRecordData", true);
                try {
                    vars.put("recordDataJson",
                            "<user-data>\n" + objectMapper.writeValueAsString(ctx.getRecordData()) + "\n</user-data>");
                } catch (Exception e) {
                    // Per-key fallback: skip offending value rather than leak
                    // Java Object#toString shape ({key=val, key2=val2}) into
                    // the prompt. LLM gets partial JSON it can still parse,
                    // with explicit marker for skipped keys. Previous behavior
                    // emitted raw toString() which produced unparseable prompt
                    // and led to LLM hallucinations on context shape. See
                    // deep-review P3 AuraBotChatService:518.
                    log.warn("recordData JSON serialization failed; per-key fallback: {}", e.getMessage());
                    com.fasterxml.jackson.databind.node.ObjectNode safe = objectMapper.createObjectNode();
                    for (java.util.Map.Entry<String, Object> en : ctx.getRecordData().entrySet()) {
                        try {
                            safe.set(en.getKey(), objectMapper.valueToTree(en.getValue()));
                        } catch (Exception innerE) {
                            safe.put(en.getKey(),
                                    "<unserializable:" + (en.getValue() == null
                                            ? "null"
                                            : en.getValue().getClass().getSimpleName()) + ">");
                        }
                    }
                    vars.put("recordDataJson",
                            "<user-data>\n" + safe.toString() + "\n</user-data>");
                }
            }
            if (ctx.getBreadcrumb() != null && !ctx.getBreadcrumb().isEmpty()) {
                vars.put("breadcrumb", String.join(" > ", ctx.getBreadcrumb()));
            }
        }
        vars.put("hasTools", hasTools);

        String rendered = promptTemplateService.render(tenantId, "aurabot_chat", vars);
        if (rendered != null && !rendered.isBlank()) {
            String prompt = hasTools ? rendered + toolHint : rendered;
            if (!contextSection.isBlank()) {
                prompt += "\n\n" + contextSection;
            }
            return prompt;
        }

        // Fallback: build inline system prompt
        StringBuilder sb = new StringBuilder(DEFAULT_SYSTEM_PROMPT);
        if (hasTools) {
            sb.append(toolHint);
        }
        if (!contextSection.isBlank()) {
            sb.append("\n\n").append(contextSection);
        }
        return sb.toString();
    }

    // Package-private overload used by focused prompt tests.
    String buildSystemPrompt(Long tenantId, ChatRequest request) {
        return buildSystemPrompt(tenantId, request, (ChatToolResolver.ResolvedTools) null);
    }

    private AgentContextBundle buildAgentContextBundle(Long tenantId, ChatRequest request) {
        return buildAgentContextBundle(tenantId, request, new ArrayList<>());
    }

    private AgentContextBundle buildAgentContextBundle(Long tenantId, ChatRequest request,
                                                        List<String> warnings) {
        ChatRequest.PageContext ctx = request != null ? request.getPageContext() : null;
        String modelSchemaText = ctx != null ? buildModelSchemaText(ctx.getModelCode()) : "";
        String ragContext = request != null ? resolveRagContext(tenantId, request, warnings) : "";
        AgentContextBundle contextBundle = contextAssembler.assemble(
                new AgentContextAssembler.Request(
                        tenantId,
                        null,
                        ctx,
                        modelSchemaText,
                        ragContext,
                        request != null ? request.getKnowledgeBaseIds() : null));
        return contextBundle;
    }

    /**
     * Build model schema text for system prompt injection.
     * Provides field names, column names, and data types so LLM can write SQL directly
     * without needing to call list_models or information_schema queries.
     */
    private String buildModelSchemaText(String modelCode) {
        if (modelCode == null || modelCode.isBlank()) return "";
        try {
            Optional<ModelDefinition> opt = metaModelService.getModelDefinition(modelCode);
            if (opt.isEmpty() || opt.get().getFields() == null) return "";
            var fields = opt.get().getFields();
            StringBuilder schema = new StringBuilder();
            for (var f : fields) {
                if (SystemFieldConstants.isSystemField(f.getCode())) continue;
                String col = f.getColumnName() != null ? f.getColumnName() : f.getCode();
                schema.append(col).append(" (").append(f.getDataType());
                if (f.getDisplayName() != null) schema.append(", ").append(f.getDisplayName());
                schema.append("), ");
            }
            return schema.length() > 2 ? schema.substring(0, schema.length() - 2) : "";
        } catch (Exception e) {
            log.debug("Failed to build model schema for {}: {}", modelCode, e.getMessage());
            return "";
        }
    }

    /**
     * Append model schema to system prompt StringBuilder.
     */
    private void appendModelSchema(StringBuilder sb, String modelCode) {
        String schema = buildModelSchemaText(modelCode);
        if (!schema.isEmpty()) {
            sb.append("\n\n## Model Schema (mt_").append(modelCode).append(")\n");
            sb.append("Columns: ").append(schema);
            sb.append("\nSystem columns (always available): id, pid, tenant_id, created_at, updated_at, created_by, updated_by");
            sb.append("\nIMPORTANT: Use these column names directly in SQL. No need to call list_models or query information_schema.");
        }
    }

    /**
     * Resolve RAG context from knowledge bases if available.
     * Uses the optional RagContextProvider from the core AI runtime.
     */
    private String resolveRagContext(Long tenantId, ChatRequest request) {
        return resolveRagContext(tenantId, request, new ArrayList<>());
    }

    /**
     * @param warnings collector for a failure the user needs to know about; the caller forwards it
     *                 to the response sink
     */
    private String resolveRagContext(Long tenantId, ChatRequest request, List<String> warnings) {
        if (ragContextProvider == null) return "";
        try {
            List<String> kbIds = request.getKnowledgeBaseIds();
            boolean hasExplicitKbs = kbIds != null && !kbIds.isEmpty();

            // Only query RAG if explicitly requested or tenant has active KBs
            if (!hasExplicitKbs && !ragContextProvider.hasActiveKnowledgeBases(tenantId)) {
                return "";
            }

            String context = ragContextProvider.retrieveContext(tenantId, request.getMessage(), kbIds);
            return context != null ? context : "";
        } catch (Exception e) {
            // The turn must survive a broken knowledge base — but the user must not be left thinking
            // the answer was informed by it. Without this they get a fluent, confident reply built on
            // nothing, and no way to tell it apart from a real one. log.debug meant even the operator
            // could not see it.
            log.warn("RAG context resolution failed for tenant {} — answering without the knowledge "
                    + "base: {}", tenantId, e.getMessage());
            warnings.add("The knowledge base could not be searched, so this answer does not use it. "
                    + "Try again, or check the knowledge base's embedding provider.");
            return "";
        }
    }

    private TurnOutcome streamProvider(String providerCode, ProviderConfig config, String model, String systemPrompt,
                                       List<ChatMessage> history, String userMessage,
                                       int maxTokens, ResponseSink sink, String traceId) throws Exception {
        LlmProvider provider = llmProviderFactory.getProvider(providerCode);
        if (provider == null) {
            String msg = "LLM provider not available: " + providerCode;
            sink.onError(msg, null);
            return new TurnOutcome.Failed(msg, null);
        }

        LlmChatRequest request = LlmChatRequest.builder()
                .providerCode(providerCode)
                .model(model)
                .systemPrompt(systemPrompt)
                .messages(LlmMessageTapeSupport.buildTextMessages(
                        history,
                        ChatMessage::getRole,
                        ChatMessage::getContent,
                        userMessage))
                .maxTokens(maxTokens)
                .build();

        return chatTurnRuntime.streamProviderResponse(
                provider,
                request,
                config.getApiKey(),
                config.getBaseUrl(),
                sink,
                traceId);
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

    private void endTraceForStreamOutcome(TraceContext trace, TurnOutcome outcome) {
        if (outcome instanceof TurnOutcome.Success success) {
            aiTraceService.endTrace(trace, success.finalResponse(), "success");
        } else if (outcome instanceof TurnOutcome.Failed failed) {
            aiTraceService.endTraceWithError(trace, failed.errorMessage());
        } else {
            aiTraceService.endTrace(trace, "[streamed]", "success");
        }
    }

    // =========================================================================
    // (SSE helpers removed in A.3 — all SSE writes go through ResponseSink /
    //  SseResponseSink. byte stream parity is verified by the pre-refactor
    //  baseline at docs/plans/2026-04/sse-baseline-2026-04-26.sha256.)
    // =========================================================================

    /**
     * Constrain the tool list based on the BIF's candidateSkillsMode.
     * - fixed + read_only intent: drop all write (cmd_*) tools
     * - bounded: keep all (LLM may choose); approval gate enforces at execution
     * - hint (default): no filtering
     * Spec: 03-BusinessIntentFrameSpec §1 candidateSkillsMode.
     */
    private List<LlmChatRequest.Tool> applyCandidateSkillsMode(
            List<LlmChatRequest.Tool> tools,
            com.auraboot.framework.agent.dto.BusinessIntentFrame bif) {
        if (tools == null || tools.isEmpty() || bif == null) return tools;
        String mode = bif.getCandidateSkillsMode();
        String actionability = bif.getActionability();
        if (!"fixed".equals(mode) || !"read_only".equals(actionability)) {
            return tools;
        }
        List<LlmChatRequest.Tool> filtered = new ArrayList<>();
        for (LlmChatRequest.Tool t : tools) {
            String name = t.getName() != null ? t.getName() : "";
            if (!name.startsWith("cmd_")) {
                filtered.add(t);
            }
        }
        log.debug("D1 mode=fixed read_only: filtered tools {} → {}", tools.size(), filtered.size());
        return filtered;
    }

    /**
     * Render a compact BIF context hint appended to the system prompt so the LLM
     * knows what the user most likely wants and which candidate skills are in scope.
     */
    private String buildBifContextHint(com.auraboot.framework.agent.dto.BusinessIntentFrame bif) {
        StringBuilder sb = new StringBuilder("\n\n## Intent Analysis (D1)\n");
        sb.append("- intent: ").append(nullSafe(bif.getIntent())).append('\n');
        sb.append("- object: ").append(nullSafe(bif.getObject())).append('\n');
        sb.append("- risk: ").append(nullSafe(bif.getRiskLevel()))
                .append(" (").append(nullSafe(bif.getActionability())).append(")\n");
        if (bif.getCandidateSkills() != null && !bif.getCandidateSkills().isEmpty()) {
            sb.append("- candidate skills (").append(nullSafe(bif.getCandidateSkillsMode())).append("): ")
                    .append(String.join(", ", bif.getCandidateSkills())).append('\n');
        }
        if (bif.getConfidence() != null) {
            sb.append("- confidence: overall=").append(String.format("%.2f", bif.getConfidence().getOverall())).append('\n');
        }
        if (bif.getPreContext() != null && !bif.getPreContext().isEmpty()) {
            sb.append("\n## Relevant memory (Active Memory pre-recall)\n");
            int shown = 0;
            for (java.util.Map<String, Object> snippet : bif.getPreContext()) {
                if (shown++ >= 5) break;  // keep prompt small
                Object title = snippet.get("title");
                Object content = snippet.get("content");
                Object scope = snippet.get("scope");
                sb.append("- [")
                        .append(scope == null ? "" : scope)
                        .append("] ")
                        .append(title == null ? "(no title)" : title);
                if (content != null) {
                    sb.append(": ").append(content);
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    /**
     * Surface a degraded-grounding signal to the LLM so it can choose to ask for
     * clarification instead of running tools with a shaky intent. Spec §5.1.
     */
    private String buildQualityIssueHint(String issue) {
        return "\n- quality_gate: " + issue +
                " — grounding is uncertain; if the user's request is ambiguous, ask a clarifying question before using any write tool.\n";
    }

}
