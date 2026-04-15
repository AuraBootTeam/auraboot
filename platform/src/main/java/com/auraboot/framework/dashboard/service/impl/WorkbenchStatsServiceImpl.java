package com.auraboot.framework.dashboard.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.StatusConstants;
import com.auraboot.framework.dashboard.dto.WorkbenchBpmStatsDTO;
import com.auraboot.framework.dashboard.dto.WorkbenchPipelineDTO;
import com.auraboot.framework.dashboard.dto.WorkbenchPipelineDTO.Stage;
import com.auraboot.framework.dashboard.dto.WorkbenchStatsDTO;
import com.auraboot.framework.dashboard.dto.WorkbenchStatsDTO.StatItem;
import com.auraboot.framework.dashboard.service.WorkbenchStatsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Supplier;

/**
 * Implementation of WorkbenchStatsService.
 * <p>
 * Uses JdbcTemplate for cross-table aggregation queries.
 * NOTE: JdbcTemplate usage is an intentional exception to the "no JdbcTemplate" rule —
 * this service aggregates across multiple tables (inbox, CRM dynamic tables, BPM)
 * where no single MyBatis Mapper applies.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkbenchStatsServiceImpl implements WorkbenchStatsService {

    // JdbcTemplate exception: cross-table aggregation across inbox/CRM/BPM tables
    private final JdbcTemplate jdbcTemplate;

    private static final String KEY_INBOX_PENDING = "inbox_pending";
    private static final String KEY_INBOX_URGENT = "inbox_urgent";
    private static final String KEY_CRM_OPPORTUNITY_AMOUNT = "crm_opportunity_amount";
    private static final String KEY_CRM_ACCOUNT_ACTIVE = "crm_account_active";
    private static final String KEY_BPM_RUNNING = "bpm_running";
    private static final String KEY_BPM_COMPLETED_WEEK = "bpm_completed_week";

    private static final List<String> DEFAULT_KEYS = List.of(
            KEY_INBOX_PENDING,
            KEY_INBOX_URGENT,
            KEY_CRM_OPPORTUNITY_AMOUNT,
            KEY_CRM_ACCOUNT_ACTIVE,
            KEY_BPM_RUNNING,
            KEY_BPM_COMPLETED_WEEK
    );

    private static final String FORMAT_NUMBER = "number";
    private static final String FORMAT_CURRENCY = "currency";

    @Override
    public WorkbenchStatsDTO getStats(List<String> keys) {
        List<String> requestedKeys = (keys == null || keys.isEmpty()) ? DEFAULT_KEYS : keys;

        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();

        Map<String, StatItem> stats = new LinkedHashMap<>();

        for (String key : requestedKeys) {
            StatItem item = computeStat(key, tenantId, userId);
            if (item != null) {
                stats.put(key, item);
            }
        }

        return WorkbenchStatsDTO.builder().stats(stats).build();
    }

    private StatItem computeStat(String key, Long tenantId, Long userId) {
        return switch (key) {
            case KEY_INBOX_PENDING -> computeInboxPending(tenantId, userId);
            case KEY_INBOX_URGENT -> computeInboxUrgent(tenantId, userId);
            case KEY_CRM_OPPORTUNITY_AMOUNT -> computeCrmOpportunityAmount(tenantId);
            case KEY_CRM_ACCOUNT_ACTIVE -> computeCrmAccountActive(tenantId);
            case KEY_BPM_RUNNING -> computeBpmRunning(tenantId);
            case KEY_BPM_COMPLETED_WEEK -> computeBpmCompletedWeek(tenantId);
            default -> {
                log.warn("Unknown workbench stat key: {}", key);
                yield null;
            }
        };
    }

    private StatItem computeInboxPending(Long tenantId, Long userId) {
        Long count = queryCount(
                "SELECT COUNT(*) FROM ab_inbox_item WHERE status = ? AND user_id = ? AND tenant_id = ?",
                StatusConstants.PENDING, userId, tenantId
        );
        return StatItem.builder()
                .value(count)
                .label("workbench.stats.inbox_pending")
                .format(FORMAT_NUMBER)
                .build();
    }

    private StatItem computeInboxUrgent(Long tenantId, Long userId) {
        Long count = queryCount(
                "SELECT COUNT(*) FROM ab_inbox_item WHERE status = ? AND priority IN ('urgent', 'high') AND user_id = ? AND tenant_id = ?",
                StatusConstants.PENDING, userId, tenantId
        );
        return StatItem.builder()
                .value(count)
                .label("workbench.stats.inbox_urgent")
                .format(FORMAT_NUMBER)
                .build();
    }

    private StatItem computeCrmOpportunityAmount(Long tenantId) {
        // CATCH: non-transactional query, CRM plugin may not be installed so table may not exist
        return safeQuery(() -> {
            Double amount = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(SUM(CAST(crm_opp_amount AS NUMERIC)), 0) FROM mt_crm_opportunity " +
                            "WHERE tenant_id = ? AND crm_opp_stage NOT IN ('closed_lost', 'closed_won')",
                    Double.class, tenantId
            );
            return StatItem.builder()
                    .value(amount != null ? amount : 0.0)
                    .label("workbench.stats.crm_opportunity_amount")
                    .format(FORMAT_CURRENCY)
                    .build();
        }, "crm_opportunity_amount");
    }

    private StatItem computeCrmAccountActive(Long tenantId) {
        // CATCH: non-transactional query, CRM plugin may not be installed so table may not exist
        return safeQuery(() -> {
            Long count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM mt_crm_account WHERE tenant_id = ?",
                    Long.class, tenantId
            );
            return StatItem.builder()
                    .value(count != null ? count : 0L)
                    .label("workbench.stats.crm_account_active")
                    .format(FORMAT_NUMBER)
                    .build();
        }, "crm_account_active");
    }

    private StatItem computeBpmRunning(Long tenantId) {
        // CATCH: non-transactional query, BPM execution log tracks process events
        return safeQuery(() -> {
            Long count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(DISTINCT execution_id) FROM ab_bpm_execution_log " +
                            "WHERE tenant_id = ? AND event_type = 'PROCESS_STARTED' " +
                            "AND execution_id NOT IN (" +
                            "  SELECT execution_id FROM ab_bpm_execution_log " +
                            "  WHERE tenant_id = ? AND event_type IN ('PROCESS_COMPLETED', 'PROCESS_CANCELLED')" +
                            ")",
                    Long.class, tenantId, tenantId
            );
            return StatItem.builder()
                    .value(count != null ? count : 0L)
                    .label("workbench.stats.bpm_running")
                    .format(FORMAT_NUMBER)
                    .build();
        }, "bpm_running");
    }

    private StatItem computeBpmCompletedWeek(Long tenantId) {
        // CATCH: non-transactional query, BPM execution log tracks process events
        return safeQuery(() -> {
            Long count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(DISTINCT execution_id) FROM ab_bpm_execution_log " +
                            "WHERE tenant_id = ? AND event_type = 'PROCESS_COMPLETED' " +
                            "AND created_at >= NOW() - INTERVAL '7 days'",
                    Long.class, tenantId
            );
            return StatItem.builder()
                    .value(count != null ? count : 0L)
                    .label("workbench.stats.bpm_completed_week")
                    .format(FORMAT_NUMBER)
                    .build();
        }, "bpm_completed_week");
    }

    // --- Pipeline stage constants ---

    private static final String STAGE_QUALIFICATION = "qualification";
    private static final String STAGE_NEEDS_ANALYSIS = "needs_analysis";
    private static final String STAGE_PROPOSAL = "proposal";
    private static final String STAGE_NEGOTIATION = "negotiation";
    private static final String STAGE_CLOSED_WON = "closed_won";
    private static final String STAGE_CLOSED_LOST = "closed_lost";

    private static final List<String> PIPELINE_STAGE_ORDER = List.of(
            STAGE_QUALIFICATION, STAGE_NEEDS_ANALYSIS, STAGE_PROPOSAL,
            STAGE_NEGOTIATION, STAGE_CLOSED_WON
    );

    private static final Map<String, String> PIPELINE_STAGE_COLORS = Map.of(
            STAGE_QUALIFICATION, "#93c5fd",
            STAGE_NEEDS_ANALYSIS, "#60a5fa",
            STAGE_PROPOSAL, "#3b82f6",
            STAGE_NEGOTIATION, "#2563eb",
            STAGE_CLOSED_WON, "#1d4ed8"
    );

    // --- BPM event type constants ---

    private static final String BPM_EVENT_STARTED = "PROCESS_STARTED";
    private static final String BPM_EVENT_COMPLETED = "PROCESS_COMPLETED";
    private static final String BPM_EVENT_CANCELLED = "PROCESS_CANCELLED";

    @Override
    public WorkbenchPipelineDTO getPipeline() {
        Long tenantId = MetaContext.getCurrentTenantId();

        // CATCH: non-transactional read-only query — CRM plugin table may not exist
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT crm_opp_stage AS stage, COUNT(*) AS cnt, " +
                            "COALESCE(SUM(CAST(crm_opp_amount AS NUMERIC)), 0) AS total_amount " +
                            "FROM mt_crm_opportunity " +
                            "WHERE tenant_id = ? AND crm_opp_stage != ? " +
                            "GROUP BY crm_opp_stage",
                    tenantId, STAGE_CLOSED_LOST
            );

            Map<String, Map<String, Object>> dataByStage = new HashMap<>();
            for (Map<String, Object> row : rows) {
                String stage = (String) row.get("stage");
                dataByStage.put(stage, row);
            }

            List<Stage> stages = new ArrayList<>();
            BigDecimal totalAmount = BigDecimal.ZERO;
            int totalCount = 0;

            for (String stageCode : PIPELINE_STAGE_ORDER) {
                Map<String, Object> row = dataByStage.get(stageCode);
                int count = 0;
                BigDecimal amount = BigDecimal.ZERO;
                if (row != null) {
                    count = ((Number) row.get("cnt")).intValue();
                    amount = new BigDecimal(row.get("total_amount").toString());
                }
                stages.add(Stage.builder()
                        .code(stageCode)
                        .label("workbench.pipeline." + stageCode)
                        .count(count)
                        .amount(amount)
                        .color(PIPELINE_STAGE_COLORS.get(stageCode))
                        .build());
                totalAmount = totalAmount.add(amount);
                totalCount += count;
            }

            return WorkbenchPipelineDTO.builder()
                    .stages(stages)
                    .totalAmount(totalAmount)
                    .totalCount(totalCount)
                    .build();
        } catch (Exception e) {
            log.debug("Failed to compute pipeline, CRM plugin table may not exist: {}", e.getMessage());
            return WorkbenchPipelineDTO.builder()
                    .stages(List.of())
                    .totalAmount(BigDecimal.ZERO)
                    .totalCount(0)
                    .build();
        }
    }

    @Override
    public WorkbenchBpmStatsDTO getBpmStats() {
        Long tenantId = MetaContext.getCurrentTenantId();

        // CATCH: non-transactional read-only query — BPM execution log table may not exist
        try {
            // Running count: started but not completed/cancelled
            Long runningCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(DISTINCT execution_id) FROM ab_bpm_execution_log " +
                            "WHERE tenant_id = ? AND event_type = ? " +
                            "AND execution_id NOT IN (" +
                            "  SELECT execution_id FROM ab_bpm_execution_log " +
                            "  WHERE tenant_id = ? AND event_type IN (?, ?)" +
                            ")",
                    Long.class, tenantId, BPM_EVENT_STARTED, tenantId, BPM_EVENT_COMPLETED, BPM_EVENT_CANCELLED
            );
            int running = runningCount != null ? runningCount.intValue() : 0;

            // Completed this week
            Long completedThisWeekCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(DISTINCT execution_id) FROM ab_bpm_execution_log " +
                            "WHERE tenant_id = ? AND event_type = ? " +
                            "AND created_at >= NOW() - INTERVAL '7 days'",
                    Long.class, tenantId, BPM_EVENT_COMPLETED
            );
            int completedThisWeek = completedThisWeekCount != null ? completedThisWeekCount.intValue() : 0;

            // Completed last week
            Long completedLastWeekCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(DISTINCT execution_id) FROM ab_bpm_execution_log " +
                            "WHERE tenant_id = ? AND event_type = ? " +
                            "AND created_at >= NOW() - INTERVAL '14 days' " +
                            "AND created_at < NOW() - INTERVAL '7 days'",
                    Long.class, tenantId, BPM_EVENT_COMPLETED
            );
            int completedLastWeek = completedLastWeekCount != null ? completedLastWeekCount.intValue() : 0;

            // Total completed (all time) for completion rate
            Long totalCompleted = jdbcTemplate.queryForObject(
                    "SELECT COUNT(DISTINCT execution_id) FROM ab_bpm_execution_log " +
                            "WHERE tenant_id = ? AND event_type = ?",
                    Long.class, tenantId, BPM_EVENT_COMPLETED
            );
            int completed = totalCompleted != null ? totalCompleted.intValue() : 0;

            // Completion rate = completed / (completed + running)
            double completionRate = 0.0;
            if (completed + running > 0) {
                completionRate = (double) completed / (completed + running) * 100.0;
            }

            // Average duration: time between PROCESS_STARTED and PROCESS_COMPLETED for same execution_id
            Double avgDuration = jdbcTemplate.queryForObject(
                    "SELECT AVG(EXTRACT(EPOCH FROM (c.created_at - s.created_at)) / 3600.0) " +
                            "FROM ab_bpm_execution_log s " +
                            "JOIN ab_bpm_execution_log c ON s.execution_id = c.execution_id " +
                            "WHERE s.tenant_id = ? AND s.event_type = ? " +
                            "AND c.event_type = ?",
                    Double.class, tenantId, BPM_EVENT_STARTED, BPM_EVENT_COMPLETED
            );
            double avgDurationHours = avgDuration != null ? avgDuration : 0.0;

            return WorkbenchBpmStatsDTO.builder()
                    .completionRate(Math.round(completionRate * 10.0) / 10.0)
                    .avgDurationHours(Math.round(avgDurationHours * 10.0) / 10.0)
                    .overdueRate(0.0) // No due_date field available in execution log
                    .runningCount(running)
                    .completedThisWeek(completedThisWeek)
                    .completedLastWeek(completedLastWeek)
                    .build();
        } catch (Exception e) {
            log.debug("Failed to compute BPM stats, table may not exist: {}", e.getMessage());
            return WorkbenchBpmStatsDTO.builder()
                    .completionRate(0.0)
                    .avgDurationHours(0.0)
                    .overdueRate(0.0)
                    .runningCount(0)
                    .completedThisWeek(0)
                    .completedLastWeek(0)
                    .build();
        }
    }

    /**
     * Safely execute a query that may fail if the target table doesn't exist
     * (e.g. CRM/BPM plugin not installed). Returns a zero-value StatItem on failure.
     * <p>
     * CATCH: non-transactional read-only query — plugin tables may not exist,
     * and returning a zero stat is the expected behavior when the plugin is not installed.
     */
    private StatItem safeQuery(Supplier<StatItem> query, String statKey) {
        try {
            return query.get();
        } catch (Exception e) {
            log.debug("Failed to compute stat '{}', plugin table may not exist: {}", statKey, e.getMessage());
            return StatItem.builder()
                    .value(0)
                    .label("workbench.stats." + statKey)
                    .format(FORMAT_NUMBER)
                    .build();
        }
    }

    private Long queryCount(String sql, Object... args) {
        Long result = jdbcTemplate.queryForObject(sql, Long.class, args);
        return result != null ? result : 0L;
    }
}
