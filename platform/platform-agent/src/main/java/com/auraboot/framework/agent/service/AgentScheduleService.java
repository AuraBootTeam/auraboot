package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.config.AgentProperties;
import com.auraboot.framework.agent.provider.ToolDefinition;
import com.auraboot.framework.agent.provider.ToolDiscoveryContext;
import com.auraboot.framework.agent.provider.ToolProviderRegistry;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentScheduleService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    /** Cron expression for the HEARTBEAT schedule: every 30 minutes. */
    public static final String HEARTBEAT_CRON = "0 */30 * * * *";

    /** schedule_type value used for HEARTBEAT schedules. */
    public static final String HEARTBEAT_SCHEDULE_TYPE = "cron";

    /** Unique title prefix that identifies the system heartbeat schedule. */
    public static final String HEARTBEAT_TITLE = "[SYSTEM] ACP Heartbeat";

    private final AgentProperties agentProperties;
    private final DynamicDataMapper dynamicDataMapper;
    private final AgentRunService agentRunService;
    private final AgentObservationService observationService;
    private final ToolProviderRegistry toolProviderRegistry;
    private final TaskScheduler taskScheduler;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ProactiveTriggerPolicyService proactiveTriggerPolicy;

    private final Map<String, ScheduledFuture<?>> scheduledFutures = new ConcurrentHashMap<>();

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        if (!agentProperties.isEnabled()) {
            log.info("Agent runtime disabled, skipping schedule initialization");
            return;
        }
        try {
            loadAndScheduleAll();
        } catch (Exception e) {
            log.error("Failed to initialize agent schedules on startup — agent scheduling will be unavailable: {}", e.getMessage(), e);
        }
    }

    public void loadAndScheduleAll() {
        scheduledFutures.values().forEach(f -> f.cancel(false));
        scheduledFutures.clear();

        String sql = "SELECT * FROM ab_agent_schedule " +
                "WHERE schedule_status = 'active' AND deleted_flag = FALSE " +
                "ORDER BY created_at";
        List<Map<String, Object>> schedules = dynamicDataMapper.selectByQueryWithoutTenant(sql, Map.of());

        int registered = 0;
        for (Map<String, Object> schedule : schedules) {
            try {
                registerSchedule(schedule);
                registered++;
            } catch (Exception e) {
                log.error("Failed to register schedule: pid={}, error={}", schedule.get("pid"), e.getMessage());
            }
        }
        log.info("Agent scheduler initialized: {}/{} schedules registered", registered, schedules.size());
    }

    private void registerSchedule(Map<String, Object> schedule) {
        String pid = (String) schedule.get("pid");
        String scheduleType = (String) schedule.get("schedule_type");
        String cronExpr = (String) schedule.get("cron_expression");
        Long tenantId = ((Number) schedule.get("tenant_id")).longValue();

        if ("cron".equals(scheduleType) && cronExpr != null) {
            String timezone = Objects.toString(schedule.get("timezone"), "UTC");
            ScheduledFuture<?> future = taskScheduler.schedule(
                    () -> triggerSchedule(tenantId, pid, schedule),
                    new CronTrigger(cronExpr, java.time.ZoneId.of(timezone)));
            scheduledFutures.put(pid, future);
            log.info("Registered CRON schedule: pid={}, cron={}, timezone={}",
                    pid, cronExpr, timezone);
        } else if ("interval".equals(scheduleType)) {
            Long intervalMs = schedule.get("interval_ms") != null ? ((Number) schedule.get("interval_ms")).longValue() : null;
            if (intervalMs != null && intervalMs > 0) {
                ScheduledFuture<?> future = taskScheduler.scheduleWithFixedDelay(
                        () -> triggerSchedule(tenantId, pid, schedule),
                        Duration.ofMillis(intervalMs));
                scheduledFutures.put(pid, future);
                log.info("Registered INTERVAL schedule: pid={}, interval={}ms", pid, intervalMs);
            }
        }
        reconcileMissedRun(tenantId, pid, schedule, Instant.now());
    }

    private void triggerSchedule(Long tenantId, String schedulePid, Map<String, Object> schedule) {
        triggerSchedule(tenantId, schedulePid, schedule, true);
    }

    private TriggerResult triggerSchedule(
            Long tenantId,
            String schedulePid,
            Map<String, Object> schedule,
            boolean advanceCursor) {
        log.info("Schedule triggered: pid={}, tenant={}", schedulePid, tenantId);
        MetaContext.setSystemTenantContext(tenantId);
        try {
            List<Map<String, Object>> current = jdbcTemplate.queryForList(
                    "SELECT schedule_status, run_count, max_runs "
                            + "FROM ab_agent_schedule "
                            + "WHERE tenant_id = ? AND pid = ? AND deleted_flag = FALSE",
                    tenantId,
                    schedulePid);
            if (current.size() != 1
                    || !"active".equals(current.get(0).get("schedule_status"))) {
                return TriggerResult.denied("SCHEDULE_NOT_ACTIVE");
            }
            int currentRuns = current.get(0).get("run_count") instanceof Number count
                    ? count.intValue()
                    : 0;
            Integer configuredMax = current.get(0).get("max_runs") instanceof Number max
                    ? max.intValue()
                    : null;
            if (configuredMax != null && currentRuns >= configuredMax) {
                return TriggerResult.denied("MAX_RUNS_REACHED");
            }
            if (advanceCursor) {
                advanceNextRunAt(tenantId, schedulePid, schedule, Instant.now());
            }
            ProactiveTriggerPolicyService.Decision decision =
                    proactiveTriggerPolicy.evaluateAndClaimSchedule(
                            tenantId, schedule, java.time.Instant.now());
            if (!decision.allowed()) {
                dynamicDataMapper.update(
                        "ab_agent_schedule",
                        Map.of(
                                "last_block_reason", decision.reason(),
                                "updated_at", LocalDateTime.now()),
                        Map.of("pid", schedulePid));
                observationService.publish(
                        tenantId,
                        "schedule_blocked",
                        decision.agentCode(),
                        "agent_schedule",
                        schedulePid,
                        Map.of("reason", decision.reason()));
                log.warn("Proactive schedule blocked: pid={}, reason={}",
                        schedulePid, decision.reason());
                return TriggerResult.denied(decision.reason());
            }
            String templateJson = (String) schedule.get("task_template");
            Map<String, Object> template = templateJson != null && !templateJson.isBlank()
                    ? objectMapper.readValue(templateJson, MAP_TYPE)
                    : Map.of();

            List<Map<String, Object>> claims = jdbcTemplate.queryForList(
                    """
                    UPDATE ab_agent_schedule
                    SET run_count = COALESCE(run_count, 0) + 1,
                        last_run_at = CURRENT_TIMESTAMP,
                        last_block_reason = NULL,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE tenant_id = ?
                      AND pid = ?
                      AND schedule_status = 'active'
                      AND deleted_flag = FALSE
                      AND (max_runs IS NULL OR COALESCE(run_count, 0) < max_runs)
                    RETURNING run_count, max_runs
                    """,
                    tenantId,
                    schedulePid);
            if (claims.size() != 1) {
                return TriggerResult.denied("MAX_RUNS_OR_INACTIVE");
            }
            int nextRunCount = ((Number) claims.get(0).get("run_count")).intValue();
            Integer maxRuns = claims.get(0).get("max_runs") instanceof Number max
                    ? max.intValue()
                    : null;

            String taskPid = UniqueIdGenerator.generate();
            Map<String, Object> task = new HashMap<>();
            task.put("pid", taskPid);
            task.put("tenant_id", tenantId);
            task.put("title", template.getOrDefault("title", "Scheduled: " + schedule.get("title")));
            task.put("description", template.getOrDefault("description", "Auto-created by schedule " + schedulePid));
            task.put("task_status", "todo");
            task.put("task_priority", template.getOrDefault("task_priority", "medium"));
            task.put("assignee_type", "agent");
            task.put("assignee_id", decision.agentCode());
            task.put("mission_id", schedule.get("mission_id"));
            task.put("input_data", objectMapper.writeValueAsString(Map.of(
                    "triggerType", "schedule",
                    "schedulePid", schedulePid,
                    "managerMemberId", Objects.toString(
                            decision.managerMemberId(), ""),
                    "agentReleasePid", Objects.toString(
                            decision.agentReleasePid(), ""))));
            task.put("created_at", LocalDateTime.now());
            task.put("updated_at", LocalDateTime.now());

            dynamicDataMapper.insert("ab_agent_task", task);

            if (maxRuns != null && nextRunCount >= maxRuns) {
                dynamicDataMapper.update("ab_agent_schedule",
                        Map.of("schedule_status", "expired", "updated_at", LocalDateTime.now()),
                        Map.of("pid", schedulePid));
                ScheduledFuture<?> future = scheduledFutures.remove(schedulePid);
                if (future != null) future.cancel(false);
                log.info("Schedule expired (max_runs reached): pid={}", schedulePid);
            }

            String agentCode = (String) task.get("assignee_id");
            if (agentCode != null && !agentCode.isBlank()) {
                // The trigger may create a run; individual write/external/high-risk
                // actions still pass through the normal runtime approval gate. Do
                // not cancel the entire schedule merely because one possible tool
                // is approval-gated.
                agentRunService.executeScheduledTask(
                        tenantId, taskPid, agentCode, schedulePid);
            }

            observationService.publish(tenantId, "schedule_triggered", agentCode, "agent_schedule", schedulePid,
                    Map.of("task_pid", taskPid, "schedule_title", String.valueOf(schedule.get("title"))));
            return TriggerResult.triggered(taskPid);

        } catch (Exception e) {
            log.error("Failed to trigger schedule: pid={}, error={}", schedulePid, e.getMessage(), e);
            observationService.publish(tenantId, "schedule_failed", null, "agent_schedule", schedulePid,
                    Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
            return TriggerResult.denied("TRIGGER_FAILED");
        } finally {
            MetaContext.clear();
        }
    }

    /**
     * Product-visible "Run now" entry. It deliberately reuses the same policy,
     * atomic run claim, task construction, employee principal and durable run
     * path as a clock-triggered occurrence.
     */
    public TriggerResult triggerNow(Long tenantId, String schedulePid) {
        if (tenantId == null || tenantId <= 0L
                || schedulePid == null || schedulePid.isBlank()) {
            return TriggerResult.denied("SCHEDULE_REQUIRED");
        }
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(
                "SELECT * FROM ab_agent_schedule "
                        + "WHERE tenant_id = #{params.tenantId} "
                        + "AND pid = #{params.pid} AND deleted_flag = FALSE",
                Map.of("tenantId", tenantId, "pid", schedulePid));
        if (rows.isEmpty()) {
            return TriggerResult.denied("SCHEDULE_NOT_FOUND");
        }
        Map<String, Object> schedule = rows.get(0);
        if (!"active".equals(schedule.get("schedule_status"))) {
            return TriggerResult.denied("SCHEDULE_NOT_ACTIVE");
        }
        return triggerSchedule(tenantId, schedulePid, schedule, false);
    }

    public record TriggerResult(boolean triggered, String taskPid, String reason) {
        static TriggerResult triggered(String taskPid) {
            return new TriggerResult(true, taskPid, null);
        }

        static TriggerResult denied(String reason) {
            return new TriggerResult(false, null, reason);
        }
    }

    /**
     * Reconciles one missed occurrence when a schedule is loaded after downtime.
     *
     * <p>{@code next_run_at} is the durable cursor. A new schedule initializes
     * the cursor without replaying historical cron occurrences. On a later
     * reload, an overdue cursor is atomically advanced. {@code catch_up_once}
     * dispatches exactly one immediate run; {@code skip} records the decision
     * and waits for the next normal occurrence. Repeated reloads cannot enqueue
     * the same missed occurrence because the compare-and-set update only claims
     * an overdue cursor once.
     */
    private void reconcileMissedRun(
            Long tenantId,
            String schedulePid,
            Map<String, Object> schedule,
            Instant now) {
        Instant next = calculateNextRunAt(schedule, now);
        if (next == null) {
            return;
        }
        Instant persisted = toInstant(schedule.get("next_run_at"));
        if (persisted == null) {
            jdbcTemplate.update(
                    "UPDATE ab_agent_schedule SET next_run_at = ?, updated_at = CURRENT_TIMESTAMP "
                            + "WHERE tenant_id = ? AND pid = ? AND next_run_at IS NULL "
                            + "AND schedule_status = 'active' AND deleted_flag = FALSE",
                    Timestamp.from(next),
                    tenantId,
                    schedulePid);
            return;
        }
        if (persisted.isAfter(now)) {
            return;
        }
        int claimed = jdbcTemplate.update(
                "UPDATE ab_agent_schedule SET next_run_at = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE tenant_id = ? AND pid = ? AND next_run_at <= ? "
                        + "AND schedule_status = 'active' AND deleted_flag = FALSE",
                Timestamp.from(next),
                tenantId,
                schedulePid,
                Timestamp.from(now));
        if (claimed != 1) {
            return;
        }

        String policy = Objects.toString(schedule.get("missed_run_policy"), "skip");
        if ("catch_up_once".equals(policy)) {
            taskScheduler.schedule(
                    () -> triggerSchedule(tenantId, schedulePid, schedule),
                    now.plusMillis(100));
            log.info(
                    "Scheduled one missed-run catch-up: pid={}, missedAt={}, nextAt={}",
                    schedulePid,
                    persisted,
                    next);
            return;
        }
        observationService.publish(
                tenantId,
                "schedule_missed_skipped",
                Objects.toString(schedule.get("agent_code"), null),
                "agent_schedule",
                schedulePid,
                Map.of(
                        "missedAt", persisted.toString(),
                        "nextAt", next.toString(),
                        "policy", policy));
        log.info(
                "Skipped missed schedule occurrence: pid={}, missedAt={}, nextAt={}",
                schedulePid,
                persisted,
                next);
    }

    private void advanceNextRunAt(
            Long tenantId,
            String schedulePid,
            Map<String, Object> schedule,
            Instant now) {
        Instant next = calculateNextRunAt(schedule, now);
        if (next != null) {
            jdbcTemplate.update(
                    "UPDATE ab_agent_schedule SET next_run_at = ?, updated_at = CURRENT_TIMESTAMP "
                            + "WHERE tenant_id = ? AND pid = ?",
                    Timestamp.from(next),
                    tenantId,
                    schedulePid);
        }
    }

    static Instant calculateNextRunAt(
            Map<String, Object> schedule,
            Instant now) {
        if (schedule == null || now == null) {
            return null;
        }
        String scheduleType = Objects.toString(schedule.get("schedule_type"), "");
        if ("cron".equals(scheduleType)) {
            String cron = Objects.toString(schedule.get("cron_expression"), "");
            if (cron.isBlank()) {
                return null;
            }
            ZoneId zone = ZoneId.of(
                    Objects.toString(schedule.get("timezone"), "UTC"));
            ZonedDateTime next = CronExpression.parse(cron).next(now.atZone(zone));
            return next == null ? null : next.toInstant();
        }
        if ("interval".equals(scheduleType)
                && schedule.get("interval_ms") instanceof Number interval
                && interval.longValue() > 0) {
            return now.plusMillis(interval.longValue());
        }
        return null;
    }

    static Instant toInstant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        if (value instanceof ZonedDateTime zonedDateTime) {
            return zonedDateTime.toInstant();
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.toInstant(java.time.ZoneOffset.UTC);
        }
        if (value instanceof Date date) {
            return date.toInstant();
        }
        return null;
    }

    /**
     * Check whether the given agent has any bound tools that require human approval.
     * Uses ToolProviderRegistry to discover the agent's effective tool set.
     */
    public boolean agentHasApprovalRequiredTools(Long tenantId, String agentCode) {
        if (tenantId == null || agentCode == null || agentCode.isBlank()) {
            return false;
        }
        String sql = """
                SELECT COUNT(*) AS cnt
                FROM ab_agent_definition a
                JOIN ab_agent_tool t
                  ON t.tenant_id = a.tenant_id
                 AND t.tool_code IN (
                     SELECT jsonb_array_elements_text(
                         CASE
                             WHEN a.tools IS NULL OR a.tools = '' THEN '[]'::jsonb
                             ELSE a.tools::jsonb
                         END
                     )
                 )
                WHERE a.tenant_id = #{params.tenantId}
                  AND a.agent_code = #{params.agentCode}
                  AND a.status = 'active'
                  AND (a.deleted_flag = FALSE OR a.deleted_flag IS NULL)
                  AND t.tool_status = 'active'
                  AND (t.deleted_flag = FALSE OR t.deleted_flag IS NULL)
                  AND t.requires_approval = TRUE
                """;
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQueryWithoutTenant(
                sql, Map.of("tenantId", tenantId, "agentCode", agentCode));
        if (rows.isEmpty()) {
            return false;
        }
        Object cnt = rows.get(0).get("cnt");
        return cnt instanceof Number n && n.longValue() > 0;
    }

    /**
     * Seed the HEARTBEAT schedule template for a tenant if it does not already exist.
     *
     * <p>The template is created with {@code schedule_status = 'inactive'} so it is
     * not triggered automatically until an operator explicitly enables it.  Operators
     * can activate it via the Mission Control UI or by calling
     * {@code PATCH /api/agent/schedules/{pid}/activate}.
     *
     * <p>This method is idempotent — it checks for an existing record by title before
     * inserting.  Safe to call from a tenant bootstrap flow.
     *
     * @param tenantId the tenant that should own the template
     * @return the pid of the newly created (or already-existing) schedule record
     */
    public String seedHeartbeatTemplate(Long tenantId) {
        // Check whether a heartbeat schedule already exists for this tenant
        String checkSql = "SELECT pid FROM ab_agent_schedule " +
                "WHERE tenant_id = ? AND title = ? AND deleted_flag = FALSE LIMIT 1";
        List<String> existing = jdbcTemplate.queryForList(checkSql, String.class, tenantId, HEARTBEAT_TITLE);
        if (!existing.isEmpty()) {
            log.debug("HEARTBEAT schedule already exists for tenant={}: pid={}", tenantId, existing.get(0));
            return existing.get(0);
        }

        String pid = UniqueIdGenerator.generate();
        String templateJson = "{" +
                "\"title\":\"ACP Heartbeat Check\"," +
                "\"description\":\"Proactive health check: timeout approvals, stale tasks, failed runs, memory overload\"," +
                "\"task_priority\":\"HIGH\"" +
                "}";

        jdbcTemplate.update(
                "INSERT INTO ab_agent_schedule " +
                "(pid, tenant_id, title, description, schedule_type, cron_expression, " +
                " task_template, schedule_status, timezone, run_count, deleted_flag, " +
                " created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, 'inactive', 'Asia/Shanghai', 0, FALSE, " +
                " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                pid,
                tenantId,
                HEARTBEAT_TITLE,
                "Runs every 30 minutes to check for timeout approvals, stale tasks, " +
                        "recent failures, and memory overload. " +
                        "Change schedule_status to ACTIVE to enable.",
                HEARTBEAT_SCHEDULE_TYPE,
                HEARTBEAT_CRON,
                templateJson
        );

        log.info("Seeded HEARTBEAT schedule template for tenant={}: pid={}", tenantId, pid);
        return pid;
    }
}
