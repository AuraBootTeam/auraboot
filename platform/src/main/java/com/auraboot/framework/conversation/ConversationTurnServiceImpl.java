package com.auraboot.framework.conversation;

import com.auraboot.framework.agent.port.AgentChatPort;
import com.auraboot.framework.agent.triage.PreGroundingTriage;
import com.auraboot.framework.agent.triage.TriageBucket;
import com.auraboot.framework.agent.triage.TriageRequest;
import com.auraboot.framework.agent.triage.TriageVerdict;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.aurabot.dto.ChatRequest;
import com.auraboot.framework.aurabot.service.AuraBotChatService;
import com.auraboot.framework.aurabot.service.ChatSessionStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

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
                outcome = chatService.executeAuraBotTurn(ctx, legacyRequest, sink);
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

        // 1. Look up the pending state. ChatSessionStore is keyed solely by
        //    turnId since the chat impl breaks out of the inner tool-loop the
        //    moment one write tool is queued for confirmation — so at most one
        //    pending entry exists per turnId at any given time.
        ChatSessionStore.PendingTool pending = chatSessionStore.consumePending(pendingTurnId);
        if (pending == null) {
            String msg = "No pending tool found for pendingTurnId=" + pendingTurnId
                    + " (expired or already consumed)";
            log.warn(msg);
            sink.onError(msg, null);
            return new TurnOutcome.Failed(msg, null);
        }

        // 2. Identity validation: the caller must own the suspended turn.
        TurnOutcome identityFailure = validateIdentity(pending);
        if (identityFailure != null) {
            sink.onError(((TurnOutcome.Failed) identityFailure).errorMessage(), null);
            return identityFailure;
        }

        // 3. Rebuild TurnContext from the saved pending state.
        TurnContext ctx = rebuildContext(pending);
        sideEffects.metricsRecorder().recordTurnBegin(ctx);

        // 4. Dispatch by decision.
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

        // 5. Finalize — same dispatch as runTurn so persistence/event/audit/metrics
        //    all fire on the resume path identically.
        try {
            finalizeTurn(ctx, outcome);
        } catch (Exception e) {
            log.warn("resumeTurn finalizeTurn threw, swallowing: {}", e.getMessage(), e);
        }
        return outcome;
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
