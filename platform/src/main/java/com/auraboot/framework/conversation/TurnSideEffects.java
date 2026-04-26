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
        Long persistInbound(TurnContext ctx, String userMessage, String clientMsgId);
        Long persistOutbound(TurnContext ctx, TurnOutcome outcome);

        Persistence NOOP = new Persistence() {
            public Long persistInbound(TurnContext ctx, String m, String c) { return null; }
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
