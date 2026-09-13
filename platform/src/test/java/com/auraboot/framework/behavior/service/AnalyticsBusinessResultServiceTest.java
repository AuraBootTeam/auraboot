package com.auraboot.framework.behavior.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import java.util.*;
import java.sql.Timestamp;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AnalyticsBusinessResultServiceTest {
    private final AnalyticsExecutionSourceService sources = mock(AnalyticsExecutionSourceService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final DynamicDataService data = mock(DynamicDataService.class);
    private final UserPermissionService permissions = mock(UserPermissionService.class);
    private final MetaModelService models = mock(MetaModelService.class);
    private final ObjectMapper json = new ObjectMapper();
    private final AnalyticsBusinessResultService service = new AnalyticsBusinessResultService(sources, jdbc, data, permissions, models, json);
    @BeforeEach void setup() { MetaContext.setContext(1L, 2L, "user", "user"); }
    @AfterEach void close() { MetaContext.clear(); }
    private void results(List<Map<String, Object>> rows) {
        when(sources.resolveForRead("ADOPTION")).thenReturn(new AnalyticsExecutionSourceService.Source("goal", Map.of("analysisId", "ANALYSIS")));
        when(jdbc.queryForList(anyString(), eq(1L), eq(2L), eq("ADOPTION"), eq(2L), eq(11), eq(0))).thenReturn(rows);
    }
    private Map<String, Object> row() {
        return Map.of("event_id", "EVENT", "target_type", "orders", "target_key", "ORDER",
                "occurred_at", Timestamp.from(Instant.parse("2026-09-13T01:00:00Z")),
                "payload", "{\"analyticsExecution\":{\"analysisId\":\"ANALYSIS\"},\"modelCode\":\"orders\",\"recordPid\":\"ORDER\",\"operation\":\"create\"}");
    }
    @Test void sourceRevocationPreventsReadingAnyResult() {
        when(sources.resolveForRead("ADOPTION")).thenThrow(new AccessDeniedException("revoked"));
        assertThatThrownBy(() -> service.read("ADOPTION", 1, 10)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(jdbc, data);
    }
    @Test void targetModelPermissionIsIndependentOfSourcePermission() {
        results(List.of(row()));
        assertThatThrownBy(() -> service.read("ADOPTION", 1, 10)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(data);
    }
    @Test void targetRowDenialPreventsProjection() {
        results(List.of(row()));
        when(permissions.hasPermission(2L, "model.orders.read")).thenReturn(true);
        when(data.getById("orders", "ORDER")).thenThrow(new AccessDeniedException("row denied"));
        assertThatThrownBy(() -> service.read("ADOPTION", 1, 10)).isInstanceOf(AccessDeniedException.class);
    }
    @Test void returnsOnlyAuthorizedPresentationFields() {
        results(List.of(row()));
        when(permissions.hasPermission(2L, "model.orders.read")).thenReturn(true);
        when(data.getById("orders", "ORDER")).thenReturn(Map.of("pid", "ORDER", "secret", "private"));
        var result = service.read("ADOPTION", 1, 10);
        assertThat(result.records()).hasSize(1);
        assertThat(result.records().get(0).operation()).isEqualTo("create");
        assertThat(result.records().get(0).recordedAt()).isEqualTo(Instant.parse("2026-09-13T01:00:00Z"));
        assertThat(result.hasMore()).isFalse();
        assertThat(result.toString()).doesNotContain("private", "ORDER");
        verify(sources, never()).resolve(anyString());
    }
    @Test void corruptBindingDoesNotReturnAResult() {
        var row = new HashMap<>(row());
        row.put("payload", row.get("payload").toString().replace("ANALYSIS", "OTHER"));
        results(List.of(row));
        when(permissions.hasPermission(2L, "model.orders.read")).thenReturn(true);
        when(data.getById("orders", "ORDER")).thenReturn(Map.of("pid", "ORDER"));
        assertThatThrownBy(() -> service.read("ADOPTION", 1, 10)).isInstanceOf(IllegalStateException.class);
    }
    @Test void invalidPageDoesNotTouchSourceOrStorage() {
        assertThatThrownBy(() -> service.read("ADOPTION", 0, 10)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(sources, jdbc, data);
    }
}
