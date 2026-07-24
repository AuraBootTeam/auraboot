package com.auraboot.framework.agent.runtime;

import com.auraboot.framework.agent.authorization.EffectClass;
import com.auraboot.framework.agent.metrics.DurableToolCompensationMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DurableToolCompensationService")
class DurableToolCompensationServiceTest {

    @Mock private DurableToolExecutionLedger ledger;
    @Mock private DurableToolCompensationHandler handler;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final DurableToolCompensationMetrics metrics = new DurableToolCompensationMetrics(registry);

    @Test
    @DisplayName("processDue invokes a matching handler and stores compensated terminal state")
    void processDueInvokesMatchingHandlerAndStoresCompensatedState() {
        DurableToolExecutionRecord record = compensationRequiredRecord();
        when(ledger.findCompensationRequired(50)).thenReturn(List.of(record));
        when(handler.supports(record)).thenReturn(true);
        when(handler.compensate(record)).thenReturn(new DurableToolCompensationResult(
                true,
                "{\"compensated\":true}",
                "rolled back"));
        DurableToolCompensationService service = new DurableToolCompensationService(ledger, List.of(handler), metrics);

        int processed = service.processDue();

        assertThat(processed).isEqualTo(1);
        verify(ledger).markCompensated(record, "{\"compensated\":true}");
        assertThat(outcomeCount(DurableToolCompensationMetrics.OUTCOME_COMPENSATED)).isEqualTo(1.0);
        assertThat(outcomeCount(DurableToolCompensationMetrics.OUTCOME_PENDING_NO_HANDLER)).isZero();
    }

    @Test
    @DisplayName("processDue leaves records pending when no handler supports them")
    void processDueLeavesRecordsPendingWithoutHandler() {
        DurableToolExecutionRecord record = compensationRequiredRecord();
        when(ledger.findCompensationRequired(50)).thenReturn(List.of(record));
        when(handler.supports(record)).thenReturn(false);
        DurableToolCompensationService service = new DurableToolCompensationService(ledger, List.of(handler), metrics);

        int processed = service.processDue();

        assertThat(processed).isZero();
        verify(ledger, never()).markCompensated(eq(record), contains("compensated"));
        verify(ledger, never()).markCompensationRequired(eq(record), contains("compensation"));
        // The "a domain still needs a handler" signal is now an alertable metric, not just a log.
        assertThat(outcomeCount(DurableToolCompensationMetrics.OUTCOME_PENDING_NO_HANDLER)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("processDue keeps compensation-required state with the latest failure reason")
    void processDueKeepsCompensationRequiredStateOnHandlerFailure() {
        DurableToolExecutionRecord record = compensationRequiredRecord();
        when(ledger.findCompensationRequired(50)).thenReturn(List.of(record));
        when(handler.supports(record)).thenReturn(true);
        when(handler.compensate(record)).thenThrow(new RuntimeException("undo failed"));
        DurableToolCompensationService service = new DurableToolCompensationService(ledger, List.of(handler), metrics);

        int processed = service.processDue();

        assertThat(processed).isEqualTo(1);
        verify(ledger).markCompensationRequired(eq(record), contains("undo failed"));
        assertThat(outcomeCount(DurableToolCompensationMetrics.OUTCOME_FAILED)).isEqualTo(1.0);
    }

    /** Counter value for a given outcome tag, or 0.0 if the meter was never registered. */
    private double outcomeCount(String outcome) {
        Counter counter = registry.find(DurableToolCompensationMetrics.OUTCOME_NAME)
                .tag("outcome", outcome)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    private DurableToolExecutionRecord compensationRequiredRecord() {
        DurableToolExecutionRequest request = new DurableToolExecutionRequest(
                1L,
                "run-1",
                "task-1",
                "agent",
                "custom:close_ticket",
                "custom:close_ticket",
                "args-hash",
                "custom",
                Set.of(EffectClass.EXTERNAL_NETWORK, EffectClass.WRITE_PLATFORM_STATE),
                Map.of("ticketId", "T-100"));
        return DurableToolExecutionRecord
                .failed(request.executionKey(), "{\"success\":false}", Map.of("success", false), "timeout")
                .withRecovery(request, 3, 3, 0L, false, "not retryable")
                .compensationRequired("not retryable");
    }
}
