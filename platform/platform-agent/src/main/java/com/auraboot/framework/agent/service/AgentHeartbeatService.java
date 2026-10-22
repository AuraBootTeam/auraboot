package com.auraboot.framework.agent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Proactive health monitoring for the agent runtime (F6 — HEARTBEAT schedule template).
 * <p>
 * Runs periodic checks against key agent tables and reports a structured health map
 * with counters for timeout approvals, stale tasks, recent failures, and memory-overloaded agents.
 * The result can be used by a scheduled HEARTBEAT trigger to alert ops or auto-remediate.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentHeartbeatService {

    private final JdbcTemplate jdbcTemplate;

    /** PENDING approvals older than this are considered timed out. */
    private static final String APPROVAL_TIMEOUT_INTERVAL = "24 hours";

    /** IN_PROGRESS tasks older than this are considered stale. */
    private static final String STALE_TASK_INTERVAL = "1 hour";

    /** Recent failure window. */
    private static final String FAILURE_WINDOW = "1 hour";

    /** Failure count threshold before healthy flips to false. */
    private static final int FAILURE_THRESHOLD = 2;

    /**
     * Run a full health check for the given tenant and return a structured report.
     *
     * @param tenantId the tenant to check
     * @return map with keys: timeout_approvals, stale_tasks, recent_failures,
     *         memory_overloaded_agents, healthy, checked_at
     */
    public Map<String, Object> runHeartbeat(Long tenantId) {
        int timeoutApprovals = countTimeoutApprovals(tenantId);
        int staleTasks = countStaleTasks(tenantId);
        int recentFailures = countRecentFailures(tenantId);
        int memoryOverloaded = countMemoryOverloadedAgents(tenantId);

        boolean healthy = timeoutApprovals == 0
                && staleTasks == 0
                && recentFailures <= FAILURE_THRESHOLD;

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("timeout_approvals", timeoutApprovals);
        report.put("stale_tasks", staleTasks);
        report.put("recent_failures", recentFailures);
        report.put("memory_overloaded_agents", memoryOverloaded);
        report.put("healthy", healthy);
        report.put("checked_at", Instant.now().toString());

        if (!healthy) {
            log.warn("Agent heartbeat UNHEALTHY: tenant={}, timeout_approvals={}, stale_tasks={}, recent_failures={}",
                    tenantId, timeoutApprovals, staleTasks, recentFailures);
        } else {
            log.debug("Agent heartbeat healthy: tenant={}", tenantId);
        }

        return report;
    }

    /**
     * Count PENDING approvals that have exceeded the timeout window.
     *
     * @param tenantId the tenant to check
     * @return count of timed-out pending approvals
     */
    public int countTimeoutApprovals(Long tenantId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_approval " +
                "WHERE tenant_id = ? AND approval_status = 'pending' " +
                "AND created_at < NOW() - INTERVAL '" + APPROVAL_TIMEOUT_INTERVAL + "'",
                Integer.class, tenantId);
        return count != null ? count : 0;
    }

    /**
     * Count IN_PROGRESS agent tasks that have exceeded the stale threshold.
     *
     * @param tenantId the tenant to check
     * @return count of stale in-progress tasks
     */
    public int countStaleTasks(Long tenantId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_task " +
                "WHERE tenant_id = ? AND task_status = 'in_progress' AND assignee_type = 'agent' " +
                "AND (deleted_flag = FALSE OR deleted_flag IS NULL) " +
                "AND updated_at < NOW() - INTERVAL '" + STALE_TASK_INTERVAL + "'",
                Integer.class, tenantId);
        return count != null ? count : 0;
    }

    /**
     * Count FAILED agent runs within the recent failure window.
     *
     * @param tenantId the tenant to check
     * @return count of recent failures
     */
    public int countRecentFailures(Long tenantId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_run " +
                "WHERE tenant_id = ? AND run_status = 'failed' " +
                "AND started_at > NOW() - INTERVAL '" + FAILURE_WINDOW + "'",
                Integer.class, tenantId);
        return count != null ? count : 0;
    }

    /**
     * Count agents whose memory usage exceeds threshold (placeholder for future memory tracking).
     * Returns 0 until agent memory usage tracking is implemented.
     *
     * @param tenantId the tenant to check
     * @return count of memory-overloaded agents (currently always 0)
     */
    public int countMemoryOverloadedAgents(Long tenantId) {
        // Phase 6+ will add memory_usage_bytes to ab_agent_definition or ab_agent_run
        return 0;
    }
}
