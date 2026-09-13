package com.auraboot.framework.behavior.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.behavior.controller.AnalyticsSuggestionController;
import com.auraboot.framework.bi.service.ReportAggregateQueryService;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalyticsExecutionStatusServiceTest {
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final AnalyticsExecutionStatusService service = new AnalyticsExecutionStatusService(jdbc);
    @BeforeEach void setup() { MetaContext.setContext(1L, 2L, "user", "user"); }
    @AfterEach void close() { MetaContext.clear(); }
    @Test void missingIdentityCannotReadEvenAnEmptyBatch() {
        MetaContext.clear();
        assertThatThrownBy(() -> service.read(List.of())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MetaContext not initialized");
        verifyNoInteractions(jdbc);
    }
    @Test void statusBatchIsScopedAndUnknownStatesStayUnknown() {
        when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(Map.of(
                "adoption_pid", "adopt", "deleted_flag", false, "run_status", "future-state", "attempts", 2L)));
        assertThat(service.read(List.of("adopt"))).containsEntry("adopt", new AnalyticsExecutionStatusService.Status("unknown", 2));
        verify(jdbc).queryForList(anyString(), eq(Map.of("tenant", 1L, "actor", 2L, "adoptions", List.of("adopt"))));
    }
    @Test void anOutOfRangePageStillReauthorizesBeforeReturningItsCount() {
        var data = mock(DynamicDataService.class);
        var queries = mock(ReportAggregateQueryService.class);
        var statuses = mock(AnalyticsExecutionStatusService.class);
        PaginationResult<Map<String, Object>> empty = mock(PaginationResult.class);
        when(empty.getRecords()).thenReturn(List.of());
        when(empty.getTotal()).thenReturn(6L);
        PaginationResult<Map<String, Object>> source = mock(PaginationResult.class);
        when(source.getRecords()).thenReturn(List.of(Map.of("core_dashboard_query", "{\"modelCode\":\"orders\"}")));
        when(data.list(anyString(), any())).thenReturn(empty, source);
        when(queries.execute(any())).thenThrow(new AccessDeniedException("Source revoked"));
        var controller = new AnalyticsSuggestionController(data, queries, new ObjectMapper(), statuses);
        assertThatThrownBy(() -> controller.list("analysis", 99, 5)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(statuses);
    }
}
