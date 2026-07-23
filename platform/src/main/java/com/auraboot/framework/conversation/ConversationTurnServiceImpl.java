package com.auraboot.framework.conversation;

import com.auraboot.framework.agent.identity.ChannelSessionResolver;
import com.auraboot.framework.agent.identity.AgentUserProfileResolver;
import com.auraboot.framework.agent.identity.AuraBotAgentResolver;
import com.auraboot.framework.agent.port.AgentChatPort;
import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.service.AgentApprovalGateService;
import com.auraboot.framework.agent.runtime.DurableWorkflowEngine;
import com.auraboot.framework.agent.runtime.TurnExecutionPlanner;
import com.auraboot.framework.agent.runtime.ContextConflictPolicy;
import com.auraboot.framework.agent.runtime.PendingContextFreshnessDecision;
import com.auraboot.framework.agent.runtime.PendingContextFreshnessValidator;
import com.auraboot.framework.agent.runtime.PendingContinuationService;
import com.auraboot.framework.agent.runtime.PendingToolStore;
import com.auraboot.framework.agent.runtime.PendingToolSnapshot;
import com.auraboot.framework.agent.triage.PreGroundingTriage;
import com.auraboot.framework.agent.triage.TriageBucket;
import com.auraboot.framework.agent.triage.TriageRequest;
import com.auraboot.framework.agent.triage.TriageVerdict;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.agent.dto.ChatRequest;
import com.auraboot.framework.aurabot.service.AuraBotChatService;
import com.auraboot.framework.common.util.LogSanitizer;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Phase A.4 / B.0 implementation of {@link ConversationTurnService}. Synchronous core
 * per Q-A.4=A': async only at controller/adapter boundary; the lifecycle
 * {@code begin -> execute -> end/suspend} is sync internal so {@link TurnOutcome}
 * propagates faithfully from the chat impl up to the controller.
 *
 * <p>Phase B.0 (2026-04-27): {@link #runTurn} now dispatches by {@code agentCode}:
 * the aurabot main path goes to {@link AuraBotChatService#executeAuraBotTurn},
 * named agents go to {@link AgentChatPort#runAgentTurn}. This collapses the
 * dual-path scaffold left behind by Phase A.5 and makes the chokepoint claim
 * real for both paths — every Phase B persistence / event / audit feature
 * applies once and covers both.
 *
 * <p>Phase A side effects are NOOP except metrics
 * ({@link TurnSideEffects#observeOnly}). Phase B swaps in real persistence
 * + event emission + audit.
 */
@Slf4j
@Service
public class ConversationTurnServiceImpl implements ConversationTurnService {

    private static final ObjectMapper HASH_MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final AuraBotChatService chatService;
    private final PendingContinuationService pendingContinuationService;
    private final TurnExecutionPlanner turnExecutionPlanner;
    private final TurnSideEffects sideEffects;
    private final PendingToolStore pendingToolStore;
    private final ObjectMapper objectMapper;

    /** Optional named-agent port. When the bean is absent, named-agent traffic
     *  surfaces a Failed outcome through the sink — same observability surface
     *  as any other failure path, no silent fallback. */
    @Autowired(required = false)
    private AgentChatPort agentChatPort;

    /**
     * Phase C.1: Stage 2.5 Pre-Grounding Triage. Optional bean — when absent
     * (OSS without the triage SPI wired), every turn defaults to ACP_RUN
     * which preserves Phase B behavior. {@link DefaultPreGroundingTriage} is
     * the rule-based default impl that ships with the platform.
     */
    @Autowired(required = false)
    private PreGroundingTriage preGroundingTriage;

    /**
     * Durable substrate used when a turn requires checkpointable ACP execution.
     * Optional so OSS/test contexts without the ACP runtime fail closed instead
     * of silently falling back to the legacy aurabot chat path.
     */
    @Autowired(required = false)
    private DurableWorkflowEngine durableWorkflowEngine;

    /**
     * Phase C.3c: needed for named-agent task chain persistence. ACP durable
     * task/run creation is owned by {@link DurableWorkflowEngine}.
     */
    @Autowired(required = false)
    private DynamicDataMapper dynamicDataMapper;

    /**
     * Phase C.3d (Q-C3.3=α): approval-gate convergence. {@link #resumeTurn}
     * dispatches a resume call to either the legacy {@link PendingToolStore}
     * pending-tool path or the ACP {@code ab_agent_approval} path; this bean
     * services the latter. Optional so OSS deployments without the ACP
     * runtime keep building.
     */
    @Autowired(required = false)
    private AgentApprovalGateService agentApprovalGateService;

    @Autowired(required = false)
    private PendingContextFreshnessValidator pendingContextFreshnessValidator;

    /**
     * GAP-295: 4-tuple session resolution. Optional bean — when absent, every
     * turn keeps {@code channelSessionId=null} (Phase A behavior preserved).
     * The default {@link com.auraboot.framework.agent.identity.ChannelSessionResolverImpl}
     * is always wired in OSS; required=false keeps unit tests that build the
     * impl without identity infrastructure functional.
     */
    @Autowired(required = false)
    private ChannelSessionResolver channelSessionResolver;

    @Autowired(required = false)
    private AgentUserProfileResolver agentUserProfileResolver;

    public ConversationTurnServiceImpl(AuraBotChatService chatService,
                                        PendingContinuationService pendingContinuationService,
                                        TurnExecutionPlanner turnExecutionPlanner,
                                        @Qualifier("turnSideEffects") TurnSideEffects sideEffects,
                                        PendingToolStore pendingToolStore,
                                        ObjectMapper objectMapper) {
        this.chatService = chatService;
        this.pendingContinuationService = pendingContinuationService;
        this.turnExecutionPlanner = turnExecutionPlanner;
        this.sideEffects = sideEffects;
        this.pendingToolStore = pendingToolStore;
        this.objectMapper = objectMapper;
    }

    @Override
    public TurnOutcome runTurn(TurnRequest request, ResponseSink sink) {
        // Publish which conversation this turn is about, so a tool whose subject IS the conversation
        // (escalate to a human, summarise the thread) can act on it — the LLM cannot supply an id it
        // has never seen. Cleared in the finally: a leaked scope would become the next pooled
        // request's conversation.
        TurnScopeContext.set(request.conversationId(), request.channel());
        try {
            return runTurnDispatch(request, sink);
        } finally {
            TurnScopeContext.clear();
        }
    }


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

    /**
     * Make the built-in assistant honour its own configured model, the way named agents
     * already do.
     *
     * <p>A named agent picks its provider from {@code agent_definition.model}
     * (AgentRunService via LlmRuntimeResolver). The aurabot path did not: it resolved the
     * provider from the per-request options alone, and when the caller sent neither a model
     * nor a provider it fell to "the first enabled provider with a key" — an order-dependent
     * default that ignored the aurabot row's own {@code model} column entirely. So an admin
     * who set the tenant assistant to qwen still got whichever vendor happened to be first,
     * and setting the column looked like it did nothing.
     *
     * <p>This fills the model in from the aurabot definition when — and only when — the
     * request specified neither model nor provider. An explicit per-request choice always
     * wins, and a null column (the seeded default) is a no-op, so existing tenants are
     * unchanged until someone deliberately configures the assistant's model.
     */
    private void applyConfiguredAssistantModel(Long tenantId, ChatRequest legacyRequest) {
        if (legacyRequest == null || tenantId == null) {
            return;
        }
        ChatRequest.ChatOptions options = legacyRequest.getOptions();
        if (options != null
                && ((options.getModel() != null && !options.getModel().isBlank())
                    || (options.getProvider() != null && !options.getProvider().isBlank()))) {
            return; // an explicit per-request choice wins
        }
        String configuredModel = configuredAgentModel(tenantId, "aurabot");
        if (configuredModel == null || configuredModel.isBlank()) {
            return; // no configured model — keep today's default-provider behaviour
        }
        if (options == null) {
            options = new ChatRequest.ChatOptions();
            legacyRequest.setOptions(options);
        }
        options.setModel(configuredModel);
    }

    /** The {@code model} column of an agent definition, or {@code null} if unset/absent. */
    private String configuredAgentModel(Long tenantId, String agentCode) {
        try {
            String sql = "SELECT model FROM ab_agent_definition WHERE tenant_id = #{params.tenantId} "
                    + "AND agent_code = #{params.agentCode} AND deleted_flag = FALSE LIMIT 1";
            java.util.List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                    Map.of("tenantId", tenantId, "agentCode", agentCode));
            if (rows.isEmpty()) {
                return null;
            }
            Object model = rows.get(0).get("model");
            return model == null ? null : model.toString();
        } catch (Exception e) {
            // Provider resolution has its own fallback; an optional lookup must never
            // break the turn.
            log.debug("assistant model lookup failed for tenant {}: {}", tenantId, e.getMessage());
            return null;
        }
    }

    private TurnOutcome runTurnDispatch(TurnRequest request, ResponseSink sink) {
        TurnContext ctx = beginTurn(request);
        sideEffects.metricsRecorder().recordTurnBegin(ctx);
        sink.onTurnBegin(ctx.turnId(), null, request.conversationId(),
                          request.inboundMessageId(), request.userId());

        // D.1: wrap the sink so Anthropic Extended Thinking blocks emitted via
        // {@code onThinking} are captured for persistence at finalizeTurn time
        // while still being forwarded to the SSE transport unchanged.
        ThinkingCapturingResponseSink capturingSink = new ThinkingCapturingResponseSink(sink);

        TurnOutcome outcome;
        TurnRoute route = null;
        try {
            ChatRequest legacyRequest = request.legacyRequest();
            String agentCode = request.agentCode();
            TurnExecutionPlanner.InitialExecutionMode initialMode;
            TurnExecutionPlanner.TurnExecutionPlan turnPlan = null;
            if (TurnExecutionPlanner.isRagOnlyChannel(request.channel())) {
                // RAG-only channel (embeddable CS widget): pure knowledge Q&A. Never route to the
                // durable/planner path regardless of triage bucket — otherwise a "cancel account /
                // export data" question is classified as a task and runs execute_sql, looping on tool
                // rounds or demanding human approval on a customer-facing widget (the RAG-only channel
                // tool gate in ChatToolResolver only covers the SYNC path). Force the sync RAG turn.
                initialMode = TurnExecutionPlanner.InitialExecutionMode.SYNC_AGENT_TURN;
                route = TurnRoute.ragOnlyForced();
                log.debug("Agent turn execution plan: turnId={}, RAG-only channel {} forced to SYNC_AGENT_TURN",
                        ctx.turnId(), request.channel());
            } else {
                turnPlan = turnExecutionPlanner.decide(
                        new TurnExecutionPlanner.TurnExecutionInput(
                                agentCode,
                                ctx.triageBucket(),
                                ctx.allowedReadOnlyTools(),
                                optionFlag(request, "explicitDurableRequest", "durableWorkflow", "durable"),
                                optionFlag(request, "requiresApproval"),
                                optionFlag(request, "externalSideEffect"),
                                optionFlag(request, "batch")));
                initialMode = turnPlan.initialMode();
                route = TurnRoute.from(turnPlan);
                log.debug("Agent turn execution plan: turnId={}, initialMode={}, reason={}, signals={}",
                        ctx.turnId(), initialMode, turnPlan.reason(), turnPlan.policySignals());
            }
            if (turnPlan != null
                    && turnPlan.reason() == TurnExecutionPlanner.DecisionReason.NAMED_AGENT_DURABLE_UNSUPPORTED) {
                // Review G8: an explicit durable flag addressed at a named agent is a
                // contradiction — the conversation durable engine runs as the default
                // agent only (AcpDurableWorkflowEngine pins assignee to DEFAULT_AGENT),
                // and silently downgrading to chat would drop the checkpoint/resume
                // semantics the caller asked for. Fail loudly instead of guessing.
                String msg = "Named agent '" + turnPlan.normalizedAgentCode()
                        + "' cannot execute an explicitly durable request"
                        + " (explicitDurableRequest / externalSideEffect / batch):"
                        + " durable conversation runs execute as the default agent only."
                        + " Drop the durable option or address the default assistant.";
                log.warn("runTurn rejected named-agent durable conflict: turnId={}, agent={}, signals={}",
                        ctx.turnId(), turnPlan.normalizedAgentCode(), turnPlan.policySignals());
                capturingSink.onError(msg, null);
                outcome = new TurnOutcome.Failed(msg, null);
            } else if (initialMode == TurnExecutionPlanner.InitialExecutionMode.NAMED_AGENT_TURN) {
                outcome = dispatchToNamedAgent(ctx, request, legacyRequest, capturingSink, agentCode);
            } else {
                if (initialMode == TurnExecutionPlanner.InitialExecutionMode.DURABLE_WORKFLOW) {
                    outcome = isAcpRuntimeWired()
                            ? dispatchToAcpRun(ctx, legacyRequest, capturingSink)
                            : acpRuntimeUnavailableOutcome(ctx, capturingSink);
                } else {
                    applyConfiguredAssistantModel(ctx.tenantId(), legacyRequest);
                    outcome = chatService.executeAuraBotTurn(ctx, legacyRequest, capturingSink);
                }
            }
            if (outcome == null) {
                // Defensive: chat impls always return non-null, but if a future
                // refactor drops a return path we surface it as Failed rather than NPE later.
                String msg = "chat impl returned null outcome (agentCode=" + agentCode + ")";
                log.error(msg);
                outcome = new TurnOutcome.Failed(msg, null);
            }
        } catch (Exception e) {
            log.error("runTurn caught chat impl exception: {}", e.getMessage(), e);
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            try {
                capturingSink.onError(msg, null);
            } catch (Exception sinkEx) {
                log.warn("runTurn failed to emit sink error after chat impl exception: {}", sinkEx.getMessage(), sinkEx);
            }
            outcome = new TurnOutcome.Failed(msg, e);
        }

        try {
            finalizeTurn(ctx, outcome, TurnArtifacts.of(
                    capturingSink.capturedContent(), capturingSink.capturedSignature()), route);
        } catch (Exception e) {
            // Side effects must never block the outcome from being returned to the caller,
            // but the failure must not vanish silently (P-006).
            recordFinalizeFailure(ctx, outcome, e);
        }
        return outcome;
    }

    @Override
    public TurnOutcome resumeTurn(String pendingTurnId, ConfirmDecision decision, ResponseSink sink) {
        if (pendingTurnId == null || pendingTurnId.isBlank()) {
            String msg = "resumeTurn called without pendingTurnId";
            log.warn(msg);
            sink.onError(msg, null);
            return new TurnOutcome.Failed(msg, null);
        }
        if (decision == null) {
            decision = ConfirmDecision.DENIED;
        }

        // Phase C.3d (Q-C3.3=α): the resumption token is opaque to the
        // frontend — it can be either (a) a PendingToolSnapshot key
        // (legacy chat tool-loop suspension) or (b) an ab_agent_approval.pid
        // (ACP approval gate and approval-keyed chat pending tools). Approval
        // lookup must win when both stores contain the same token; otherwise a
        // forged / stale approvalPid could bypass policy authorization by
        // consuming the chat pending payload directly.
        if (agentApprovalGateService != null) {
            Long tenantId = MetaContext.getCurrentTenantId();
            Map<String, Object> approval = tenantId != null
                    ? agentApprovalGateService.findApproval(tenantId, pendingTurnId)
                    : null;
            if (approval != null) {
                return resumeAcpApproval(approval, decision, sink);
            }
        }

        PendingToolSnapshot pending = pendingToolStore.consumePendingForOwner(
                pendingTurnId, MetaContext.getCurrentTenantId(), MetaContext.getCurrentUserId());
        if (pending != null) {
            return resumeChatPendingTool(pending, decision, sink);
        }

        String msg = "No pending tool or approval found for pendingTurnId=" + pendingTurnId
                + " (expired or already consumed)";
        log.warn(msg);
        sink.onError(msg, null);
        return new TurnOutcome.Failed(msg, null);
    }

    /** Legacy chat tool-loop suspension resume (Phase B.6 path). Behaviour
     *  is unchanged from pre-C.3d. */
    private TurnOutcome resumeChatPendingTool(PendingToolSnapshot pending,
                                               ConfirmDecision decision, ResponseSink sink) {
        // 1. Identity validation: the caller must own the suspended turn.
        TurnOutcome identityFailure = validateIdentity(pending);
        if (identityFailure != null) {
            sink.onError(((TurnOutcome.Failed) identityFailure).errorMessage(), null);
            return identityFailure;
        }
        TurnOutcome expirationFailure = validatePendingNotExpired(pending);
        if (expirationFailure != null) {
            sink.onError(((TurnOutcome.Failed) expirationFailure).errorMessage(), null);
            return expirationFailure;
        }
        if (decision == ConfirmDecision.APPROVED) {
            TurnOutcome integrityFailure = validatePendingIntegrity(pending);
            if (integrityFailure != null) {
                sink.onError(((TurnOutcome.Failed) integrityFailure).errorMessage(), null);
                return integrityFailure;
            }
            TurnOutcome freshnessFailure = validatePendingContextFreshness(pending);
            if (freshnessFailure != null) {
                sink.onError(((TurnOutcome.Failed) freshnessFailure).errorMessage(), null);
                return freshnessFailure;
            }
        }

        // 2. Rebuild TurnContext from the saved pending state.
        TurnContext ctx = rebuildContext(pending);
        sideEffects.metricsRecorder().recordTurnBegin(ctx);

        // D.1: same wrapping discipline as runTurn — the resumed Anthropic
        // stream may still emit thinking blocks before its text answer.
        ThinkingCapturingResponseSink capturingSink = new ThinkingCapturingResponseSink(sink);

        // 3. Dispatch by decision.
        TurnOutcome outcome;
        try {
            outcome = switch (decision) {
                case APPROVED -> pendingContinuationService.resumeApprovedChatTool(ctx, pending, capturingSink);
                case DENIED -> {
                    String reason = "User denied the operation";
                    capturingSink.onDone("", null);
                    yield new TurnOutcome.Interrupted(reason, "user_denied");
                }
                case CANCELLED -> {
                    String reason = "User cancelled the operation";
                    capturingSink.onDone("", null);
                    yield new TurnOutcome.Interrupted(reason, "user_cancelled");
                }
            };
            if (outcome == null) {
                String msg = "resumeTurn pending continuation returned null outcome";
                log.error(msg);
                outcome = new TurnOutcome.Failed(msg, null);
            }
        } catch (Exception e) {
            log.error("resumeTurn caught pending continuation exception: {}", e.getMessage(), e);
            outcome = new TurnOutcome.Failed(e.getMessage(), e);
        }

        try {
            finalizeTurn(ctx, outcome, TurnArtifacts.of(
                    capturingSink.capturedContent(), capturingSink.capturedSignature()),
                    TurnRoute.resumedAfterConfirmation());
        } catch (Exception e) {
            recordFinalizeFailure(ctx, outcome, e);
        }
        return outcome;
    }

    private boolean optionFlag(TurnRequest request, String... keys) {
        Map<String, Object> options = request != null ? request.options() : null;
        if (options == null || options.isEmpty() || keys == null) {
            return false;
        }
        for (String key : keys) {
            if (key == null) {
                continue;
            }
            Object value = options.get(key);
            if (value instanceof Boolean b && b) {
                return true;
            }
            if (value instanceof Number n && n.longValue() != 0L) {
                return true;
            }
            if (value instanceof String s && Boolean.parseBoolean(s.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Phase C.3d (Q-C3.3=α): ACP approval resume. Drives the approve / reject
     * decision through {@link AgentApprovalGateService} (with the auto-resume
     * @Async dispatch suppressed) and then delegates the continuation to the
     * durable workflow substrate so the resume outcome streams back through
     * the SSE sink the user is still listening on.
     *
     * <p>Tenant identity is checked at the {@code findApproval} call site —
     * the lookup is scoped by tenant — so callers from another tenant get a
     * "not found" error rather than a successful cross-tenant approve, which
     * is the same security posture the legacy chat path enforces via
     * {@link #validateIdentity}.
     */
    private TurnOutcome resumeAcpApproval(Map<String, Object> approval, ConfirmDecision decision,
                                            ResponseSink sink) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long approverId = MetaContext.getCurrentUserId();
        String approvalPid = (String) approval.get("pid");
        String runPid = (String) approval.get("run_id");
        String taskPid = (String) approval.get("task_id");
        String existingStatus = (String) approval.get("approval_status");

        if (tenantId == null || approverId == null) {
            String msg = "resumeTurn (ACP path) requires authenticated identity";
            log.warn(msg);
            sink.onError(msg, null);
            return new TurnOutcome.Failed(msg, null);
        }
        if (!"pending".equals(existingStatus)) {
            String msg = "Approval " + approvalPid + " is no longer pending (status=" + existingStatus + ")";
            log.warn(msg);
            sink.onError(msg, null);
            return new TurnOutcome.Failed(msg, null);
        }

        // Rebuild a TurnContext that points at this approval's run; the
        // chokepoint metric / event chain reads turnId etc. from it.
        TurnContext ctx = new TurnContext(
                approvalPid,                                  // turnId — reuse approvalPid for trace correlation
                tenantId,
                approverId,
                MetaContext.getCurrentMemberId(),
                null,                                         // agentId
                AuraBotAgentResolver.DEFAULT_AGENT_CODE,        // agentCode (ACP resume)
                null,                                         // channelSessionId
                null,                                         // conversationId — not stored on approval row
                null,                                         // inboundMessageId
                null,                                         // triageBucket — N/A on resume
                Set.of(),                                     // allowedReadOnlyTools — N/A on resume
                null,                                         // traceId
                taskPid,                                      // taskPid from approval row
                java.time.Instant.now());
        sideEffects.metricsRecorder().recordTurnBegin(ctx);

        // D.1: capture thinking from any Anthropic streams the ACP resume runs.
        ThinkingCapturingResponseSink capturingSink = new ThinkingCapturingResponseSink(sink);

        TurnOutcome outcome;
        try {
            outcome = switch (decision) {
                case APPROVED -> {
                    Map<String, Object> approved = agentApprovalGateService.approve(
                            tenantId, approvalPid, approverId, /*triggerAutoResume=*/ false);
                    if (approved == null) {
                        String msg = "Approval " + approvalPid + " could not be approved (terminal state)";
                        capturingSink.onError(msg, null);
                        yield new TurnOutcome.Failed(msg, null);
                    }
                    Map<String, Object> chatToolResult = agentChatPort != null
                            ? agentChatPort.executeApprovedPendingTool(tenantId, approvalPid)
                            : Map.of("handled", false);
                    if (Boolean.TRUE.equals(chatToolResult.get("handled"))) {
                        yield mapApprovedChatToolOutcome(approvalPid, chatToolResult, capturingSink);
                    }
                    yield syncResumeAcpRun(ctx, taskPid, runPid, capturingSink);
                }
                case DENIED -> {
                    agentApprovalGateService.reject(tenantId, approvalPid, approverId, "User denied the operation");
                    capturingSink.onDone("", null);
                    yield new TurnOutcome.Interrupted("User denied the operation", "user_denied");
                }
                case CANCELLED -> {
                    agentApprovalGateService.reject(tenantId, approvalPid, approverId, "User cancelled the operation");
                    capturingSink.onDone("", null);
                    yield new TurnOutcome.Interrupted("User cancelled the operation", "user_cancelled");
                }
            };
        } catch (Exception e) {
            log.error("resumeAcpApproval failed: {}", e.getMessage(), e);
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            capturingSink.onError(msg, null);
            outcome = new TurnOutcome.Failed(msg, e);
        }

        try {
            // ACP approval resume: continuation executes on the durable engine.
            finalizeTurn(ctx, outcome, TurnArtifacts.of(
                    capturingSink.capturedContent(), capturingSink.capturedSignature()),
                    new TurnRoute(
                            TurnExecutionPlanner.InitialExecutionMode.DURABLE_WORKFLOW.name(),
                            "RESUMED_AFTER_APPROVAL",
                            java.util.List.of("RESUME_PATH")));
        } catch (Exception e) {
            recordFinalizeFailure(ctx, outcome, e);
        }
        return outcome;
    }

    private TurnOutcome mapApprovedChatToolOutcome(String approvalPid, Map<String, Object> chatToolResult,
                                                    ResponseSink sink) {
        boolean success = Boolean.TRUE.equals(chatToolResult.get("success"));
        Map<String, Object> meta = new LinkedHashMap<>(chatToolResult);
        meta.put("approvalPid", approvalPid);
        if (success) {
            String msg = "Approved tool executed.";
            sink.onDone(msg, null);
            return new TurnOutcome.Success(msg, meta);
        }
        String error = String.valueOf(chatToolResult.getOrDefault(
                "error", "Approved tool execution failed. No data was changed."));
        sink.onError(error, null);
        return new TurnOutcome.Failed(error, null);
    }

    /**
     * Drive a durable ACP run resume from an approved approval. The durable
     * substrate owns the ACP run loop and result streaming.
     */
    private TurnOutcome syncResumeAcpRun(TurnContext ctx, String taskPid, String runPid,
                                          ResponseSink sink) {
        if (durableWorkflowEngine == null || taskPid == null || runPid == null) {
            String msg = "ACP resume blocked: DurableWorkflowEngine is not available (taskPid=" + taskPid
                    + ", runPid=" + runPid
                    + ", durableWorkflowEngine=" + (durableWorkflowEngine != null) + ")";
            log.error(msg);
            sink.onError(msg, null);
            return new TurnOutcome.Failed(msg, null);
        }
        return durableWorkflowEngine.resumeConversationRun(ctx, taskPid, runPid, sink);
    }

    /**
     * Validate the requesting user actually owns the suspended turn. Without
     * this, a malicious client knowing a {@code pendingTurnId} could resume
     * someone else's turn (since pendingTurnId is a public-ish PID echoed back
     * via SSE, an attacker who guesses or sniffs one should not be able to
     * execute the pending tool).
     */
    private TurnOutcome validateIdentity(PendingToolSnapshot pending) {
        Long currentTenantId = MetaContext.getCurrentTenantId();
        Long currentUserId = MetaContext.getCurrentUserId();
        if (pending.getTenantId() == null || pending.getUserId() == null) {
            // Pre-B.6 entries (if any leaked through during deploy) — refuse to
            // resume rather than risking cross-user execution.
            String msg = "pending tool entry missing identity tuple — refusing resume";
            log.warn(msg);
            return new TurnOutcome.Failed(msg, null);
        }
        if (currentTenantId == null || !currentTenantId.equals(pending.getTenantId())) {
            String msg = "tenant mismatch on resumeTurn (current=" + currentTenantId
                    + ", suspended=" + pending.getTenantId() + ")";
            log.warn(msg);
            return new TurnOutcome.Failed(msg, null);
        }
        if (currentUserId == null || !currentUserId.equals(pending.getUserId())) {
            String msg = "user mismatch on resumeTurn (current=" + currentUserId
                    + ", suspended=" + pending.getUserId() + ")";
            log.warn(msg);
            return new TurnOutcome.Failed(msg, null);
        }
        return null;
    }

    private TurnOutcome validatePendingNotExpired(PendingToolSnapshot pending) {
        Long expiresAt = pending.getExpiresAt();
        if (expiresAt == null) {
            return null;
        }
        long now = java.time.Instant.now().toEpochMilli();
        if (expiresAt > now) {
            return null;
        }
        String msg = "pending tool entry expired — refusing resume";
        log.warn("{} (turnId={}, toolName={}, expiresAt={})",
                msg,
                pending.getTurnId(),
                pending.getToolName(),
                expiresAt);
        return new TurnOutcome.Failed(msg, null);
    }

    private TurnOutcome validatePendingIntegrity(PendingToolSnapshot pending) {
        if (hasText(pending.getArgsHash())) {
            String expectedArgsHash = hashMap(pending.getInput());
            if (!pending.getArgsHash().equals(expectedArgsHash)) {
                String msg = "pending tool args hash mismatch — refusing resume";
                log.warn("{} (turnId={}, toolName={})", msg, pending.getTurnId(), pending.getToolName());
                return new TurnOutcome.Failed(msg, null);
            }
        }
        if (hasText(pending.getToolSchemaHash())) {
            Map<String, Object> schema = resolvePendingToolSchema(pending);
            if (schema != null) {
                String expectedSchemaHash = hashMap(schema);
                if (!pending.getToolSchemaHash().equals(expectedSchemaHash)) {
                    String msg = "pending tool schema hash mismatch — refusing resume";
                    log.warn("{} (turnId={}, toolName={})", msg, pending.getTurnId(), pending.getToolName());
                    return new TurnOutcome.Failed(msg, null);
                }
            }
        }
        if (hasText(pending.getPreviewHash())) {
            String expectedPreviewHash = hashText(pending.getPreview());
            if (!pending.getPreviewHash().equals(expectedPreviewHash)) {
                String msg = "pending tool preview hash mismatch — refusing resume";
                log.warn("{} (turnId={}, toolName={})", msg, pending.getTurnId(), pending.getToolName());
                return new TurnOutcome.Failed(msg, null);
            }
        }
        return null;
    }

    private TurnOutcome validatePendingContextFreshness(PendingToolSnapshot pending) {
        if (pendingContextFreshnessValidator == null) {
            return null;
        }
        PendingContextFreshnessDecision decision;
        try {
            decision = pendingContextFreshnessValidator.validate(pending);
        } catch (RuntimeException e) {
            // CATCH: validator is a non-transactional guard; fail closed before executing side effects.
            String msg = "pending context freshness validation failed — refusing resume";
            log.warn("{} (turnId={}, toolName={}, error={})",
                    msg, pending.getTurnId(), pending.getToolName(), e.getClass().getSimpleName());
            return new TurnOutcome.Failed(msg, e);
        }
        if (decision == null || decision.fresh()) {
            return null;
        }
        ContextConflictPolicy policy = decision.conflictPolicy() != null
                ? decision.conflictPolicy()
                : ContextConflictPolicy.REJECT_AND_REPLAN;
        if (policy == ContextConflictPolicy.ALLOW_IF_NON_CRITICAL) {
            log.info("Pending context freshness conflict allowed as non-critical: turnId={}, toolName={}, reason={}",
                    pending.getTurnId(), pending.getToolName(), decision.reasonCode());
            return null;
        }
        String msg = "pending context freshness conflict — refusing resume"
                + (hasText(decision.reasonCode()) ? ": " + decision.reasonCode() : "");
        log.warn("{} (turnId={}, toolName={}, policy={}, message={})",
                msg, pending.getTurnId(), pending.getToolName(), policy, decision.message());
        return new TurnOutcome.Failed(msg, null);
    }

    private Map<String, Object> resolvePendingToolSchema(PendingToolSnapshot pending) {
        if (pending.getAgentToolDefinitions() == null || pending.getAgentToolDefinitions().isEmpty()) {
            return null;
        }
        for (AgentToolDefinition definition : pending.getAgentToolDefinitions()) {
            if (definition == null || !equalsAny(pending.getToolName(), definition.getName(), definition.getSourceCode())) {
                continue;
            }
            return definition.getInputSchema() != null ? definition.getInputSchema() : Map.of();
        }
        return null;
    }

    private String hashMap(Map<String, Object> value) {
        try {
            byte[] bytes = HASH_MAPPER.writeValueAsBytes(value == null ? Map.of() : value);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception e) {
            return HexFormat.of().formatHex(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
        }
    }

    private String hashText(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return HexFormat.of().formatHex(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
        }
    }

    private boolean equalsAny(String target, String... values) {
        if (target == null || values == null) {
            return false;
        }
        for (String value : values) {
            if (target.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** F1: tolerate null / unknown names from pre-F1 pending rows — a stale
     *  snapshot must degrade to "bucket unknown", never fail the resume. */
    private static TriageBucket parseTriageBucket(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return TriageBucket.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private TurnContext rebuildContext(PendingToolSnapshot pending) {
        // GAP-295 resume path: re-attach the channel session captured at
        // suspend. We trust the stored pid (PendingTool already passed
        // identity validation in validateIdentity) but defensively use
        // findByPid so a stale/missing row falls back to null instead of
        // propagating a phantom pid into TurnContext.
        String channelSessionId = resolveResumeChannelSessionId(
                pending.getChannelSessionPid(), pending.getTenantId());
        return new TurnContext(
                pending.getTurnId(),
                pending.getTenantId(),
                pending.getUserId(),
                pending.getHumanMemberId(),
                null,                                  // agentId — Phase B/B+ AuraBotAgentResolver
                pending.getAgentCode(),                // DC.3c Fix 2: preserve named-agent identity across resume
                pending.getChannel(),                  // preserve channel for downstream policy gates
                pending.getProfileId(),                // preserve user profile for downstream policy gates
                channelSessionId,                      // GAP-295 resume: re-attached via findByPid
                pending.getConversationId(),
                null,                                  // inboundMessageId — already persisted at suspend time
                parseTriageBucket(pending.getTriageBucket()),  // F1: restore routing semantics across resume
                Set.of(),                              // allowedReadOnlyTools — already suspended
                null,                                  // traceId — chat impl re-attaches via aiTraceService.findActiveTrace
                pending.getTaskPid(),                  // DC.3c: resume finalization closes the original named-agent task
                java.time.Instant.now());
    }

    /**
     * GAP-295 resume path: re-attach the channel session pid captured on the
     * pending state. Returns null when the resolver bean is absent (test
     * contexts), the pid is null (pre-GAP-295 pending entries that leaked
     * through), or the row no longer exists (TTL / cleanup). Failures keep
     * resume working — channel session is observability state, not a hard
     * dependency.
     */
    private String resolveResumeChannelSessionId(String channelSessionPid, Long tenantId) {
        if (channelSessionResolver == null || channelSessionPid == null || tenantId == null) {
            return null;
        }
        try {
            return channelSessionResolver.findByPid(channelSessionPid, tenantId)
                    .map(ChannelSessionResolver.ChannelSession::pid)
                    .orElse(null);
        } catch (Exception e) {
            log.warn("GAP-295 resume: findByPid failed for pid={} tenantId={}: {}",
                    channelSessionPid, tenantId, e.getMessage());
            return null;
        }
    }

    /**
     * Phase B.0: named-agent dispatch. The {@link AgentChatPort} bean is optional
     * (the OSS distribution may not include the ACP runtime), so handle absence
     * + agent-not-found symmetrically through the sink + Failed outcome rather
     * than throwing or silently falling through to aurabot.
     *
     * <p>DC.3c (design v5 §10.7 Fix 3): the chokepoint owns the
     * {@code ab_agent_task} row lifecycle for named-agent dispatch:
     * <ul>
     *   <li>{@link #createNamedAgentTask} writes a row at entry, with
     *       {@code parent_id = request.parentTaskPid()} (null for root, set
     *       for handoff hops).</li>
     *   <li>{@code TurnContext.withTaskPid} threads the new pid into the
     *       ctx that {@link AgentChatPort#runAgentTurn} sees.</li>
     *   <li>{@link #attachTaskPidToOutcome} ensures the returned
     *       {@code TurnOutcome.Success} carries {@code meta._taskPid} so the
     *       caller can use it as the next hop's parentTaskPid.</li>
     *   <li>{@link #finalizeTurn} closes the task by outcome type.</li>
     * </ul>
     * AgentReplyTask used to write task rows itself in D.3 — that path is
     * removed here in favour of single chokepoint ownership.
     */
    private TurnOutcome dispatchToNamedAgent(TurnContext ctx, TurnRequest request,
                                              ChatRequest legacyRequest, ResponseSink sink,
                                              String agentCode) {
        if (agentChatPort == null) {
            String msg = "Named agent requested (agentCode=" + agentCode + ") but AgentChatPort " +
                    "is not available in the current runtime.";
            log.warn(msg);
            sink.onError(msg, null);
            return new TurnOutcome.Failed(msg, null);
        }
        boolean agentExists;
        try {
            agentExists = agentChatPort.agentExists(ctx.tenantId(), agentCode);
        } catch (Exception e) {
            String msg = safeExceptionMessage(e);
            log.warn("Named-agent existence lookup failed: agentCode={}, errorType={}",
                    agentCode, e.getClass().getSimpleName());
            sink.onError(msg, null);
            return new TurnOutcome.Failed(msg, e);
        }
        if (!agentExists) {
            // Two very different situations shared one sentence, and it named the
            // agent_code — an internal identifier the reader did not choose and
            // cannot act on (§2.2 forbids raw codes in user-facing text). The
            // common case by far is an operator having suspended this colleague
            // on purpose; being told it "was not found" sends them looking for a
            // deleted record instead of the Resume button.
            String msg = agentDefinitionExists(ctx.tenantId(), agentCode)
                    ? "This AI colleague is suspended and is not taking new work. "
                      + "An administrator can resume it from its profile page."
                    : "This AI colleague is no longer available.";
            sink.onError(msg, null);
            return new TurnOutcome.Failed(msg, null);
        }

        // DC.3c Fix 3: create ab_agent_task row before invoking AgentChatPort,
        // with parent_id linking to the upstream hop on a handoff chain.
        String taskPid;
        try {
            taskPid = createNamedAgentTask(ctx, request, agentCode);
        } catch (Exception e) {
            String msg = safeExceptionMessage(e);
            log.error("Named-agent task creation failed: agentCode={}, errorType={}",
                    agentCode, e.getClass().getSimpleName());
            sink.onError(msg, null);
            return new TurnOutcome.Failed(msg, e);
        }
        TurnContext ctxWithTask = (taskPid != null) ? ctx.withTaskPid(taskPid) : ctx;

        log.info("Chat request delegated to named agent: agentCode={}, tenantId={}, turnId={}, taskPid={}, parentTaskPid={}, overrides={}",
                agentCode, ctx.tenantId(), ctx.turnId(), taskPid, request.parentTaskPid(),
                request.overrides() != null);
        // DC.3a: pass server-only overrides through to the AgentChatPort SPI
        // (4-arg variant). Aurabot REST callers always pass null; group-chat
        // AgentReplyTask passes a populated AgentTurnOverrides.
        TurnOutcome outcome = agentChatPort.runAgentTurn(ctxWithTask, legacyRequest, sink, request.overrides());
        // Surface taskPid on Success.meta so AgentReplyTask (handoff caller) can
        // use it as parentTaskPid for the next hop.
        return attachTaskPidToOutcome(outcome, taskPid);
    }

    /**
     * DC.3c Fix 3: create an {@code ab_agent_task} row for a named-agent turn
     * dispatched through the chokepoint. Returns the pid. Missing or failing task
     * persistence fails the turn before {@link AgentChatPort} runs so the task
     * chain cannot silently disappear.
     */
    private String createNamedAgentTask(TurnContext ctx, TurnRequest request, String agentCode) {
        if (dynamicDataMapper == null) {
            throw new IllegalStateException("Named-agent task persistence is unavailable");
        }
        try {
            String taskPid = com.auraboot.framework.common.util.UniqueIdGenerator.generate();
            java.util.Map<String, Object> row = new java.util.HashMap<>();
            row.put("pid", taskPid);
            row.put("tenant_id", ctx.tenantId());
            row.put("title", buildNamedAgentTaskTitle(request));
            row.put("description", request.userMessage() != null ? request.userMessage() : "");
            row.put("task_status", "in_progress");
            row.put("task_priority", "normal");
            row.put("assignee_type", "ai");
            row.put("assignee_id", agentCode);
            if (request.parentTaskPid() != null) {
                row.put("parent_id", request.parentTaskPid());
            }
            row.put("created_at", java.time.LocalDateTime.now());
            row.put("updated_at", java.time.LocalDateTime.now());
            // Carry turn identity so cross-channel mission view can correlate.
            java.util.Map<String, Object> inputData = new java.util.LinkedHashMap<>();
            inputData.put("turnId", ctx.turnId());
            inputData.put("conversationId", ctx.conversationId());
            inputData.put("inboundMessageId", ctx.inboundMessageId());
            inputData.put("agentCode", agentCode);
            inputData.put("channel", request.channel());
            try {
                row.put("input_data", objectMapper.writeValueAsString(inputData));
            } catch (Exception jsonEx) {
                row.put("input_data", "{}");
            }
            dynamicDataMapper.insert("ab_agent_task", row);
            return taskPid;
        } catch (Exception e) {
            throw new IllegalStateException("Named-agent task creation failed: " + safeExceptionMessage(e), e);
        }
    }

    private static String buildNamedAgentTaskTitle(TurnRequest request) {
        String msg = request.userMessage();
        if (msg == null || msg.isBlank()) return "Named-agent turn";
        String trimmed = msg.trim();
        return trimmed.length() > 80 ? trimmed.substring(0, 80) + "..." : trimmed;
    }

    /**
     * DC.3c Fix 3: surface {@code taskPid} on {@code TurnOutcome.Success.meta}
     * so the caller (AgentReplyTask handoff loop) can read it for the next
     * hop's {@code parentTaskPid}. Failed / Interrupted / PendingConfirmation
     * outcomes pass through unchanged — taskPid only needed for handoff
     * recursion which only fires on Success with meta._handoff_to.
     */
    private TurnOutcome attachTaskPidToOutcome(TurnOutcome outcome, String taskPid) {
        if (taskPid == null || !(outcome instanceof TurnOutcome.Success success)) {
            return outcome;
        }
        java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
        if (success.meta() != null) meta.putAll(success.meta());
        meta.putIfAbsent("_taskPid", taskPid);
        return new TurnOutcome.Success(success.finalResponse(), meta);
    }

    private boolean isAcpRuntimeWired() {
        return durableWorkflowEngine != null && durableWorkflowEngine.isAvailable();
    }

    private TurnOutcome acpRuntimeUnavailableOutcome(TurnContext ctx, ResponseSink sink) {
        String msg = "ACP runtime wiring is incomplete for triageBucket=" + ctx.triageBucket()
                + " (durableWorkflowEngine=" + (durableWorkflowEngine != null)
                + ", available=" + (durableWorkflowEngine != null && durableWorkflowEngine.isAvailable()) + ")";
        log.error(msg);
        sink.onError(msg, null);
        return new TurnOutcome.Failed(msg, null);
    }

    /**
     * Delegate ACP_RUN turns to the durable workflow substrate. The
     * chokepoint owns lifecycle, persistence, and audit; ACP task creation
     * and run outcome mapping live behind {@link DurableWorkflowEngine}.
     */
    private TurnOutcome dispatchToAcpRun(TurnContext ctx, ChatRequest legacyRequest, ResponseSink sink) {
        if (!isAcpRuntimeWired()) {
            return acpRuntimeUnavailableOutcome(ctx, sink);
        }
        return durableWorkflowEngine.startConversationRun(ctx, legacyRequest, sink);
    }

    private TurnContext beginTurn(TurnRequest request) {
        String profileId = resolveProfileId(request);
        // Phase C.1: Stage 2.5 Pre-Grounding Triage runs BEFORE persistence so the
        // verdict can be written onto the inbound row + carried in TurnContext.
        // Caller-supplied precomputedBucket (set by webhook / event adapters) wins
        // over the SPI verdict per design — same semantic as channel override
        // in DefaultPreGroundingTriage.
        TriageVerdict verdict = runTriage(request, profileId);
        TriageBucket effectiveBucket = request.precomputedBucket() != null
                ? request.precomputedBucket()
                : (verdict != null ? verdict.bucket() : null);

        // Phase B.1: Persistence.persistInbound takes the TurnRequest directly —
        // TurnContext is not yet built (its inboundMessageId field is exactly
        // what we are about to populate from the persistence return).
        Long inboundMessageId = sideEffects.persistence().persistInbound(request, verdict);
        // GAP-295: resolve the (tenantId, channel, channelUserId, profileId)
        // session row so EffectLifetime.PER_SESSION (and downstream session-
        // scoped state) has a stable scope key. {@code profileId=null} means
        // "tenant default profile" per the SPI contract — not "no profile".
        String channelSessionId = resolveChannelSessionId(request, profileId);
        return new TurnContext(
                com.auraboot.framework.common.util.UniqueIdGenerator.generate(),
                request.tenantId(),
                request.userId(),
                request.humanMemberId(),
                null,                                // agentId — Phase B's AuraBotAgentResolver
                request.agentCode(),                 // DC.3c Fix 2: drives outbound sender_id resolution
                request.channel(),                   // execution-policy channel source
                profileId,                           // resolved user profile id for policy/session scope
                channelSessionId,                    // GAP-295: resolved above
                request.conversationId(),
                inboundMessageId,
                effectiveBucket,
                allowedReadOnlyToolsFor(effectiveBucket, verdict),
                null,                                // traceId — set inside chat impl (kept null on TurnContext for Phase A)
                null,                                // taskPid — chokepoint dispatch later fills via withTaskPid (DC.3c)
                java.time.Instant.now());
    }

    private static Set<String> allowedReadOnlyToolsFor(TriageBucket effectiveBucket, TriageVerdict verdict) {
        if (effectiveBucket != TriageBucket.CONTEXTUAL_ANSWER || verdict == null
                || verdict.allowedReadOnlyTools() == null) {
            return Set.of();
        }
        return verdict.allowedReadOnlyTools();
    }

    /**
     * GAP-295: resolve or create the channel session for this turn's
     * 4-tuple identity. Returns null when the resolver bean is absent (test
     * contexts) or the request lacks the minimum tuple inputs (channel +
     * userId) — leaves {@code TurnContext.channelSessionId=null} which matches
     * pre-GAP-295 Phase B behavior. Failures are logged but never propagated:
     * a turn that can otherwise execute should not be aborted because session
     * accounting was unavailable.
     */
    private String resolveChannelSessionId(TurnRequest request, String profileId) {
        if (channelSessionResolver == null) {
            return null;
        }
        if (request.channel() == null || request.channel().isBlank()) {
            log.debug("GAP-295: skipping channel session resolution — channel is blank");
            return null;
        }
        try {
            ChannelSessionResolver.ChannelSession session = channelSessionResolver.resolve(
                    new ChannelSessionResolver.ResolveRequest(
                            request.tenantId(),
                            request.channel(),
                            String.valueOf(request.userId()),
                            profileId,
                            request.userId(),              // acpUserId
                            /*createIfAbsent=*/ true));
            return session != null ? session.pid() : null;
        } catch (Exception e) {
            log.warn("GAP-295: channel session resolve failed for channel={} userId={}: {}",
                    request.channel(), request.userId(), e.getMessage());
            return null;
        }
    }

    private String resolveProfileId(TurnRequest request) {
        if (agentUserProfileResolver == null || request == null) {
            return null;
        }
        try {
            return agentUserProfileResolver.resolveProfileId(request.tenantId(), request.userId())
                    .orElse(null);
        } catch (RuntimeException e) {
            log.warn("Agent user profile resolve failed for tenant={} userId={}: {}",
                    request.tenantId(), request.userId(), e.getMessage());
            return null;
        }
    }

    /**
     * Phase C.1: invoke the Pre-Grounding Triage SPI. Fail closed to ACP_RUN
     * (per the SPI contract: a classifier failure must choose ACP_RUN, never
     * LIGHT_CHAT) so a misbehaving classifier cannot accidentally route a
     * platform-action turn to the no-platform light path.
     *
     * @return the verdict, or null when the SPI bean is absent (preserves
     *         pre-C.1 behavior — no triage_bucket column write, TurnContext
     *         falls back to caller-supplied precomputedBucket)
     */
    private TriageVerdict runTriage(TurnRequest request, String profileId) {
        if (preGroundingTriage == null) {
            return null;
        }
        TriageRequest tr = new TriageRequest(
                request.tenantId(),
                request.userId(),
                request.channel(),
                profileId,
                request.userMessage(),
                request.pageContext() != null && !request.pageContext().isEmpty(),
                hasRecordContext(request)
        );
        try {
            return preGroundingTriage.triage(tr);
        } catch (Exception e) {
            log.warn("PreGroundingTriage threw; failing closed (channel={}): {}",
                    request.channel(), e.getMessage());
            return triageFailureFallback(request.channel());
        }
    }

    /**
     * R2 review §6-3 (2026-07-19): channel-sensitive triage failure fallback.
     * System channels keep ACP_RUN — trusted automation belongs on the durable
     * path anyway. Human channels fall back to a READ-ONLY contextual chat turn
     * instead: ACP_RUN is the heavier, MORE capable runtime (not a safe default
     * for an unclassifiable human message), and on deployments without ACP
     * wiring it surfaces as a user-visible failure. The read-only grant is
     * enforced at the tool envelope (G10 cap), so the degraded turn can read
     * but never write. Never LIGHT_CHAT.
     */
    static TriageVerdict triageFailureFallback(String channel) {
        if (channel != null && PreGroundingTriage.SYSTEM_CHANNELS.contains(channel.toLowerCase())) {
            return new TriageVerdict(
                    TriageBucket.ACP_RUN,
                    0.0,
                    java.util.List.of("triage_exception"),
                    java.util.Set.of());
        }
        return new TriageVerdict(
                TriageBucket.CONTEXTUAL_ANSWER,
                0.0,
                java.util.List.of("triage_exception_readonly_fallback"),
                PreGroundingTriage.READONLY_CONTEXT_TOOLS);
    }

    /** {@code request.legacyRequest()} carries a {@code recordPid} on
     *  {@code ChatRequest.PageContext} — when present we have record context. */
    private static boolean hasRecordContext(TurnRequest request) {
        ChatRequest legacy = request.legacyRequest();
        if (legacy == null || legacy.getPageContext() == null) {
            return false;
        }
        String recordPid = legacy.getPageContext().getRecordPid();
        return recordPid != null && !recordPid.isBlank();
    }

    private void finalizeTurn(TurnContext ctx, TurnOutcome outcome) {
        finalizeTurn(ctx, outcome, TurnArtifacts.EMPTY);
    }

    private void finalizeTurn(TurnContext ctx, TurnOutcome outcome, TurnArtifacts artifacts) {
        finalizeTurn(ctx, outcome, artifacts, null);
    }

    /** {@code route} is the planner snapshot for this turn (G1/G8 observation
     *  seam); null on resume paths where no fresh planner decision exists. */
    private void finalizeTurn(TurnContext ctx, TurnOutcome outcome, TurnArtifacts artifacts, TurnRoute route) {
        TurnArtifacts effective = artifacts != null ? artifacts : TurnArtifacts.EMPTY;
        switch (outcome) {
            case TurnOutcome.Success s -> {
                sideEffects.persistence().persistOutbound(ctx, s, effective);
                sideEffects.eventEmitter().emit(new TurnCompletedEvent(ctx, s, route));
            }
            case TurnOutcome.Interrupted i -> {
                sideEffects.persistence().persistOutbound(ctx, i, effective);
                sideEffects.eventEmitter().emit(new TurnCompletedEvent(ctx, i, route));
            }
            case TurnOutcome.Failed f -> {
                sideEffects.auditWriter().writeFailure(ctx, f);
                sideEffects.persistence().persistOutbound(ctx, f, effective);
                sideEffects.eventEmitter().emit(new TurnCompletedEvent(ctx, f, route));
            }
            case TurnOutcome.PendingConfirmation pc -> {
                // suspendTurn semantics (P1.4 fix): only persist outbound when there is a
                // partial response worth keeping; otherwise skip persistence and just emit
                // the suspension event. Phase B additionally stores pending
                // payloads through PendingToolStore.
                // the pending tool payload keyed by ctx.turnId().
                if (pc.partialResponse() != null && !pc.partialResponse().isBlank()) {
                    sideEffects.persistence().persistOutbound(ctx, pc, effective);
                }
                sideEffects.eventEmitter().emit(new TurnSuspendedEvent(ctx, pc));
            }
        }
        // DC.3c Fix 3: close ab_agent_task by outcome type when this turn
        // owns one (named-agent dispatch path created it; aurabot main path
        // does not — ctx.taskPid() stays null there).
        closeNamedAgentTask(ctx, outcome);
        sideEffects.metricsRecorder().recordTurnEnd(ctx, outcome);
    }

    /**
     * P-006: {@code finalizeTurn} side effects must never block the outcome from
     * being returned to the caller — the response SSE was already delivered and
     * we do NOT roll it back. But the previous behaviour merely {@code log.warn}-ed
     * the swallowed exception: a {@code persistOutbound} failure (e.g. a DB
     * constraint) then left a turn whose Success response reached the UI yet has
     * NO {@code ab_im_message} row, with no signal to reconcile "sent but not
     * persisted". This adds a visible failure surface — a dedicated metric
     * counter plus an audit failure record — WITHOUT changing the
     * response-is-not-rolled-back policy. Never throws: it is invoked from a
     * catch whose whole purpose is to swallow, so metric/audit emission is itself
     * guarded.
     */
    private void recordFinalizeFailure(TurnContext ctx, TurnOutcome outcome, Exception e) {
        log.warn("finalizeTurn threw, swallowing (response already delivered, NOT rolled back): {}",
                e.getMessage(), e);
        try {
            sideEffects.metricsRecorder().recordOutboundPersistFailure(ctx);
        } catch (Exception metricEx) {
            log.warn("finalizeTurn failure metric emit failed: {}", metricEx.getMessage());
        }
        try {
            String outcomeType = outcome != null ? outcome.getClass().getSimpleName() : "unknown";
            sideEffects.auditWriter().writeFailure(ctx, new TurnOutcome.Failed(
                    "finalizeTurn side-effect failure after " + outcomeType
                            + " outcome (response already delivered, not rolled back): "
                            + safeExceptionMessage(e),
                    e));
        } catch (Exception auditEx) {
            log.warn("finalizeTurn failure audit write failed: {}", auditEx.getMessage());
        }
    }

    /**
     * DC.3c Fix 3: close the {@code ab_agent_task} row that {@link #createNamedAgentTask}
     * opened, with status driven by outcome type:
     * <ul>
     *   <li>{@link TurnOutcome.Success} with {@code meta._handoff_to} → completed
     *       (reason: {@code handoff_to:<targetCode>}) — the upstream hop finishes
     *       by delegating; child task is the next hop's responsibility.</li>
     *   <li>{@link TurnOutcome.Success} otherwise → completed.</li>
     *   <li>{@link TurnOutcome.Interrupted} → completed (terminal but not a failure).</li>
     *   <li>{@link TurnOutcome.Failed} → failed with errorMessage.</li>
     *   <li>{@link TurnOutcome.PendingConfirmation} → status remains {@code in_progress};
     *       resume path will close it.</li>
     * </ul>
     */
    private void closeNamedAgentTask(TurnContext ctx, TurnOutcome outcome) {
        String taskPid = namedAgentTaskPid(ctx, outcome);
        if (dynamicDataMapper == null || taskPid == null) {
            return;
        }
        try {
            java.util.Map<String, Object> updates = new java.util.HashMap<>();
            updates.put("updated_at", java.time.LocalDateTime.now());
            switch (outcome) {
                case TurnOutcome.Success s -> {
                    updates.put("task_status", "completed");
                    if (s.meta() != null && s.meta().get("_handoff_to") != null) {
                        updates.put("error_message", "handoff_to:" + s.meta().get("_handoff_to"));
                    }
                }
                case TurnOutcome.Interrupted i -> {
                    updates.put("task_status", "completed");
                    if (i.reason() != null) {
                        updates.put("error_message", "interrupted:" + i.reason());
                    }
                }
                case TurnOutcome.Failed f -> {
                    updates.put("task_status", "failed");
                    updates.put("error_message",
                            f.errorMessage() != null ? f.errorMessage() : "unknown_error");
                }
                case TurnOutcome.PendingConfirmation pc -> {
                    // Leave in_progress; resume path closes it.
                    return;
                }
            }
            dynamicDataMapper.update("ab_agent_task", updates,
                    java.util.Map.of("pid", taskPid));
        } catch (Exception e) {
            String msg = "Named-agent task close failed: " + safeExceptionMessage(e);
            log.warn("closeNamedAgentTask failed for taskPid={}: {}", taskPid, safeExceptionMessage(e));
            try {
                sideEffects.auditWriter().writeFailure(ctx, new TurnOutcome.Failed(msg, e));
            } catch (Exception auditEx) {
                log.warn("closeNamedAgentTask audit write failed for taskPid={}: {}",
                        taskPid, safeExceptionMessage(auditEx));
            }
        }
    }

    private String namedAgentTaskPid(TurnContext ctx, TurnOutcome outcome) {
        if (ctx != null && ctx.taskPid() != null) {
            return ctx.taskPid();
        }
        if (outcome instanceof TurnOutcome.Success success && success.meta() != null) {
            Object taskPid = success.meta().get("_taskPid");
            if (taskPid instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
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
}
