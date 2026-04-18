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
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
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
    private final AgentApprovalGateService approvalGateService;

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
            ScheduledFuture<?> future = taskScheduler.schedule(
                    () -> triggerSchedule(tenantId, pid, schedule),
                    new CronTrigger(cronExpr));
            scheduledFutures.put(pid, future);
            log.info("Registered CRON schedule: pid={}, cron={}", pid, cronExpr);
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
    }

    private void triggerSchedule(Long tenantId, String schedulePid, Map<String, Object> schedule) {
        log.info("Schedule triggered: pid={}, tenant={}", schedulePid, tenantId);
        MetaContext.setContext(tenantId, 0L, null, "system");
        try {
            String templateJson = (String) schedule.get("task_template");
            Map<String, Object> template = templateJson != null && !templateJson.isBlank()
                    ? objectMapper.readValue(templateJson, MAP_TYPE)
                    : Map.of();

            String taskPid = UniqueIdGenerator.generate();
            Map<String, Object> task = new HashMap<>();
            task.put("pid", taskPid);
            task.put("tenant_id", tenantId);
            task.put("title", template.getOrDefault("title", "Scheduled: " + schedule.get("title")));
            task.put("description", template.getOrDefault("description", "Auto-created by schedule " + schedulePid));
            task.put("task_status", "todo");
            task.put("task_priority", template.getOrDefault("task_priority", "medium"));
            task.put("assignee_type", "agent");
            task.put("assignee_id", template.getOrDefault("assignee_id", template.getOrDefault("agent_code", "")));
            task.put("mission_id", schedule.get("mission_id"));
            task.put("created_at", LocalDateTime.now());
            task.put("updated_at", LocalDateTime.now());

            dynamicDataMapper.insert("ab_agent_task", task);

            Map<String, Object> scheduleUpdate = new HashMap<>();
            scheduleUpdate.put("last_run_at", LocalDateTime.now());
            scheduleUpdate.put("run_count", ((Number) schedule.getOrDefault("run_count", 0)).intValue() + 1);
            scheduleUpdate.put("updated_at", LocalDateTime.now());
            dynamicDataMapper.update("ab_agent_schedule", scheduleUpdate, Map.of("pid", schedulePid));

            Integer maxRuns = schedule.get("max_runs") != null ? ((Number) schedule.get("max_runs")).intValue() : null;
            if (maxRuns != null && ((Number) schedule.getOrDefault("run_count", 0)).intValue() + 1 >= maxRuns) {
                dynamicDataMapper.update("ab_agent_schedule",
                        Map.of("schedule_status", "expired", "updated_at", LocalDateTime.now()),
                        Map.of("pid", schedulePid));
                ScheduledFuture<?> future = scheduledFutures.remove(schedulePid);
                if (future != null) future.cancel(false);
                log.info("Schedule expired (max_runs reached): pid={}", schedulePid);
            }

            String agentCode = (String) task.get("assignee_id");
            if (agentCode != null && !agentCode.isBlank()) {
                // P0 fix: 同时检查 tool-level requires_approval 和 agent-level approval policy。
                // 原实现仅检查 t.requires_approval=TRUE，绕过所有 policy-level gate（agent_code
                // 模式 / cost_threshold 等）。这是 schedule 旁路审批的主根因。
                boolean toolGated  = agentHasApprovalRequiredTools(tenantId, agentCode);
                boolean policyGated = approvalGateService.agentHasMatchingPolicy(tenantId, agentCode);
                if (toolGated || policyGated) {
                    log.warn("Scheduled run for agent '{}' skipped: blocked by approval gate " +
                            "(tool_level={}, policy_level={}). Use manual dispatch instead. schedule_pid={}",
                            agentCode, toolGated, policyGated, schedulePid);
                    dynamicDataMapper.update("ab_agent_task",
                            Map.of("task_status", "cancelled", "updated_at", LocalDateTime.now()),
                            Map.of("pid", taskPid));
                    return;
                }
                agentRunService.executeTask(tenantId, taskPid, agentCode);
            }

            observationService.publish(tenantId, "schedule_triggered", agentCode, "agent_schedule", schedulePid,
                    Map.of("task_pid", taskPid, "schedule_title", String.valueOf(schedule.get("title"))));

        } catch (Exception e) {
            log.error("Failed to trigger schedule: pid={}, error={}", schedulePid, e.getMessage(), e);
            observationService.publish(tenantId, "schedule_failed", null, "agent_schedule", schedulePid,
                    Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"));
        } finally {
            MetaContext.clear();
        }
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
