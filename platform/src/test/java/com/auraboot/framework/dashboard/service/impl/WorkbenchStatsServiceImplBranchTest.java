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

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Additional branch coverage for {@link WorkbenchStatsServiceImpl} —
 * exercises happy paths of each safeQuery stat key and zero/null
 * fallbacks not covered by {@code WorkbenchStatsServiceImplTest}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkbenchStatsServiceImpl branch coverage")
class WorkbenchStatsServiceImplBranchTest {

    @Mock private JdbcTemplate jdbcTemplate;

    private WorkbenchStatsServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new WorkbenchStatsServiceImpl(jdbcTemplate);
        MetaContext.setContext(20L, 2L, "u-2", "user");
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    @DisplayName("getStats inbox_urgent computes count from JdbcTemplate")
    void inboxUrgentHappy() {
        // 4-arg variant: status, userId, tenantId
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(), any(), any()))
                .thenReturn(11L);
        WorkbenchStatsDTO out = service.getStats(List.of("inbox_urgent"));
        assertEquals(11L, out.getStats().get("inbox_urgent").getValue());
        assertEquals("workbench.stats.inbox_urgent", out.getStats().get("inbox_urgent").getLabel());
    }

    @Test
    @DisplayName("crm_opportunity_amount happy path returns currency-formatted amount")
    void crmOpportunityAmountHappy() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Double.class), any()))
                .thenReturn(1234.56);
        WorkbenchStatsDTO out = service.getStats(List.of("crm_opportunity_amount"));
        assertEquals(1234.56, out.getStats().get("crm_opportunity_amount").getValue());
        assertEquals("currency", out.getStats().get("crm_opportunity_amount").getFormat());
    }

    @Test
    @DisplayName("crm_opportunity_amount returns 0.0 when query returns null")
    void crmOpportunityAmountNull() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Double.class), any()))
                .thenReturn(null);
        WorkbenchStatsDTO out = service.getStats(List.of("crm_opportunity_amount"));
        assertEquals(0.0, out.getStats().get("crm_opportunity_amount").getValue());
    }

    @Test
    @DisplayName("crm_account_active happy path returns count")
    void crmAccountActiveHappy() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any()))
                .thenReturn(42L);
        WorkbenchStatsDTO out = service.getStats(List.of("crm_account_active"));
        assertEquals(42L, out.getStats().get("crm_account_active").getValue());
    }

    @Test
    @DisplayName("crm_account_active returns 0L when query returns null")
    void crmAccountActiveNull() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any()))
                .thenReturn(null);
        WorkbenchStatsDTO out = service.getStats(List.of("crm_account_active"));
        assertEquals(0L, out.getStats().get("crm_account_active").getValue());
    }

    @Test
    @DisplayName("bpm_running happy path returns count from 2-arg variant")
    void bpmRunningHappy() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(), any()))
                .thenReturn(7L);
        WorkbenchStatsDTO out = service.getStats(List.of("bpm_running"));
        assertEquals(7L, out.getStats().get("bpm_running").getValue());
    }

    @Test
    @DisplayName("bpm_completed_week happy path returns count")
    void bpmCompletedWeekHappy() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any()))
                .thenReturn(3L);
        WorkbenchStatsDTO out = service.getStats(List.of("bpm_completed_week"));
        assertEquals(3L, out.getStats().get("bpm_completed_week").getValue());
    }

    @Test
    @DisplayName("getPipeline accumulates totalAmount across multiple stages")
    void pipelineMultipleStages() {
        Map<String, Object> q = new HashMap<>();
        q.put("stage", "qualification");
        q.put("cnt", 2);
        q.put("total_amount", "100");

        Map<String, Object> p = new HashMap<>();
        p.put("stage", "proposal");
        p.put("cnt", 1);
        p.put("total_amount", "500");

        when(jdbcTemplate.queryForList(anyString(), (Object[]) any()))
                .thenReturn(List.of(q, p));

        WorkbenchPipelineDTO out = service.getPipeline();
        assertEquals(3, out.getTotalCount());
        assertEquals(0, BigDecimal.valueOf(600).compareTo(out.getTotalAmount()));
        // Each stage in PIPELINE_STAGE_ORDER must be represented (5 entries)
        assertEquals(5, out.getStages().size());
    }

    @Test
    @DisplayName("getPipeline ignores rows for stages outside PIPELINE_STAGE_ORDER")
    void pipelineUnknownStageIgnored() {
        Map<String, Object> row = new HashMap<>();
        row.put("stage", "no_such_stage");
        row.put("cnt", 99);
        row.put("total_amount", "9999");
        when(jdbcTemplate.queryForList(anyString(), (Object[]) any()))
                .thenReturn(List.of(row));

        WorkbenchPipelineDTO out = service.getPipeline();
        // stage list still has 5 known entries, all empty (count 0)
        assertEquals(5, out.getStages().size());
        assertEquals(0, out.getTotalCount());
    }

    @Test
    @DisplayName("getBpmStats with zero completed and zero running yields 0% completion rate")
    void bpmStatsZeroDivision() {
        // Running = 0
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(), any(), any(), any(), any()))
                .thenReturn(0L);
        // 2-arg Long queries (completed week, completed last week, total completed) all return 0
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(), any()))
                .thenReturn(0L);
        // Avg duration null → 0.0
        when(jdbcTemplate.queryForObject(anyString(), eq(Double.class), any(), any(), any()))
                .thenReturn(null);

        WorkbenchBpmStatsDTO out = service.getBpmStats();
        assertEquals(0.0, out.getCompletionRate());
        assertEquals(0.0, out.getAvgDurationHours());
        assertEquals(0, out.getRunningCount());
        assertEquals(0, out.getCompletedThisWeek());
        assertEquals(0, out.getCompletedLastWeek());
    }

    @Test
    @DisplayName("getBpmStats with null Long queries treats counts as zero")
    void bpmStatsNullCounts() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(), any(), any(), any(), any()))
                .thenReturn(null);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(), any()))
                .thenReturn(null);
        when(jdbcTemplate.queryForObject(anyString(), eq(Double.class), any(), any(), any()))
                .thenReturn(2.0);

        WorkbenchBpmStatsDTO out = service.getBpmStats();
        assertNotNull(out);
        assertEquals(0, out.getRunningCount());
        assertEquals(2.0, out.getAvgDurationHours());
    }

    @Test
    @DisplayName("getStats(null) with all-success path covers every key in DEFAULT_KEYS")
    void getStatsAllDefaultsHappy() {
        // Inbox: 5-arg Long
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(), any(), any()))
                .thenReturn(1L);
        // CRM opportunity: Double 1-arg
        when(jdbcTemplate.queryForObject(anyString(), eq(Double.class), any()))
                .thenReturn(50.0);
        // CRM account active: Long 1-arg
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any()))
                .thenReturn(2L);
        // BPM running: Long 2-arg
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(), any()))
                .thenReturn(3L);

        WorkbenchStatsDTO out = service.getStats(null);
        assertEquals(6, out.getStats().size());
        assertTrue(out.getStats().containsKey("inbox_pending"));
        assertTrue(out.getStats().containsKey("inbox_urgent"));
        assertTrue(out.getStats().containsKey("crm_opportunity_amount"));
        assertTrue(out.getStats().containsKey("crm_account_active"));
        assertTrue(out.getStats().containsKey("bpm_running"));
        assertTrue(out.getStats().containsKey("bpm_completed_week"));
    }
}
