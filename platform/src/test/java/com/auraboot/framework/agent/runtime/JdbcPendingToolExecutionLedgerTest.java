package com.auraboot.framework.agent.runtime;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("JdbcPendingToolExecutionLedger")
class JdbcPendingToolExecutionLedgerTest {

    @Mock private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("claim inserts a running idempotency row and returns acquired")
    void claimInsertsRunningRowAndReturnsAcquired() {
        PendingToolSnapshot pending = pending();
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        JdbcPendingToolExecutionLedger ledger = new JdbcPendingToolExecutionLedger(jdbcTemplate, objectMapper);

        PendingToolExecutionClaim claim = ledger.claim(pending);

        assertThat(claim.acquired()).isTrue();
        assertThat(claim.record().status()).isEqualTo(PendingToolExecutionStatus.RUNNING);
        assertThat(claim.record().executionKey()).contains("pending-tool:");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), any(Object[].class));
        assertThat(sql.getValue())
                .contains("INSERT INTO ab_idempotency_record")
                .contains("ON CONFLICT (tenant_id, client_request_id) DO NOTHING");
    }

    @Test
    @DisplayName("claim replays an existing completed execution record")
    void claimReplaysExistingCompletedExecutionRecord() throws Exception {
        PendingToolSnapshot pending = pending();
        PendingToolExecutionRecord completed = PendingToolExecutionRecord.succeeded(
                PendingToolStore.executionKey(pending),
                Map.of("pid", "model-1"));
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
                "status", "SUCCEEDED",
                "outcome", objectMapper.writeValueAsString(completed)
        )));
        JdbcPendingToolExecutionLedger ledger = new JdbcPendingToolExecutionLedger(jdbcTemplate, objectMapper);

        PendingToolExecutionClaim claim = ledger.claim(pending);

        assertThat(claim.acquired()).isFalse();
        assertThat(claim.record().status()).isEqualTo(PendingToolExecutionStatus.SUCCEEDED);
        assertThat(claim.record().result()).containsEntry("pid", "model-1");
    }

    @Test
    @DisplayName("complete persists the terminal result under tenant and execution key")
    void completePersistsTerminalResultUnderTenantAndExecutionKey() {
        PendingToolSnapshot pending = pending();
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        JdbcPendingToolExecutionLedger ledger = new JdbcPendingToolExecutionLedger(jdbcTemplate, objectMapper);

        ledger.complete(pending, "execution-1", Map.of("pid", "model-1"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), any(Object[].class));
        assertThat(sql.getValue())
                .contains("UPDATE ab_idempotency_record")
                .contains("tenant_id = ?")
                .contains("client_request_id = ?");
    }

    @Test
    @DisplayName("claim fails closed when tenant identity is missing")
    void claimFailsClosedWhenTenantIdentityMissing() {
        JdbcPendingToolExecutionLedger ledger = new JdbcPendingToolExecutionLedger(jdbcTemplate, objectMapper);
        PendingToolSnapshot pending = pending();
        pending.setTenantId(null);

        assertThatThrownBy(() -> ledger.claim(pending))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId is required");
    }

    private PendingToolSnapshot pending() {
        return PendingToolSnapshot.builder()
                .turnId("turn-1")
                .tenantId(1L)
                .userId(2L)
                .toolId("tool-1")
                .toolName("model:create")
                .toolVersion("v1")
                .argsHash("args-hash")
                .input(Map.of("code", "crm_customer"))
                .build();
    }
}
