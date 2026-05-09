package com.auraboot.framework.dashboard.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.dashboard.dto.WorkbenchBpmStatsDTO;
import com.auraboot.framework.dashboard.dto.WorkbenchPipelineDTO;
import com.auraboot.framework.dashboard.dto.WorkbenchStatsDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkbenchStatsServiceImpl")
class WorkbenchStatsServiceImplTest {

    @Mock private JdbcTemplate jdbcTemplate;

    private WorkbenchStatsServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new WorkbenchStatsServiceImpl(jdbcTemplate);
        MetaContext.setContext(10L, 1L, "u-1", "user");
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    @DisplayName("getStats returns inbox_pending stat with count")
    void getStatsInboxPending() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(), any(), any()))
                .thenReturn(7L);
        WorkbenchStatsDTO out = service.getStats(List.of("inbox_pending"));
        assertEquals(1, out.getStats().size());
        assertEquals(7L, out.getStats().get("inbox_pending").getValue());
        assertEquals("number", out.getStats().get("inbox_pending").getFormat());
    }

    @Test
    @DisplayName("getStats with unknown key skips it")
    void getStatsUnknownKey() {
        WorkbenchStatsDTO out = service.getStats(List.of("nonexistent_key"));
        assertEquals(0, out.getStats().size());
    }

    @Test
    @DisplayName("getStats(empty) computes all defaults; CRM/BPM table errors return zero stats")
    void getStatsDefaults() {
        // inbox queries return numeric counts
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(), any(), any()))
                .thenReturn(3L);
        // crm/bpm queries throw to trigger safeQuery fallback
        when(jdbcTemplate.queryForObject(anyString(), eq(Double.class), any()))
                .thenThrow(new RuntimeException("table missing"));
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any()))
                .thenThrow(new RuntimeException("table missing"));
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(), any()))
                .thenThrow(new RuntimeException("table missing"));

        WorkbenchStatsDTO out = service.getStats(null);
        assertEquals(6, out.getStats().size());
    }

    @Test
    @DisplayName("getPipeline returns empty stages on table-missing error")
    void getPipelineFallback() {
        when(jdbcTemplate.queryForList(anyString(), (Object[]) any()))
                .thenThrow(new RuntimeException("table missing"));
        WorkbenchPipelineDTO p = service.getPipeline();
        assertNotNull(p);
        assertEquals(0, p.getStages().size());
    }

    @Test
    @DisplayName("getPipeline aggregates rows into stages")
    void getPipelineAggregates() {
        Map<String, Object> row = new HashMap<>();
        row.put("stage", "qualification");
        row.put("cnt", 2);
        row.put("total_amount", "1000");
        when(jdbcTemplate.queryForList(anyString(), (Object[]) any()))
                .thenReturn(List.of(row));

        WorkbenchPipelineDTO p = service.getPipeline();
        assertEquals(5, p.getStages().size());
        assertEquals(2, p.getTotalCount());
    }

    @Test
    @DisplayName("getBpmStats returns zeros when log table missing")
    void getBpmStatsFallback() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("table missing"));
        WorkbenchBpmStatsDTO out = service.getBpmStats();
        assertEquals(0.0, out.getCompletionRate());
        assertEquals(0, out.getRunningCount());
    }

    @Test
    @DisplayName("getBpmStats computes completion rate")
    void getBpmStatsHappy() {
        // Running count
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(), any(), any(), any(), any()))
                .thenReturn(2L);
        // Other Long queries
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(), any()))
                .thenReturn(8L);
        // Avg duration
        when(jdbcTemplate.queryForObject(anyString(), eq(Double.class), any(), any(), any()))
                .thenReturn(5.5);

        WorkbenchBpmStatsDTO out = service.getBpmStats();
        assertEquals(2, out.getRunningCount());
        // completed=8, running=2 → 8 / 10 * 100 = 80.0
        assertEquals(80.0, out.getCompletionRate());
        assertEquals(5.5, out.getAvgDurationHours());
    }
}
