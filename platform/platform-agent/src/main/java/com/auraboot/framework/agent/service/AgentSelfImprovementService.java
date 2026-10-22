package com.auraboot.framework.agent.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * Agent Self-Improvement Service (G1 — Phase 6).
 *
 * <p>Extracts structured lessons from failed agent runs and persists them as
 * {@code LESSON} memories in {@code ab_agent_memory}.  Lessons are automatically
 * shared (shareable=false by default) and assigned to the "agent" category so they
 * are included in future prompt assembly via {@link AgentPromptAssemblyService}.
 *
 * <p>Rules for lesson extraction:
 * <ul>
 *   <li>The run must have {@code run_status = 'failed'}.</li>
 *   <li>The {@code error_message} must be non-null and contain at least 10 characters
 *       (short messages carry no actionable information).</li>
 *   <li>One LESSON memory per failed run is created (idempotent on duplicate runPid
 *       because of the source_run_id index; a second call just finds 0 insertions).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentSelfImprovementService {

    /** Minimum error_message length required before a lesson is worth persisting. */
    private static final int MIN_ERROR_LENGTH = 10;

    /** Default importance assigned to auto-extracted lessons. */
    private static final int DEFAULT_LESSON_IMPORTANCE = 7;

    private final JdbcTemplate jdbcTemplate;

    // =========================================================================
    // G1a — Extract lesson from a failed run
    // =========================================================================

    /**
     * Inspect a completed run and, if it failed with a meaningful error, store
     * a LESSON memory for the agent.
     *
     * @param tenantId  tenant scope
     * @param agentCode agent identifier (stored in {@code ab_agent_memory.memory_agent_id})
     * @param runPid    the {@code pid} of the agent run to inspect
     * @return 1 if a lesson was created, 0 if the run was not a qualifying failure
     */
    public int extractLessonsFromFailedRun(Long tenantId, String agentCode, String runPid) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT run_status, error_message "
                + "FROM ab_agent_run "
                + "WHERE pid = ? AND tenant_id = ?",
                runPid, tenantId);

        if (rows.isEmpty()) {
            log.warn("Run not found: pid={} tenantId={}", runPid, tenantId);
            return 0;
        }

        Map<String, Object> run = rows.get(0);
        String status = (String) run.get("run_status");
        String errorMessage = (String) run.get("error_message");

        if (!StatusConstants.FAILED.equals(status)) {
            log.debug("Run {} is not FAILED (status={}), skipping lesson extraction", runPid, status);
            return 0;
        }

        if (errorMessage == null || errorMessage.trim().length() < MIN_ERROR_LENGTH) {
            log.debug("Run {} has no meaningful error message, skipping lesson extraction", runPid);
            return 0;
        }

        // Check for duplicate: don't create two lessons for the same run
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_memory "
                + "WHERE tenant_id = ? AND memory_agent_id = ? "
                + "AND memory_type = 'lesson' AND source_run_id = ? "
                + "AND (deleted_flag IS NULL OR deleted_flag = FALSE)",
                Integer.class,
                tenantId, agentCode, runPid);

        if (existing != null && existing > 0) {
            log.debug("Lesson already exists for run {}", runPid);
            return 0;
        }

        String lessonTitle = "Failure lesson from run " + runPid;
        String lessonContent = buildLessonContent(errorMessage);

        String memoryPid = UniqueIdGenerator.generate();
        jdbcTemplate.update(
                "INSERT INTO ab_agent_memory "
                + "(pid, tenant_id, memory_agent_id, memory_type, category, "
                + " memory_title, memory_content, importance, source_run_id, "
                + " shareable, created_at, updated_at, deleted_flag) "
                + "VALUES (?, ?, ?, 'lesson', 'agent', ?, ?, ?, ?, FALSE, NOW(), NOW(), FALSE)",
                memoryPid, tenantId, agentCode,
                lessonTitle, lessonContent, DEFAULT_LESSON_IMPORTANCE, runPid);

        log.info("Created LESSON memory {} for agent {} from failed run {}", memoryPid, agentCode, runPid);
        return 1;
    }

    // =========================================================================
    // G1b — Improvement statistics
    // =========================================================================

    /**
     * Return aggregated improvement statistics for an agent.
     *
     * <p>Returned map keys:
     * <ul>
     *   <li>{@code agentCode}     — the agent identifier</li>
     *   <li>{@code totalRuns}     — total run count (all statuses)</li>
     *   <li>{@code successfulRuns}— runs with status SUCCESS</li>
     *   <li>{@code failedRuns}    — runs with status FAILED</li>
     *   <li>{@code successRate}   — ratio in [0.0, 1.0]; 0.0 when no runs exist</li>
     *   <li>{@code lessonCount}   — LESSON memories stored for this agent</li>
     * </ul>
     *
     * @param tenantId  tenant scope
     * @param agentCode agent identifier
     * @return non-null map with the keys listed above
     */
    public Map<String, Object> getImprovementStats(Long tenantId, String agentCode) {
        // Run stats — directly from ab_agent_run using agent_id
        Map<String, Object> runStats = jdbcTemplate.queryForMap(
                "SELECT "
                + "  COUNT(*) AS total_runs, "
                + "  COUNT(*) FILTER (WHERE run_status = 'success') AS successful_runs, "
                + "  COUNT(*) FILTER (WHERE run_status = 'failed')  AS failed_runs "
                + "FROM ab_agent_run "
                + "WHERE tenant_id = ? AND agent_id = ?",
                tenantId, agentCode);

        long totalRuns     = toLong(runStats.get("total_runs"));
        long successfulRuns = toLong(runStats.get("successful_runs"));
        long failedRuns    = toLong(runStats.get("failed_runs"));
        double successRate = (totalRuns > 0) ? (double) successfulRuns / totalRuns : 0.0;

        // Lesson count from ab_agent_memory
        Integer lessonCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_memory "
                + "WHERE tenant_id = ? AND memory_agent_id = ? "
                + "AND memory_type = 'lesson' "
                + "AND (deleted_flag IS NULL OR deleted_flag = FALSE)",
                Integer.class,
                tenantId, agentCode);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agentCode",     agentCode);
        result.put("totalRuns",     totalRuns);
        result.put("successfulRuns", successfulRuns);
        result.put("failedRuns",    failedRuns);
        result.put("successRate",   successRate);
        result.put("lessonCount",   lessonCount == null ? 0L : lessonCount.longValue());
        return result;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Construct a concise, human-readable lesson content from a raw error message.
     * The raw message is preserved verbatim; a brief prefix makes the intent clear.
     */
    private String buildLessonContent(String errorMessage) {
        String trimmed = errorMessage.trim();
        return "Run failed with error: " + trimmed;
    }

    private long toLong(Object value) {
        if (value == null) return 0L;
        return ((Number) value).longValue();
    }
}
