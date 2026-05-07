package com.auraboot.framework.conversation;

/**
 * Bundle of side-effect SPIs the {@link ConversationTurnService} fires during
 * lifecycle. Allowing callers to inject NOOP / partial implementations is what
 * makes Phase A a true 0-behavior-change refactor.
 *
 * <p>Phase A injects {@link #observeOnly} (Q12=N): metrics + trace fire,
 * persistence/event/audit are NOOP. Phase B swaps in {@link Production}
 * (or a feature-flag controlled chain) with real implementations.
 *
 * <p>Trace span emission is intentionally NOT in this bundle: it is owned by
 * {@code AiTraceService} which sits orthogonal to the turn lifecycle.
 */
public interface TurnSideEffects {

    Persistence persistence();

    EventEmitter eventEmitter();

    AuditWriter auditWriter();

    MetricsRecorder metricsRecorder();

    /** Phase A strict: fully NOOP including metrics. Use when Q12=M. */
    TurnSideEffects TRULY_DISABLED = new TurnSideEffects() {
        public Persistence persistence() { return Persistence.NOOP; }
        public EventEmitter eventEmitter() { return EventEmitter.NOOP; }
        public AuditWriter auditWriter() { return AuditWriter.NOOP; }
        public MetricsRecorder metricsRecorder() { return MetricsRecorder.NOOP; }
    };

    /**
     * Phase A default (Q12=N): persistence/event/audit NOOP but metrics keep
     * firing so we can observe whether {@code runTurn} is actually being called
     * from the legacy entry points. Pass the real {@link MetricsRecorder} in.
     */
    static TurnSideEffects observeOnly(MetricsRecorder realMetrics) {
        return new TurnSideEffects() {
            public Persistence persistence() { return Persistence.NOOP; }
            public EventEmitter eventEmitter() { return EventEmitter.NOOP; }
            public AuditWriter auditWriter() { return AuditWriter.NOOP; }
            public MetricsRecorder metricsRecorder() { return realMetrics; }
        };
    }

    /** Persistence of inbound + outbound messages. Phase B implements; Phase A NOOP. */
    interface Persistence {
        /**
         * Persist the inbound (user) message. Phase B.0 signature change: called
         * from {@code beginTurn} BEFORE the {@link TurnContext} is constructed —
         * the returned message id is what populates
         * {@code TurnContext.inboundMessageId}. The {@link TurnRequest} carries
         * every field we have at that moment (tenantId, userId, humanMemberId,
         * conversationId, clientMsgId, userMessage).
         *
         * <p>Phase C.1: triage verdict (Stage 2.5 Pre-Grounding) is propagated
         * here so the persisted row can carry the routing decision (bucket /
         * confidence / reasonCodes). Pass null when no triage SPI is wired —
         * downstream impls treat null as "skip triage column write".
         *
         * @return the persisted message id, or null when persistence skipped /
         *         disabled (NOOP profile or missing required fields)
         */
        Long persistInbound(TurnRequest request,
                             com.auraboot.framework.agent.triage.TriageVerdict triageVerdict);

        Long persistOutbound(TurnContext ctx, TurnOutcome outcome);

        /**
         * D.1 (2026-05-07) overload that carries side-channel turn artifacts
         * (Anthropic Extended Thinking prose + signature, future per-turn
         * usage, etc.). Default impl ignores artifacts and delegates to the
         * 2-arg method so legacy {@link Persistence} implementations keep
         * compiling unchanged. {@link AuraBotTurnPersistence} overrides to
         * actually persist {@code thinking_content} / {@code thinking_signature}
         * onto the {@code ab_im_message} agent row.
         */
        default Long persistOutbound(TurnContext ctx, TurnOutcome outcome, TurnArtifacts artifacts) {
            return persistOutbound(ctx, outcome);
        }

        Persistence NOOP = new Persistence() {
            public Long persistInbound(TurnRequest request,
                                         com.auraboot.framework.agent.triage.TriageVerdict triageVerdict) {
                return null;
            }
            public Long persistOutbound(TurnContext ctx, TurnOutcome o) { return null; }
        };
    }

    /** Spring application event emission. */
    interface EventEmitter {
        void emit(Object event);
        EventEmitter NOOP = event -> {};
    }

    /** Audit log write (for both completed and rejected/failed decisions). */
    interface AuditWriter {
        void writeFailure(TurnContext ctx, TurnOutcome.Failed failed);
        AuditWriter NOOP = (ctx, failed) -> {};
    }

    /** Metrics counters / histograms. Phase A keeps real impl per Q12=N. */
    interface MetricsRecorder {
        void recordTurnBegin(TurnContext ctx);
        void recordTurnEnd(TurnContext ctx, TurnOutcome outcome);

        MetricsRecorder NOOP = new MetricsRecorder() {
            public void recordTurnBegin(TurnContext ctx) {}
            public void recordTurnEnd(TurnContext ctx, TurnOutcome outcome) {}
        };
    }
}
