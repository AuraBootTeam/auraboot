package com.auraboot.framework.agent.runtime;

import com.auraboot.framework.agent.authorization.EffectClass;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("JdbcDurableToolExecutionLedger")
class JdbcDurableToolExecutionLedgerTest {

    @Mock private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("claim inserts a running durable execution row and returns acquired")
    void claimInsertsRunningRowAndReturnsAcquired() {
        DurableToolExecutionRequest request = request();
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        JdbcDurableToolExecutionLedger ledger = new JdbcDurableToolExecutionLedger(jdbcTemplate, objectMapper);

        DurableToolExecutionClaim claim = ledger.claim(request);

        assertThat(claim.acquired()).isTrue();
        assertThat(claim.executionKey()).startsWith("agent.tool_execution:run-1:custom:close_ticket:");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), any(Object[].class));
        assertThat(sql.getValue())
                .contains("INSERT INTO ab_idempotency_record")
                .contains("ON CONFLICT (tenant_id, client_request_id) DO NOTHING");
    }

    @Test
    @DisplayName("claim replays an existing succeeded execution record")
    void claimReplaysExistingSucceededExecutionRecord() throws Exception {
        DurableToolExecutionRequest request = request();
        DurableToolExecutionRecord completed = DurableToolExecutionRecord.succeeded(
                request.executionKey(),
                "{\"success\":true,\"externalId\":\"T-100\"}",
                Map.of("success", true, "externalId", "T-100"));
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
                "status", "SUCCEEDED",
                "outcome", objectMapper.writeValueAsString(completed)
        )));
        JdbcDurableToolExecutionLedger ledger = new JdbcDurableToolExecutionLedger(jdbcTemplate, objectMapper);

        DurableToolExecutionClaim claim = ledger.claim(request);

        assertThat(claim.acquired()).isFalse();
        assertThat(claim.record().status()).isEqualTo(DurableToolExecutionStatus.SUCCEEDED);
        assertThat(claim.record().rawResult()).contains("externalId");
        assertThat(claim.record().result()).containsEntry("externalId", "T-100");
    }

    @Test
    @DisplayName("complete persists raw terminal result under tenant and execution key")
    void completePersistsRawTerminalResult() {
        DurableToolExecutionRequest request = request();
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        JdbcDurableToolExecutionLedger ledger = new JdbcDurableToolExecutionLedger(jdbcTemplate, objectMapper);

        ledger.complete(request, request.executionKey(), "{\"success\":true}");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), any(Object[].class));
        assertThat(sql.getValue())
                .contains("UPDATE ab_idempotency_record")
                .contains("tenant_id = ?")
                .contains("client_request_id = ?");
    }

    @Test
    @DisplayName("findRecoverable selects due failed retryable durable executions")
    void findRecoverableSelectsDueFailedRetryableRecords() throws Exception {
        DurableToolExecutionRequest request = requestWithInput(Map.of("idempotencyKey", "idem-1"));
        DurableToolExecutionRecord failed = DurableToolExecutionRecord
                .failed(request.executionKey(), "{\"success\":false}", Map.of("success", false), "timeout")
                .withRecovery(request, 1, 3, 0L, true, null);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
                "tenant_id", 1L,
                "client_request_id", request.executionKey(),
                "status", "FAILED",
                "outcome", objectMapper.writeValueAsString(failed)
        )));
        JdbcDurableToolExecutionLedger ledger = new JdbcDurableToolExecutionLedger(jdbcTemplate, objectMapper);

        List<DurableToolExecutionRecord> records = ledger.findRecoverable(10);

        assertThat(records).hasSize(1);
        assertThat(records.get(0).request().toolRef()).isEqualTo("custom:close_ticket");
        assertThat(records.get(0).retryable()).isTrue();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sql.capture(), any(Object[].class));
        assertThat(sql.getValue())
                .contains("command_code LIKE")
                .contains("status = 'FAILED'")
                .contains("nextRetryAt");
    }

    @Test
    @DisplayName("claimRetry moves a failed row to running and increments the attempt")
    void claimRetryMovesFailedRowToRunningAndIncrementsAttempt() {
        DurableToolExecutionRequest request = requestWithInput(Map.of("idempotencyKey", "idem-1"));
        DurableToolExecutionRecord record = DurableToolExecutionRecord
                .failed(request.executionKey(), "{\"success\":false}", Map.of("success", false), "timeout")
                .withRecovery(request, 1, 3, 0L, true, null);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        JdbcDurableToolExecutionLedger ledger = new JdbcDurableToolExecutionLedger(jdbcTemplate, objectMapper);

        boolean claimed = ledger.claimRetry(record);

        assertThat(claimed).isTrue();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), any(Object[].class));
        assertThat(sql.getValue())
                .contains("status = 'RUNNING'")
                .contains("status = 'FAILED'")
                .contains("client_request_id = ?");
    }

    @Test
    @DisplayName("fail marks non-retryable external side effects as compensation-required")
    void failMarksNonRetryableSideEffectsAsCompensationRequired() throws Exception {
        DurableToolExecutionRequest request = requestWithInput(Map.of("ticketId", "T-100"));
        DurableToolExecutionRecord running = DurableToolExecutionRecord.running(request.executionKey(), request);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
                "status", "RUNNING",
                "outcome", objectMapper.writeValueAsString(running)
        )));
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        JdbcDurableToolExecutionLedger ledger = new JdbcDurableToolExecutionLedger(jdbcTemplate, objectMapper);

        ledger.fail(request, request.executionKey(), "{\"success\":false}", "timeout");

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(anyString(), args.capture());
        assertThat(String.valueOf(args.getValue()[0])).contains("COMPENSATION_REQUIRED");
        assertThat(String.valueOf(args.getValue()[0])).contains("not retryable");
    }

    @Test
    @DisplayName("findCompensationRequired selects compensation-required durable executions")
    void findCompensationRequiredSelectsCompensationRequiredRecords() throws Exception {
        DurableToolExecutionRequest request = requestWithInput(Map.of("ticketId", "T-100"));
        DurableToolExecutionRecord record = DurableToolExecutionRecord
                .failed(request.executionKey(), "{\"success\":false}", Map.of("success", false), "timeout")
                .withRecovery(request, 3, 3, 0L, false, "not retryable")
                .compensationRequired("not retryable");
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
                "tenant_id", 1L,
                "client_request_id", request.executionKey(),
                "status", "COMPENSATION_REQUIRED",
                "outcome", objectMapper.writeValueAsString(record)
        )));
        JdbcDurableToolExecutionLedger ledger = new JdbcDurableToolExecutionLedger(jdbcTemplate, objectMapper);

        List<DurableToolExecutionRecord> records = ledger.findCompensationRequired(10);

        assertThat(records).hasSize(1);
        assertThat(records.get(0).status()).isEqualTo(DurableToolExecutionStatus.COMPENSATION_REQUIRED);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sql.capture(), any(Object[].class));
        assertThat(sql.getValue()).contains("status = 'COMPENSATION_REQUIRED'");
    }

    @Test
    @DisplayName("markCompensated persists compensated terminal status")
    void markCompensatedPersistsCompensatedTerminalStatus() {
        DurableToolExecutionRequest request = requestWithInput(Map.of("ticketId", "T-100"));
        DurableToolExecutionRecord record = DurableToolExecutionRecord
                .failed(request.executionKey(), "{\"success\":false}", Map.of("success", false), "timeout")
                .withRecovery(request, 3, 3, 0L, false, "not retryable")
                .compensationRequired("not retryable");
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        JdbcDurableToolExecutionLedger ledger = new JdbcDurableToolExecutionLedger(jdbcTemplate, objectMapper);

        ledger.markCompensated(record, "{\"compensated\":true}");

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(anyString(), args.capture());
        assertThat(String.valueOf(args.getValue()[0])).contains("COMPENSATED");
        assertThat(String.valueOf(args.getValue()[0])).contains("compensated");
    }

    @Test
    @DisplayName("claim fails closed when tenant identity is missing")
    void claimFailsClosedWhenTenantIdentityMissing() {
        JdbcDurableToolExecutionLedger ledger = new JdbcDurableToolExecutionLedger(jdbcTemplate, objectMapper);
        DurableToolExecutionRequest request = new DurableToolExecutionRequest(
                null,
                "run-1",
                "task-1",
                "agent",
                "custom:close_ticket",
                "custom:close_ticket",
                "hash",
                Set.of(EffectClass.EXTERNAL_NETWORK, EffectClass.WRITE_PLATFORM_STATE),
                Map.of());

        assertThatThrownBy(() -> ledger.claim(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId is required");
    }

    private DurableToolExecutionRequest request() {
        return requestWithInput(Map.of("ticketId", "T-100"));
    }

    private DurableToolExecutionRequest requestWithInput(Map<String, Object> input) {
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
