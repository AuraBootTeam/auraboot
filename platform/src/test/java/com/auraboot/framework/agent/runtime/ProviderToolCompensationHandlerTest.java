package com.auraboot.framework.agent.runtime;

import com.auraboot.framework.agent.authorization.EffectClass;
import com.auraboot.framework.agent.provider.ProviderExecutionResult;
import com.auraboot.framework.agent.provider.ToolProviderRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ProviderToolCompensationHandler")
class ProviderToolCompensationHandlerTest {

    private final ToolProviderRegistry registry = mock(ToolProviderRegistry.class);
    private final ProviderToolCompensationHandler handler =
            new ProviderToolCompensationHandler(registry, new ObjectMapper());

    @Test
    @DisplayName("supports records with explicit compensation tool metadata")
    void supportsRecordsWithExplicitCompensationToolMetadata() {
        DurableToolExecutionRecord record = record(Map.of(
                "ticketId", "T-100",
                "compensationToolRef", "custom:reopen_ticket",
                "compensationArgs", Map.of("ticketId", "T-100")));

        assertThat(handler.supports(record)).isTrue();
    }

    @Test
    @DisplayName("compensate routes to the configured provider compensation tool")
    void compensateRoutesToConfiguredProviderCompensationTool() throws Exception {
        DurableToolExecutionRecord record = record(Map.of(
                "ticketId", "T-100",
                "compensationToolRef", "custom:reopen_ticket",
                "compensationArgs", Map.of("ticketId", "T-100", "reason", "agent compensation")));
        when(registry.execute(7L, "custom:reopen_ticket",
                Map.of("ticketId", "T-100", "reason", "agent compensation")))
                .thenReturn(ProviderExecutionResult.builder()
                        .success(true)
                        .data(Map.of("reopened", true))
                        .durationMs(12)
                        .build());

        DurableToolCompensationResult result = handler.compensate(record);

        assertThat(result.compensated()).isTrue();
        assertThat(new ObjectMapper().readValue(result.rawResult(), Map.class))
                .containsEntry("success", true)
                .containsEntry("provider", "custom:reopen_ticket");
        verify(registry).execute(7L, "custom:reopen_ticket",
                Map.of("ticketId", "T-100", "reason", "agent compensation"));
    }

    @Test
    @DisplayName("compensate accepts nested business compensation spec and propagates idempotency key")
    void compensateAcceptsNestedBusinessCompensationSpec() throws Exception {
        DurableToolExecutionRecord record = record(Map.of(
                "ticketId", "T-100",
                "compensation", Map.of(
                        "toolRef", "custom:reopen_ticket",
                        "args", Map.of("ticketId", "T-100"),
                        "idempotencyKey", "comp-run-1")));
        when(registry.execute(7L, "custom:reopen_ticket",
                Map.of("ticketId", "T-100", "idempotencyKey", "comp-run-1")))
                .thenReturn(ProviderExecutionResult.builder()
                        .success(true)
                        .data(Map.of("reopened", true))
                        .durationMs(12)
                        .build());

        DurableToolCompensationResult result = handler.compensate(record);

        assertThat(result.compensated()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = new ObjectMapper().readValue(result.rawResult(), Map.class);
        assertThat(payload)
                .containsEntry("success", true)
                .containsEntry("provider", "custom:reopen_ticket")
                .containsEntry("executionKey", record.executionKey())
                .containsEntry("idempotencyKey", "comp-run-1");
        verify(registry).execute(7L, "custom:reopen_ticket",
                Map.of("ticketId", "T-100", "idempotencyKey", "comp-run-1"));
    }

    @Test
    @DisplayName("failed provider compensation keeps the record compensation-required")
    void failedProviderCompensationKeepsRecordRequired() {
        DurableToolExecutionRecord record = record(Map.of(
                "compensation_tool_ref", "custom:reopen_ticket",
                "compensation_args", Map.of("ticketId", "T-100")));
        when(registry.execute(7L, "custom:reopen_ticket", Map.of("ticketId", "T-100")))
                .thenReturn(ProviderExecutionResult.builder()
                        .success(false)
                        .errorMessage("ticket not found")
                        .build());

        DurableToolCompensationResult result = handler.compensate(record);

        assertThat(result.compensated()).isFalse();
        assertThat(result.message()).contains("ticket not found");
    }

    private DurableToolExecutionRecord record(Map<String, Object> input) {
        DurableToolExecutionRequest request = new DurableToolExecutionRequest(
                7L,
                "run-1",
                "task-1",
                "aurabot",
                "custom:close_ticket",
                "custom:close_ticket",
                "args-hash",
                "custom",
                Set.of(EffectClass.EXTERNAL_NETWORK),
                input);
        return DurableToolExecutionRecord
                .failed(request.executionKey(), "{\"success\":false}", Map.of("success", false), "timeout")
                .withRecovery(request, 3, 3, 0L, false, "not retryable")
                .compensationRequired("not retryable");
    }
}
