package com.auraboot.framework.agent.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DataChangeLogPendingContextFreshnessValidator")
class DataChangeLogPendingContextFreshnessValidatorTest {

    @Mock private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("missing context metadata is treated as fresh for legacy pending snapshots")
    void missingContextMetadataIsFresh() {
        DataChangeLogPendingContextFreshnessValidator validator =
                new DataChangeLogPendingContextFreshnessValidator(jdbcTemplate);

        PendingContextFreshnessDecision decision = validator.validate(PendingToolSnapshot.builder()
                .tenantId(1L)
                .build());

        assertThat(decision.fresh()).isTrue();
        verify(jdbcTemplate, never()).queryForList(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("matching latest change id keeps the pending snapshot fresh")
    void matchingLatestChangeIdIsFresh() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
                "id", 42L,
                "changed_at", OffsetDateTime.parse("2026-05-20T01:00:00Z")
        )));
        DataChangeLogPendingContextFreshnessValidator validator =
                new DataChangeLogPendingContextFreshnessValidator(jdbcTemplate);

        PendingContextFreshnessDecision decision = validator.validate(PendingToolSnapshot.builder()
                .tenantId(1L)
                .modelCode("crm_customer")
                .recordVersion("change:42")
                .input(Map.of("recordPid", "C-1"))
                .build());

        assertThat(decision.fresh()).isTrue();
    }

    @Test
    @DisplayName("changed latest audit row marks the pending snapshot stale")
    void changedLatestAuditRowMarksSnapshotStale() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
                "id", 43L,
                "changed_at", OffsetDateTime.parse("2026-05-20T01:05:00Z")
        )));
        DataChangeLogPendingContextFreshnessValidator validator =
                new DataChangeLogPendingContextFreshnessValidator(jdbcTemplate);

        PendingContextFreshnessDecision decision = validator.validate(PendingToolSnapshot.builder()
                .tenantId(1L)
                .modelCode("crm_customer")
                .recordVersion("change:42")
                .contextConflictPolicy(ContextConflictPolicy.ASK_USER_TO_CONFIRM_AGAIN.name())
                .input(Map.of("recordPid", "C-1"))
                .build());

        assertThat(decision.fresh()).isFalse();
        assertThat(decision.conflictPolicy()).isEqualTo(ContextConflictPolicy.ASK_USER_TO_CONFIRM_AGAIN);
        assertThat(decision.reasonCode()).isEqualTo("record_version_stale");
        assertThat(decision.message()).contains("crm_customer").contains("C-1");
    }

    @Test
    @DisplayName("contextVersion can provide model, record id and expected version")
    void contextVersionProvidesModelRecordAndVersion() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
                "id", 42L,
                "changed_at", OffsetDateTime.parse("2026-05-20T01:00:00Z")
        )));
        DataChangeLogPendingContextFreshnessValidator validator =
                new DataChangeLogPendingContextFreshnessValidator(jdbcTemplate);

        PendingContextFreshnessDecision decision = validator.validate(PendingToolSnapshot.builder()
                .tenantId(1L)
                .contextVersion("crm_customer:C-1:change:42")
                .build());

        assertThat(decision.fresh()).isTrue();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sql.capture(), any(Object[].class));
        assertThat(sql.getValue()).contains("FROM ab_data_change_log");
    }
}
