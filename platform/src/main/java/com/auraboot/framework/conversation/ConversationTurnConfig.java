package com.auraboot.framework.conversation;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for {@link ConversationTurnService} side-effect bundle.
 *
 * <p>Phase A.6 (initial): all side effects NOOP except Micrometer metrics
 * (counters {@code aurabot.turn.begin} / {@code aurabot.turn.end} tagged
 * {@code phase=A}).
 *
 * <p>Phase B.1 (2026-04-27): {@link TurnSideEffects.Persistence} switched to
 * the real {@link AuraBotTurnPersistence} implementation that writes both
 * inbound (sender_type=human) and outbound (sender_type=agent) rows into
 * {@code ab_im_message}. {@link TurnSideEffects.EventEmitter} and
 * {@link TurnSideEffects.AuditWriter} stay NOOP for now — Phase B.2 / B.3
 * wire those next.
 */
@Configuration
public class ConversationTurnConfig {

    @Bean
    public TurnSideEffects.MetricsRecorder turnMetricsRecorder(MeterRegistry registry) {
        Counter beginCounter = Counter.builder("aurabot.turn.begin")
                .tag("phase", "A")
                .description("ConversationTurnService.runTurn entered (begin)")
                .register(registry);
        Counter endCounter = Counter.builder("aurabot.turn.end")
                .tag("phase", "A")
                .description("ConversationTurnService.runTurn finalized (end / suspend)")
                .register(registry);
        return new TurnSideEffects.MetricsRecorder() {
            @Override
            public void recordTurnBegin(TurnContext ctx) {
                beginCounter.increment();
            }

            @Override
            public void recordTurnEnd(TurnContext ctx, TurnOutcome outcome) {
                endCounter.increment();
            }
        };
    }

    @Bean(name = "turnSideEffects")
    public TurnSideEffects turnSideEffects(TurnSideEffects.MetricsRecorder metricsRecorder,
                                             TurnSideEffects.Persistence persistence) {
        // Phase B.1: persistence is real (AuraBotTurnPersistence); event + audit
        // remain NOOP until B.2 / B.3 swap them in.
        return new TurnSideEffects() {
            @Override public Persistence persistence() { return persistence; }
            @Override public EventEmitter eventEmitter() { return EventEmitter.NOOP; }
            @Override public AuditWriter auditWriter() { return AuditWriter.NOOP; }
            @Override public MetricsRecorder metricsRecorder() { return metricsRecorder; }
        };
    }
}
