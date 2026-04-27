package com.auraboot.framework.conversation;

import com.auraboot.framework.aurabot.dto.ChatRequest;
import com.auraboot.framework.aurabot.service.AuraBotChatService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Phase A.4 implementation of {@link ConversationTurnService}. Synchronous core
 * per Q-A.4=A': async only at controller/adapter boundary; the lifecycle
 * {@code begin -> execute -> end/suspend} is sync internal so {@link TurnOutcome}
 * propagates faithfully from the chat impl up to the controller.
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
            outcome = chatService.executeAuraBotTurn(ctx, legacyRequest, sink);
            if (outcome == null) {
                // Defensive: executeAuraBotTurn always returns non-null, but if a future
                // refactor drops a return path we surface it as Failed rather than NPE later.
                String msg = "executeAuraBotTurn returned null outcome";
                log.error(msg);
                outcome = new TurnOutcome.Failed(msg, null);
            }
        } catch (Exception e) {
            log.error("runTurn caught executeAuraBotTurn exception: {}", e.getMessage(), e);
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

    private TurnContext beginTurn(TurnRequest request) {
        // Phase A: persistence is NOOP, returns null inboundMessageId.
        Long inboundMessageId = sideEffects.persistence()
                .persistInbound(null, request.userMessage(), request.clientMsgId());
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
