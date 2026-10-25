package com.auraboot.framework.agent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent Cost Report Service (G3 — Phase 6).
 *
 * <p>Provides aggregation queries over {@code ab_agent_run} to support cost
 * monitoring, budget planning, and per-agent spend analysis.
 *
 * <p>All cost values are expressed as {@code NUMERIC(10,6)} totals as stored in the
 * database.  Callers should treat the returned {@link java.math.BigDecimal} values
 * appropriately when displaying currency.
 *
 * <p>Design note: this service intentionally performs read-only queries against
 * {@code ab_agent_run} directly.  No JOIN to {@code ab_agent_definition} is
 * performed because agent_code is not a column in {@code ab_agent_run}; the
 * {@code agent_id} column in that table stores the agent code string directly
 * (see schema).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentCostReportService {

    /** Maximum allowed value for the {@code days} parameter to prevent runaway queries. */
    private static final int MAX_DAYS = 365;

    private final JdbcTemplate jdbcTemplate;

    // =========================================================================
    // G3a — Cost aggregated by agent
    // =========================================================================

    /**
     * Return per-agent cost totals for the given tenant.
     *
     * <p>Each row in the result contains:
     * <ul>
     *   <li>{@code agent_id}          — the agent code stored in ab_agent_run</li>
     *   <li>{@code total_runs}        — total number of runs</li>
     *   <li>{@code successful_runs}   — runs with status SUCCESS</li>
     *   <li>{@code failed_runs}       — runs with status FAILED</li>
     *   <li>{@code total_cost}        — sum of total_cost (COALESCE'd to 0 for nulls)</li>
     *   <li>{@code total_input_tokens}  — sum of input_tokens</li>
     *   <li>{@code total_output_tokens} — sum of output_tokens</li>
     * </ul>
     *
     * @param tenantId tenant scope
     * @return list of per-agent rows, ordered by total_cost DESC; may be empty but never null
     */
    public List<Map<String, Object>> getCostByAgent(Long tenantId) {
        return jdbcTemplate.queryForList(
                "SELECT "
                + "  agent_id, "
                + "  COUNT(*)                                               AS total_runs, "
                + "  COUNT(*) FILTER (WHERE run_status = 'success')         AS successful_runs, "
                + "  COUNT(*) FILTER (WHERE run_status = 'failed')          AS failed_runs, "
                + "  COALESCE(SUM(total_cost),    0)                        AS total_cost, "
                + "  COALESCE(SUM(input_tokens),  0)                        AS total_input_tokens, "
                + "  COALESCE(SUM(output_tokens), 0)                        AS total_output_tokens "
                + "FROM ab_agent_run "
                + "WHERE tenant_id = ? "
                + "GROUP BY agent_id "
                + "ORDER BY total_cost DESC",
                tenantId);
    }

    // =========================================================================
    // G3b — Cost aggregated by day
    // =========================================================================

    /**
     * Return daily cost aggregates for the last {@code days} calendar days.
     *
     * <p>Each row contains:
     * <ul>
     *   <li>{@code run_date}   — the calendar date (DATE type)</li>
     *   <li>{@code run_count}  — number of runs that day</li>
     *   <li>{@code total_cost} — sum of total_cost for that day</li>
     *   <li>{@code total_input_tokens}  — sum of input_tokens</li>
     *   <li>{@code total_output_tokens} — sum of output_tokens</li>
     * </ul>
     *
     * <p>Rows are ordered newest-first (run_date DESC).
     *
     * @param tenantId tenant scope
     * @param days     number of past days to include; must be in [1, 365]
     * @return list of daily rows; may be empty but never null
     * @throws IllegalArgumentException if {@code days} is outside [1, 365]
     */
    public List<Map<String, Object>> getCostByDay(Long tenantId, int days) {
        if (days < 1 || days > MAX_DAYS) {
            throw new IllegalArgumentException(
                    "days must be between 1 and " + MAX_DAYS + ", got: " + days);
        }

        return jdbcTemplate.queryForList(
                "SELECT "
                + "  DATE(created_at AT TIME ZONE 'UTC')   AS run_date, "
                + "  COUNT(*)                               AS run_count, "
                + "  COALESCE(SUM(total_cost),    0)        AS total_cost, "
                + "  COALESCE(SUM(input_tokens),  0)        AS total_input_tokens, "
                + "  COALESCE(SUM(output_tokens), 0)        AS total_output_tokens "
                + "FROM ab_agent_run "
                + "WHERE tenant_id = ? "
                + "  AND created_at >= NOW() - (? || ' days')::interval "
                + "GROUP BY DATE(created_at AT TIME ZONE 'UTC') "
                + "ORDER BY run_date DESC",
                tenantId, days);
    }

    // =========================================================================
    // G3c — Tenant-level cost summary
    // =========================================================================

    /**
     * Return a single-row summary of all agent costs for the tenant.
     *
     * <p>Returned map keys: {@code total_cost}, {@code total_runs},
     * {@code successful_runs}, {@code failed_runs}, {@code total_input_tokens},
     * {@code total_output_tokens}, {@code distinct_agents}.
     *
     * @param tenantId tenant scope
     * @return non-null, non-empty map; all numeric fields default to 0 when there are no runs
     */
    public Map<String, Object> getTenantCostSummary(Long tenantId) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT "
                + "  COALESCE(SUM(total_cost),    0)                       AS total_cost, "
                + "  COUNT(*)                                               AS total_runs, "
                + "  COUNT(*) FILTER (WHERE run_status = 'success')         AS successful_runs, "
                + "  COUNT(*) FILTER (WHERE run_status = 'failed')          AS failed_runs, "
                + "  COALESCE(SUM(input_tokens),  0)                        AS total_input_tokens, "
                + "  COALESCE(SUM(output_tokens), 0)                        AS total_output_tokens, "
                + "  COUNT(DISTINCT agent_id)                               AS distinct_agents "
                + "FROM ab_agent_run "
                + "WHERE tenant_id = ?",
                tenantId);

        // Return a LinkedHashMap to guarantee deterministic key ordering
        Map<String, Object> result = new LinkedHashMap<>(row);
        return result;
    }
}
