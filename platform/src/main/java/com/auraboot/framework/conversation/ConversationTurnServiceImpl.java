package com.auraboot.framework.conversation;

import com.auraboot.framework.agent.port.AgentChatPort;
import com.auraboot.framework.aurabot.dto.ChatRequest;
import com.auraboot.framework.aurabot.service.AuraBotChatService;
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

    /** Optional named-agent port. When the bean is absent, named-agent traffic
     *  surfaces a Failed outcome through the sink — same observability surface
     *  as any other failure path, no silent fallback. */
    @Autowired(required = false)
    private AgentChatPort agentChatPort;

    public ConversationTurnServiceImpl(AuraBotChatService chatService,
                                        @Qualifier("turnSideEffects") TurnSideEffects sideEffects) {
        this.chatService = chatService;
        this.sideEffects = sideEffects;
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
        // Phase B B.6 wires this to AuraBotChatService.doResumeAfterConfirmationSinkAware.
        // Until then, /execute endpoint stays on the legacy resumeAfterConfirmation entry.
        throw new UnsupportedOperationException(
                "resumeTurn is wired in Phase B B.6 — /execute endpoint still uses legacy entry in Phase A");
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
        // Phase B.1: Persistence.persistInbound takes the TurnRequest directly —
        // TurnContext is not yet built (its inboundMessageId field is exactly
        // what we are about to populate from the persistence return).
        Long inboundMessageId = sideEffects.persistence().persistInbound(request);
        return new TurnContext(
                com.auraboot.framework.common.util.UniqueIdGenerator.generate(),
                request.tenantId(),
                request.userId(),
                request.humanMemberId(),
                null,                                // agentId — Phase B's AuraBotAgentResolver
                null,                                // channelSessionId — Phase B's ChannelSessionResolver
                request.conversationId(),
                inboundMessageId,
                request.precomputedBucket(),
                null,                                // traceId — set inside chat impl (kept null on TurnContext for Phase A)
                java.time.Instant.now());
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
