package com.auraboot.framework.agent.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
@DisplayName("DataChangeLogPendingContextVersionResolver")
class DataChangeLogPendingContextVersionResolverTest {

    @Mock private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("resolves latest audit row into stable pending context version")
    void resolvesLatestAuditRowIntoStablePendingContextVersion() {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
                "id", 42L,
                "changed_at", OffsetDateTime.parse("2026-05-20T01:00:00Z")
        )));
        DataChangeLogPendingContextVersionResolver resolver =
                new DataChangeLogPendingContextVersionResolver(jdbcTemplate);

        PendingContextVersion version = resolver.resolve(new PendingContextVersionRequest(
                1L,
                "crm_customer",
                "C-100"));

        assertThat(version.modelCode()).isEqualTo("crm_customer");
        assertThat(version.recordPid()).isEqualTo("C-100");
        assertThat(version.recordVersion()).isEqualTo("change:42");
        assertThat(version.contextVersion()).isEqualTo("crm_customer:C-100:change:42");
    }

    @Test
    @DisplayName("returns empty metadata when record identity is not verifiable")
    void returnsEmptyMetadataWhenRecordIdentityIsNotVerifiable() {
        DataChangeLogPendingContextVersionResolver resolver =
                new DataChangeLogPendingContextVersionResolver(jdbcTemplate);

        PendingContextVersion version = resolver.resolve(new PendingContextVersionRequest(
                1L,
                "crm_customer",
                null));

        assertThat(version.verifiable()).isFalse();
        verify(jdbcTemplate, never()).queryForList(anyString(), any(Object[].class));
    }
}
