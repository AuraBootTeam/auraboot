package com.auraboot.framework.agent.runtime;

import com.auraboot.framework.agent.authorization.EffectClass;
import com.auraboot.framework.agent.provider.ProviderExecutionResult;
import com.auraboot.framework.agent.provider.ToolProviderRegistry;
import com.auraboot.framework.agent.service.ActionRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DurableToolExecutionRecoveryService")
class DurableToolExecutionRecoveryServiceTest {

    @Mock private DurableToolExecutionLedger ledger;
    @Mock private ToolProviderRegistry toolProviderRegistry;
    @Mock private ActionRecorder actionRecorder;

    private DurableToolExecutionRecoveryService service;

    @BeforeEach
    void setUp() {
        service = new DurableToolExecutionRecoveryService(
                ledger,
                toolProviderRegistry,
                new ObjectMapper(),
                actionRecorder);
    }

    @Test
    @DisplayName("processDue retries recoverable provider failures through the provider registry")
    void processDueRetriesRecoverableProviderFailures() {
        DurableToolExecutionRequest request = request(Map.of(
                "ticketId", "T-100",
                "status", "closed",
                "idempotencyKey", "idem-1"));
        DurableToolExecutionRecord record = DurableToolExecutionRecord
                .failed(request.executionKey(), "{\"success\":false}", Map.of("success", false), "timeout")
                .withRecovery(request, 1, 3, 0L, true, null);
        when(ledger.findRecoverable(50)).thenReturn(java.util.List.of(record));
        when(ledger.claimRetry(record)).thenReturn(true);
        when(toolProviderRegistry.execute(eq(1L), eq("custom:close_ticket"), eq(request.input())))
                .thenReturn(ProviderExecutionResult.builder()
                        .success(true)
                        .data(Map.of("success", true, "externalId", "T-100"))
                        .durationMs(12L)
                        .build());

        int processed = service.processDue();

        assertThat(processed).isEqualTo(1);
        verify(toolProviderRegistry).execute(eq(1L), eq("custom:close_ticket"), eq(request.input()));
        verify(ledger).complete(eq(request), eq(request.executionKey()), contains("\"externalId\":\"T-100\""));
        verify(actionRecorder).recordProviderAction(
                eq(1L), eq("run-1"), eq("custom:close_ticket"), any(), eq(request.input()), anyMap(), isNull(),
                eq(Set.of(EffectClass.EXTERNAL_NETWORK, EffectClass.WRITE_PLATFORM_STATE)));
    }

    @Test
    @DisplayName("processDue marks non-retryable records as compensation-required without provider dispatch")
    void processDueMarksNonRetryableRecordsForCompensation() {
        DurableToolExecutionRequest request = request(Map.of("ticketId", "T-100", "status", "closed"));
        DurableToolExecutionRecord record = DurableToolExecutionRecord
                .failed(request.executionKey(), "{\"success\":false}", Map.of("success", false), "timeout")
                .withRecovery(request, 1, 3, 0L, false, null);
        when(ledger.findRecoverable(50)).thenReturn(java.util.List.of(record));

        int processed = service.processDue();

        assertThat(processed).isEqualTo(1);
        verify(toolProviderRegistry, never()).execute(any(), anyString(), anyMap());
        verify(ledger).markCompensationRequired(eq(record), contains("not retryable"));
    }

    @Test
    @DisplayName("processDue skips records when another worker already claimed the retry")
    void processDueSkipsWhenRetryClaimIsLost() {
        DurableToolExecutionRequest request = request(Map.of("idempotencyKey", "idem-1"));
        DurableToolExecutionRecord record = DurableToolExecutionRecord
                .failed(request.executionKey(), "{\"success\":false}", Map.of("success", false), "timeout")
                .withRecovery(request, 1, 3, 0L, true, null);
        when(ledger.findRecoverable(50)).thenReturn(java.util.List.of(record));
        when(ledger.claimRetry(record)).thenReturn(false);

        int processed = service.processDue();

        assertThat(processed).isZero();
        verify(toolProviderRegistry, never()).execute(any(), anyString(), anyMap());
        verify(ledger, never()).complete(any(), anyString(), anyString());
    }

    private DurableToolExecutionRequest request(Map<String, Object> input) {
        return new DurableToolExecutionRequest(
                1L,
                "run-1",
                "task-1",
                "agent",
                "custom:close_ticket",
                "custom:close_ticket",
                "args-hash",
                "custom",
                Set.of(EffectClass.EXTERNAL_NETWORK, EffectClass.WRITE_PLATFORM_STATE),
                input);
    }
}
