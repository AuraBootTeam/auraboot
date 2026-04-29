package com.auraboot.framework.conversation;

import com.auraboot.framework.agent.port.AgentChatPort;
import com.auraboot.framework.agent.service.ActiveMemoryService;
import com.auraboot.framework.agent.service.AgentApprovalGateService;
import com.auraboot.framework.agent.service.AgentRunService;
import com.auraboot.framework.agent.service.RunOutcome;
import com.auraboot.framework.agent.triage.PreGroundingTriage;
import com.auraboot.framework.agent.triage.TriageBucket;
import com.auraboot.framework.agent.triage.TriageRequest;
import com.auraboot.framework.agent.triage.TriageVerdict;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.aurabot.dto.ChatRequest;
import com.auraboot.framework.aurabot.service.AuraBotChatService;
import com.auraboot.framework.aurabot.service.ChatSessionStore;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

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

    private final AuraBotChatService chatService;
    private final TurnSideEffects sideEffects;
    private final ChatSessionStore chatSessionStore;
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
     * Phase C.3c: ACP runtime entry point used when {@link TriageBucket#ACP_RUN}
     * fires. Optional only because some test contexts may construct the
     * chokepoint without the full ACP wiring; in production this bean is
     * always present. When absent, ACP_RUN turns fall back to the legacy
     * aurabot chat path so users are never broken by partial wiring.
     */
    @Autowired(required = false)
    private AgentRunService agentRunService;

    /**
     * Phase C.3c: needed to write the {@code ab_agent_task} row that ACP runs
     * are keyed off (Q-C3.1=A "per-turn task model"). Optional in the same
     * sense as {@link #agentRunService} — mocking-friendly and OSS-safe.
     */
    @Autowired(required = false)
    private DynamicDataMapper dynamicDataMapper;

    /**
     * Phase C.3d (Q-C3.3=α): approval-gate convergence. {@link #resumeTurn}
     * dispatches a resume call to either the legacy {@link ChatSessionStore}
     * pending-tool path or the ACP {@code ab_agent_approval} path; this bean
     * services the latter. Optional so OSS deployments without the ACP
     * runtime keep building.
     */
    @Autowired(required = false)
    private AgentApprovalGateService agentApprovalGateService;

    public ConversationTurnServiceImpl(AuraBotChatService chatService,
                                        @Qualifier("turnSideEffects") TurnSideEffects sideEffects,
                                        ChatSessionStore chatSessionStore,
                                        ObjectMapper objectMapper) {
        this.chatService = chatService;
        this.sideEffects = sideEffects;
        this.chatSessionStore = chatSessionStore;
        this.objectMapper = objectMapper;
    }

    @Override
    public TurnOutcome runTurn(TurnRequest request, ResponseSink sink) {
        TurnContext ctx = beginTurn(request);
        sideEffects.metricsRecorder().recordTurnBegin(ctx);

        TurnOutcome outcome;
        try {
            ChatRequest legacyRequest = request.legacyRequest();
            String agentCode = request.agentCode();
            if (isAuraBotPath(agentCode)) {
                if (shouldDispatchToAcpRuntime(ctx)) {
                    outcome = dispatchToAcpRun(ctx, legacyRequest, sink);
                } else {
                    outcome = chatService.executeAuraBotTurn(ctx, legacyRequest, sink);
                }
            } else {
                outcome = dispatchToNamedAgent(ctx, legacyRequest, sink, agentCode);
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
            outcome = new TurnOutcome.Failed(e.getMessage(), e);
        }

        try {
            finalizeTurn(ctx, outcome);
        } catch (Exception e) {
            // Side effects must never block the outcome from being returned to the caller.
            log.warn("finalizeTurn threw, swallowing: {}", e.getMessage(), e);
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
        // frontend — it can be either (a) a ChatSessionStore.PendingTool key
        // (legacy chat tool-loop suspension, set by AuraBotChatService B.6
        // path) or (b) an ab_agent_approval.pid (new ACP path written by
        // dispatchToAcpRun via the C.3d mapRunToTurnOutcome PendingApproval
        // branch). We try the chat-store first because consumePending atomically
        // removes the entry; if the token is actually an approval pid the
        // store returns null and we fall through to the approval lookup.
        ChatSessionStore.PendingTool pending = chatSessionStore.consumePending(pendingTurnId);
        if (pending != null) {
            return resumeChatPendingTool(pending, decision, sink);
        }

        // Try ACP approval path (only when the gate bean is wired)
        if (agentApprovalGateService != null) {
            Long tenantId = MetaContext.getCurrentTenantId();
            Map<String, Object> approval = tenantId != null
                    ? agentApprovalGateService.findApproval(tenantId, pendingTurnId)
                    : null;
            if (approval != null) {
                return resumeAcpApproval(approval, decision, sink);
            }
        }

        String msg = "No pending tool or approval found for pendingTurnId=" + pendingTurnId
                + " (expired or already consumed)";
        log.warn(msg);
        sink.onError(msg, null);
        return new TurnOutcome.Failed(msg, null);
    }

    /** Legacy chat tool-loop suspension resume (Phase B.6 path). Behaviour
     *  is unchanged from pre-C.3d. */
    private TurnOutcome resumeChatPendingTool(ChatSessionStore.PendingTool pending,
                                               ConfirmDecision decision, ResponseSink sink) {
        // 1. Identity validation: the caller must own the suspended turn.
        TurnOutcome identityFailure = validateIdentity(pending);
        if (identityFailure != null) {
            sink.onError(((TurnOutcome.Failed) identityFailure).errorMessage(), null);
            return identityFailure;
        }

        // 2. Rebuild TurnContext from the saved pending state.
        TurnContext ctx = rebuildContext(pending);
        sideEffects.metricsRecorder().recordTurnBegin(ctx);

        // 3. Dispatch by decision.
        TurnOutcome outcome;
        try {
            outcome = switch (decision) {
                case APPROVED -> chatService.resumeApprovedTurnFromPending(ctx, pending, sink);
                case DENIED -> {
                    String reason = "User denied the operation";
                    sink.onDone("", null);
                    yield new TurnOutcome.Interrupted(reason, "user_denied");
                }
                case CANCELLED -> {
                    String reason = "User cancelled the operation";
                    sink.onDone("", null);
                    yield new TurnOutcome.Interrupted(reason, "user_cancelled");
                }
            };
            if (outcome == null) {
                String msg = "resumeTurn chat impl returned null outcome";
                log.error(msg);
                outcome = new TurnOutcome.Failed(msg, null);
            }
        } catch (Exception e) {
            log.error("resumeTurn caught chat impl exception: {}", e.getMessage(), e);
            outcome = new TurnOutcome.Failed(e.getMessage(), e);
        }

        try {
            finalizeTurn(ctx, outcome);
        } catch (Exception e) {
            log.warn("resumeTurn finalizeTurn threw, swallowing: {}", e.getMessage(), e);
        }
        return outcome;
    }

    /**
     * Phase C.3d (Q-C3.3=α): ACP approval resume. Drives the approve / reject
     * decision through {@link AgentApprovalGateService} (with the auto-resume
     * @Async dispatch suppressed) and then synchronously calls
     * {@link AgentRunService#executeTaskSync} with {@code resumeFromRunPid}
     * so the resulting {@link RunOutcome} streams back through the SSE sink
     * the user is still listening on.
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
                null, null,
                null,                                         // conversationId — not stored on approval row
                null,
                null,                                         // triageBucket — N/A on resume
                null,
                java.time.Instant.now());
        sideEffects.metricsRecorder().recordTurnBegin(ctx);

        TurnOutcome outcome;
        try {
            outcome = switch (decision) {
                case APPROVED -> {
                    Map<String, Object> approved = agentApprovalGateService.approve(
                            tenantId, approvalPid, approverId, /*triggerAutoResume=*/ false);
                    if (approved == null) {
                        String msg = "Approval " + approvalPid + " could not be approved (terminal state)";
                        sink.onError(msg, null);
                        yield new TurnOutcome.Failed(msg, null);
                    }
                    yield syncResumeAcpRun(ctx, taskPid, runPid, sink);
                }
                case DENIED -> {
                    agentApprovalGateService.reject(tenantId, approvalPid, approverId, "User denied the operation");
                    sink.onDone("", null);
                    yield new TurnOutcome.Interrupted("User denied the operation", "user_denied");
                }
                case CANCELLED -> {
                    agentApprovalGateService.reject(tenantId, approvalPid, approverId, "User cancelled the operation");
                    sink.onDone("", null);
                    yield new TurnOutcome.Interrupted("User cancelled the operation", "user_cancelled");
                }
            };
        } catch (Exception e) {
            log.error("resumeAcpApproval failed: {}", e.getMessage(), e);
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            sink.onError(msg, null);
            outcome = new TurnOutcome.Failed(msg, e);
        }

        try {
            finalizeTurn(ctx, outcome);
        } catch (Exception e) {
            log.warn("resumeAcpApproval finalizeTurn threw, swallowing: {}", e.getMessage(), e);
        }
        return outcome;
    }

    /**
     * Drive a synchronous ACP run resume from an approved approval. Mirrors
     * {@link #dispatchToAcpRun}'s ResponseSinkContext binding so any
     * {@code result_contract} events emitted during the resumed step still
     * stream to the same SSE sink.
     */
    private TurnOutcome syncResumeAcpRun(TurnContext ctx, String taskPid, String runPid,
                                          ResponseSink sink) {
        if (agentRunService == null || taskPid == null || runPid == null) {
            String msg = "ACP resume blocked: missing wiring (taskPid=" + taskPid
                    + ", runPid=" + runPid + ", agentRunService=" + (agentRunService != null) + ")";
            log.error(msg);
            sink.onError(msg, null);
            return new TurnOutcome.Failed(msg, null);
        }
        ResponseSinkContext.set(sink);
        try {
            RunOutcome resumeOutcome = agentRunService.executeTaskSync(
                    ctx.tenantId(), taskPid, ActiveMemoryService.DEFAULT_AGENT, runPid);
            return mapRunToTurnOutcome(ctx, resumeOutcome, sink);
        } finally {
            ResponseSinkContext.clear();
        }
    }

    /**
     * Validate the requesting user actually owns the suspended turn. Without
     * this, a malicious client knowing a {@code pendingTurnId} could resume
     * someone else's turn (since pendingTurnId is a public-ish PID echoed back
     * via SSE, an attacker who guesses or sniffs one should not be able to
     * execute the pending tool).
     */
    private TurnOutcome validateIdentity(ChatSessionStore.PendingTool pending) {
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

    private TurnContext rebuildContext(ChatSessionStore.PendingTool pending) {
        return new TurnContext(
                pending.getTurnId(),
                pending.getTenantId(),
                pending.getUserId(),
                pending.getHumanMemberId(),
                null,                                  // agentId — Phase B/B+ AuraBotAgentResolver
                null,                                  // channelSessionId — Phase B+ ChannelSessionResolver
                pending.getConversationId(),
                null,                                  // inboundMessageId — already persisted at suspend time
                null,                                  // triageBucket
                null,                                  // traceId — chat impl re-attaches via aiTraceService.findActiveTrace
                java.time.Instant.now());
    }

    /**
     * The aurabot main path covers explicit {@code "aurabot"} as well as null /
     * blank agentCode (default fallthrough — frontend sends agentCode only when
     * the user explicitly selected a named agent).
     */
    private static boolean isAuraBotPath(String agentCode) {
        return agentCode == null || agentCode.isBlank() || "aurabot".equals(agentCode);
    }

    /**
     * Phase B.0: named-agent dispatch. The {@link AgentChatPort} bean is optional
     * (the OSS distribution may not include the ACP runtime), so handle absence
     * + agent-not-found symmetrically through the sink + Failed outcome rather
     * than throwing or silently falling through to aurabot.
     */
    private TurnOutcome dispatchToNamedAgent(TurnContext ctx, ChatRequest legacyRequest,
                                              ResponseSink sink, String agentCode) {
        if (agentChatPort == null) {
            String msg = "Named agent requested (agentCode=" + agentCode + ") but AgentChatPort " +
                    "is not available in the current runtime.";
            log.warn(msg);
            sink.onError(msg, null);
            return new TurnOutcome.Failed(msg, null);
        }
        if (!agentChatPort.agentExists(ctx.tenantId(), agentCode)) {
            String msg = "Agent not found or inactive: " + agentCode;
            sink.onError(msg, null);
            return new TurnOutcome.Failed(msg, null);
        }
        log.info("Chat request delegated to named agent: agentCode={}, tenantId={}, turnId={}",
                agentCode, ctx.tenantId(), ctx.turnId());
        return agentChatPort.runAgentTurn(ctx, legacyRequest, sink);
    }

    /**
     * Phase C.3c (Q-C3.5=β step1): ACP_RUN bucket dispatches to the ACP
     * runtime for the aurabot main path. The cutover is bucket-driven so
     * LIGHT_CHAT / CONTEXTUAL_ANSWER turns continue to flow through the
     * existing chat path — this lets us migrate the action-oriented traffic
     * (the whole point of "use ACP to replace tool loop") incrementally
     * without rewriting prose-streaming turns in the same PR.
     *
     * <p>Falls back to the legacy chat path when:
     * <ul>
     *     <li>{@link #agentRunService} is unbound — partial wiring or test context</li>
     *     <li>{@link #dynamicDataMapper} is unbound — same</li>
     * </ul>
     * Both fall-throughs log at WARN; never silent.
     */
    private boolean shouldDispatchToAcpRuntime(TurnContext ctx) {
        if (ctx.triageBucket() != TriageBucket.ACP_RUN) {
            return false;
        }
        if (agentRunService == null || dynamicDataMapper == null) {
            log.warn("triageBucket=ACP_RUN but ACP runtime wiring is incomplete "
                            + "(agentRunService={}, dynamicDataMapper={}); falling back to chat path",
                    agentRunService != null, dynamicDataMapper != null);
            return false;
        }
        return true;
    }

    /**
     * Per Q-C3.1=A, every ACP_RUN turn creates an {@code ab_agent_task} row
     * with {@code assignee_type='ai'} so the ACP run / step / action /
     * approval rows downstream all attach to the same task pid. The task
     * carries the chat turn's identity in {@code input_data} so the run
     * record can be correlated back to the conversation turn for cross-
     * feature observability (Q-C3.1 rationale).
     *
     * <p>SSE byte note: the chat path emits {@code chunk} text-streaming
     * events as the LLM types. The ACP path is action-oriented — it emits
     * {@code result_contract} per tool call (via {@code ResultContractEmitter}
     * → {@link ResponseSinkContext}) and a single {@code done} event at the
     * end. Frontend rendering still works because both event types are
     * supported, but the typing animation is absent for ACP_RUN turns. This
     * is the intentional UX consequence of routing action verbs through
     * ACP per design §3.6.
     *
     * <p>Approval gate handling: per Q-C3.5=β step1 scope, a
     * {@link RunOutcome.PendingApproval} surfaces here as a
     * {@link TurnOutcome.Failed} with a clear "approval pending" message.
     * The full approve / reject flow over {@code ab_agent_approval} is
     * Phase C.3d (Q-C3.3=α). Wiring it earlier would force a frontend
     * contract change in this PR; deferring keeps C.3c reviewable.
     */
    private TurnOutcome dispatchToAcpRun(TurnContext ctx, ChatRequest legacyRequest, ResponseSink sink) {
        // Bind the sink so any ResultContract emitted from inside the ACP
        // run loop (ToolLoopService -> ResultContractEmitter -> sink) flows
        // out the same SSE stream the chokepoint already started.
        ResponseSinkContext.set(sink);
        try {
            String taskPid = createAcpTaskRow(ctx, legacyRequest);
            log.info("ACP_RUN dispatch: tenantId={}, turnId={}, taskPid={}",
                    ctx.tenantId(), ctx.turnId(), taskPid);

            RunOutcome runOutcome = agentRunService.executeTaskSync(
                    ctx.tenantId(), taskPid, ActiveMemoryService.DEFAULT_AGENT, null);

            return mapRunToTurnOutcome(ctx, runOutcome, sink);
        } catch (Exception e) {
            log.error("dispatchToAcpRun failed: {}", e.getMessage(), e);
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            sink.onError(msg, null);
            return new TurnOutcome.Failed(msg, e);
        } finally {
            ResponseSinkContext.clear();
        }
    }

    /**
     * Insert an {@code ab_agent_task} row carrying the chat turn's identity
     * tuple. Returns the task pid so the caller can hand it to
     * {@code AgentRunService.executeTaskSync}. Never falls back — a DB
     * failure here surfaces as a runtime exception which the caller maps
     * into {@link TurnOutcome.Failed}.
     */
    private String createAcpTaskRow(TurnContext ctx, ChatRequest legacyRequest) {
        String taskPid = UniqueIdGenerator.generate();
        Map<String, Object> task = new HashMap<>();
        task.put("pid", taskPid);
        task.put("tenant_id", ctx.tenantId());
        task.put("title", buildTaskTitle(legacyRequest));
        task.put("description", legacyRequest != null ? legacyRequest.getMessage() : "");
        task.put("task_status", "in_progress");
        task.put("task_priority", "normal");
        task.put("assignee_type", "ai");
        task.put("assignee_id", ActiveMemoryService.DEFAULT_AGENT);
        task.put("created_at", LocalDateTime.now());
        task.put("updated_at", LocalDateTime.now());

        Map<String, Object> inputData = new LinkedHashMap<>();
        inputData.put("turnId", ctx.turnId());
        inputData.put("conversationId", ctx.conversationId());
        inputData.put("inboundMessageId", ctx.inboundMessageId());
        inputData.put("triageBucket", ctx.triageBucket() != null ? ctx.triageBucket().name() : null);
        inputData.put("userMessage", legacyRequest != null ? legacyRequest.getMessage() : null);
        try {
            task.put("input_data", objectMapper.writeValueAsString(inputData));
        } catch (JsonProcessingException ex) {
            // Don't block the run — fall back to a minimal payload. The user
            // message in `description` is the same content the LLM sees.
            task.put("input_data", "{}");
        }

        dynamicDataMapper.insert("ab_agent_task", task);
        return taskPid;
    }

    private static String buildTaskTitle(ChatRequest legacyRequest) {
        if (legacyRequest == null || legacyRequest.getMessage() == null) {
            return "Aurabot turn";
        }
        String msg = legacyRequest.getMessage().trim();
        if (msg.length() > 80) {
            return msg.substring(0, 80) + "...";
        }
        return msg.isEmpty() ? "Aurabot turn" : msg;
    }

    /**
     * Map ACP {@link RunOutcome} → chokepoint {@link TurnOutcome} and emit
     * the corresponding terminal SSE event so the frontend's
     * {@code reader.readChat} loop terminates cleanly.
     */
    private TurnOutcome mapRunToTurnOutcome(TurnContext ctx, RunOutcome ro, ResponseSink sink) {
        return switch (ro) {
            case RunOutcome.Success s -> {
                String response = s.finalResponse() != null ? s.finalResponse() : "";
                sink.onDone(response, null);
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("runPid", s.runPid());
                meta.put("inputTokens", s.inputTokens());
                meta.put("outputTokens", s.outputTokens());
                meta.put("totalCost", s.totalCost());
                yield new TurnOutcome.Success(response, meta);
            }
            case RunOutcome.PendingApproval pa -> {
                // Phase C.3d (Q-C3.3=α): convergence to ACP approval gate.
                // approvalPid identifies the ab_agent_approval row; we surface
                // it on the confirm_required SSE event as the resumption token
                // (replacing pendingTurnId for ACP_RUN turns) and return a
                // PendingConfirmation outcome so finalizeTurn fires the
                // suspension event chain (TurnSuspendedEvent + ChatSessionStore
                // savePending stays unwired for ACP path — the approval row IS
                // the persisted pending state).
                if (pa.approvalPid() == null) {
                    // Pre-C.3d throw site: no approvalPid available — fall back
                    // to Failed so the user is not silently stuck.
                    String msg = "Approval required but no approval pid available "
                            + "(run " + pa.runPid() + "): "
                            + (pa.message() != null ? pa.message() : "<no detail>");
                    sink.onError(msg, null);
                    yield new TurnOutcome.Failed(msg, null);
                }
                sink.onConfirmRequired(
                        pa.approvalPid(),                   // toolId — frontend uses for action correlation
                        "agent_approval_gate",              // toolName — generic ACP gate marker
                        pa.message() != null ? pa.message() : "Approval required",
                        Map.of("runPid", pa.runPid()),
                        pa.approvalPid());                  // pendingTurnId — resumption token
                yield new TurnOutcome.PendingConfirmation(
                        pa.approvalPid(),                   // pendingTurnId in TurnOutcome
                        "",                                 // partialResponse — ACP run does not stream prose
                        pa.approvalPid());                  // pendingToolId
            }
            case RunOutcome.Failed f -> {
                String msg = f.errorMessage() != null ? f.errorMessage() : "ACP run failed";
                sink.onError(msg, null);
                yield new TurnOutcome.Failed(msg, null);
            }
            case RunOutcome.Skipped sk -> {
                // Skipped means a pre-execution gate (agent runtime disabled)
                // fired. Surface as Failed so the user sees a clear message.
                String msg = sk.reason() != null ? sk.reason() : "ACP run skipped";
                sink.onError(msg, null);
                yield new TurnOutcome.Failed(msg, null);
            }
        };
    }

    private TurnContext beginTurn(TurnRequest request) {
        // Phase C.1: Stage 2.5 Pre-Grounding Triage runs BEFORE persistence so the
        // verdict can be written onto the inbound row + carried in TurnContext.
        // Caller-supplied precomputedBucket (set by webhook / event adapters) wins
        // over the SPI verdict per design — same semantic as channel override
        // in DefaultPreGroundingTriage.
        TriageVerdict verdict = runTriage(request);
        TriageBucket effectiveBucket = request.precomputedBucket() != null
                ? request.precomputedBucket()
                : (verdict != null ? verdict.bucket() : null);

        // Phase B.1: Persistence.persistInbound takes the TurnRequest directly —
        // TurnContext is not yet built (its inboundMessageId field is exactly
        // what we are about to populate from the persistence return).
        Long inboundMessageId = sideEffects.persistence().persistInbound(request, verdict);
        return new TurnContext(
                com.auraboot.framework.common.util.UniqueIdGenerator.generate(),
                request.tenantId(),
                request.userId(),
                request.humanMemberId(),
                null,                                // agentId — Phase B's AuraBotAgentResolver
                null,                                // channelSessionId — Phase B's ChannelSessionResolver
                request.conversationId(),
                inboundMessageId,
                effectiveBucket,
                null,                                // traceId — set inside chat impl (kept null on TurnContext for Phase A)
                java.time.Instant.now());
    }

    /**
     * Phase C.1: invoke the Pre-Grounding Triage SPI. Fail-closed to ACP_RUN
     * (per the SPI contract: "Failure must fall back to ACP_RUN, never
     * LIGHT_CHAT") so a misbehaving classifier cannot accidentally route a
     * platform-action turn to the no-platform light path.
     *
     * @return the verdict, or null when the SPI bean is absent (preserves
     *         pre-C.1 behavior — no triage_bucket column write, TurnContext
     *         falls back to caller-supplied precomputedBucket)
     */
    private TriageVerdict runTriage(TurnRequest request) {
        if (preGroundingTriage == null) {
            return null;
        }
        TriageRequest tr = new TriageRequest(
                request.tenantId(),
                request.userId(),
                request.channel(),
                null,                                // profileId — Phase C+ tenant-profile policy hook
                request.userMessage(),
                request.pageContext() != null && !request.pageContext().isEmpty(),
                hasRecordContext(request),
                0                                    // recentLightTurnCount — Phase C+ history-hotness query
        );
        try {
            return preGroundingTriage.triage(tr);
        } catch (Exception e) {
            log.warn("PreGroundingTriage threw, falling back to ACP_RUN: {}", e.getMessage());
            return new TriageVerdict(
                    TriageBucket.ACP_RUN,
                    0.0,
                    java.util.List.of("triage_exception"),
                    java.util.Set.of());
        }
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
        switch (outcome) {
            case TurnOutcome.Success s -> {
                sideEffects.persistence().persistOutbound(ctx, s);
                sideEffects.eventEmitter().emit(new TurnCompletedEvent(ctx, s));
            }
            case TurnOutcome.Interrupted i -> {
                sideEffects.persistence().persistOutbound(ctx, i);
                sideEffects.eventEmitter().emit(new TurnCompletedEvent(ctx, i));
            }
            case TurnOutcome.Failed f -> {
                sideEffects.auditWriter().writeFailure(ctx, f);
                sideEffects.eventEmitter().emit(new TurnCompletedEvent(ctx, f));
            }
            case TurnOutcome.PendingConfirmation pc -> {
                // suspendTurn semantics (P1.4 fix): only persist outbound when there is a
                // partial response worth keeping; otherwise skip persistence and just emit
                // the suspension event. Phase B will additionally chatSessionStore.savePending
                // the pending tool payload keyed by ctx.turnId().
                if (pc.partialResponse() != null && !pc.partialResponse().isBlank()) {
                    sideEffects.persistence().persistOutbound(ctx, pc);
                }
                sideEffects.eventEmitter().emit(new TurnSuspendedEvent(ctx, pc));
            }
        }
        sideEffects.metricsRecorder().recordTurnEnd(ctx, outcome);
    }
}
