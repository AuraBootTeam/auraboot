package com.auraboot.framework.conversation;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Phase A.6 wiring for {@link ConversationTurnService}. Provides:
 * <ul>
 *     <li>{@link TurnSideEffects.MetricsRecorder} backed by Micrometer counters
 *         {@code aurabot.turn.begin} / {@code aurabot.turn.end} tagged with
 *         {@code phase=A} so the dashboard can attribute Phase A traffic.</li>
 *     <li>{@link TurnSideEffects} bean named {@code turnSideEffects} configured
 *         as {@link TurnSideEffects#observeOnly} — persistence/event/audit are
 *         NOOP so behavior matches pre-refactor exactly; only metrics fire.</li>
 * </ul>
 *
 * <p>Phase B replaces this config with one that wires real
 * {@link TurnSideEffects.Persistence} / {@link TurnSideEffects.EventEmitter} /
 * {@link TurnSideEffects.AuditWriter}.
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
    public TurnSideEffects turnSideEffects(TurnSideEffects.MetricsRecorder metricsRecorder) {
        return TurnSideEffects.observeOnly(metricsRecorder);
    }
}
