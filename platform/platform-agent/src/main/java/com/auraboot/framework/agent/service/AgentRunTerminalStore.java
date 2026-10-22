package com.auraboot.framework.agent.service;

import com.auraboot.framework.behavior.outcome.BehaviorOutcomeEvent;
import com.auraboot.framework.behavior.outcome.BehaviorOutcomePublisher;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Commits run/task persistence boundaries and authoritative terminal execution facts. */
@Service
@Slf4j
@RequiredArgsConstructor
public class AgentRunTerminalStore {
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<com.auraboot.framework.behavior.service.AnalyticsExecutionSourceService> analyticsSources;
    private final JdbcTemplate jdbc;
    private final DynamicDataMapper data;
    private final BehaviorOutcomePublisher outcomes;

    /** Creates a run and updates its owning task atomically; admission is not an execution-start fact. */
    @Transactional
    public void create(Long tenantId, String runPid, String taskPid,
                       Map<String, Object> run, Map<String, Object> taskUpdate) {
        createScoped(tenantId, runPid, taskPid, run, taskUpdate, false);
    }

    /** Admits only the first run, including recovery after task creation without dispatch. */
    @Transactional
    public void createInitialAnalyticsRun(Long tenantId, String runPid, String taskPid,
                                          Map<String, Object> run, Map<String, Object> taskUpdate) {
        createScoped(tenantId, runPid, taskPid, run, taskUpdate, true);
    }

    public static final class AnalyticsRunAlreadyAdmitted extends RuntimeException {
        public AnalyticsRunAlreadyAdmitted() {
            super("This execution request already has a task with a run. Check its current status before retrying execution.");
        }
    }

    private void createScoped(Long tenantId, String runPid, String taskPid,
                              Map<String, Object> run, Map<String, Object> taskUpdate, boolean initialAnalytics) {
        if (!java.util.Objects.equals(tenantId, run.get("tenant_id"))
                || !java.util.Objects.equals(runPid, run.get("pid"))
                || !java.util.Objects.equals(taskPid, run.get("task_id"))) {
            throw new IllegalArgumentException("Run identity does not match its persistence scope");
        }
        List<Map<String, Object>> tasks = jdbc.queryForList("""
                SELECT pid FROM ab_agent_task
                WHERE tenant_id = ? AND pid = ? AND deleted_flag = FALSE
                FOR UPDATE
                """, tenantId, taskPid);
        if (tasks.size() != 1) throw new IllegalStateException("Run task is unavailable");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Run creation requires a transaction");
        }
        if (initialAnalytics) {
            var bindings = jdbc.queryForList("""
                    SELECT task_pid FROM ab_analytics_task_execution WHERE tenant_id = ? AND task_pid = ?
                    """, tenantId, taskPid);
            if (bindings.size() != 1) throw new IllegalStateException("Analytics task provenance is unavailable");
            // The owning task lock serializes this check with every run insertion.
            var admitted = jdbc.queryForList("""
                    SELECT pid FROM ab_agent_run WHERE tenant_id = ? AND task_id = ? LIMIT 1
                    """, tenantId, taskPid);
            if (!admitted.isEmpty()) throw new AnalyticsRunAlreadyAdmitted();
        }
        if (data.insert("ab_agent_run", run) != 1
                || data.update("ab_agent_task", taskUpdate,
                Map.of("tenant_id", tenantId, "pid", taskPid)) != 1) {
            throw new IllegalStateException("Run creation did not persist exactly one run/task pair");
        }
    }

    /** Records admission to plan execution, independently from the earlier run-row creation. */
    @Transactional
    public void started(Long tenantId, String runPid, String taskPid) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT r.actor_user_id, r.principal_type, r.run_status, t.description, t.input_data,
                       a.binding AS analytics_binding, a.goal AS analytics_goal
                FROM ab_agent_run r JOIN ab_agent_task t
                  ON t.pid = r.task_id AND t.tenant_id = r.tenant_id
                LEFT JOIN ab_analytics_task_execution a ON a.tenant_id = t.tenant_id AND a.task_pid = t.pid
                WHERE r.tenant_id = ? AND r.pid = ? AND t.pid = ? AND t.deleted_flag = FALSE
                FOR UPDATE OF r, t
                """, tenantId, runPid, taskPid);
        if (rows.size() != 1) throw new IllegalStateException("Run/task relationship is unavailable");
        Map<String, Object> row = rows.get(0);
        if (!"running".equals(row.get("run_status"))) {
            throw new IllegalStateException("Run is not admitted for execution");
        }
        Object actor = row.get("actor_user_id");
        Map<String, Object> props = new java.util.LinkedHashMap<>();
        props.put("taskPid", taskPid);
        props.put("principalType", row.get("principal_type") == null ? "unknown" : row.get("principal_type"));
        String analysisId = null;
        String adoptionEventId = null;
        try {
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            var input = json.readTree(row.get("input_data") == null ? "{}" : row.get("input_data").toString());
            var binding = input.get("analyticsExecution");
            Object authoritativeValue = row.get("analytics_binding");
            if (binding != null || authoritativeValue != null) {
                if (binding == null || authoritativeValue == null
                        || !json.readTree(authoritativeValue.toString()).equals(binding)) {
                    throw new IllegalStateException("Analytics task has no matching server-owned execution binding");
                }
                var source = analyticsSources.getObject().resolve(binding.path("adoptionPid").asText());
                if (!json.valueToTree(source.binding()).equals(binding)
                        || !java.util.Objects.equals(source.goal(), row.get("analytics_goal"))
                        || !java.util.Objects.equals(source.goal(), row.get("description"))
                        || !java.util.Objects.equals(source.goal(), input.path("userMessage").asText())
                        || !java.util.Objects.equals(String.valueOf(actor), String.valueOf(source.binding().get("actorUserId")))) {
                    throw new IllegalStateException("Analytics task binding changed before execution");
                }
                props.put("analyticsExecution", source.binding());
                analysisId = String.valueOf(source.binding().get("analysisId"));
                adoptionEventId = UUID.nameUUIDFromBytes((tenantId + ":" + actor + ":analytics_suggestion_adopted:"
                        + source.binding().get("adoptionPid")).getBytes(StandardCharsets.UTF_8)).toString();
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalStateException("Task execution input is invalid", invalid);
        }
        outcomes.publish(BehaviorOutcomeEvent.builder()
                .tenantId(tenantId).userId(actor == null ? null : Long.valueOf(actor.toString()))
                .eventId(UUID.nameUUIDFromBytes((tenantId + ":" + runPid + ":started")
                        .getBytes(StandardCharsets.UTF_8)).toString())
                .eventName("agent_execution_started").runId(runPid).interactionId(analysisId).causedByEventId(adoptionEventId)
                .targetType("agent_run").targetKey(runPid)
                .props(props)
                .build());
    }

    @Transactional
    public boolean complete(Long tenantId, String runPid, String taskPid,
                            Map<String, Object> runUpdate, Map<String, Object> taskUpdate,
                            Runnable committedSignal) {
        return completeScoped(tenantId, runPid, taskPid, runUpdate, taskUpdate, committedSignal, false);
    }

    /** Completes an approval-paused run without overwriting a concurrently resumed run. */
    @Transactional
    public boolean failPendingApproval(Long tenantId, String runPid, String taskPid, String reason) {
        var now = java.time.LocalDateTime.now();
        return completeScoped(tenantId, runPid, taskPid,
                Map.of("run_status", "failed", "error_message", reason, "completed_at", now, "updated_at", now),
                Map.of("task_status", "blocked", "updated_at", now), () -> {}, true);
    }

    private boolean completeScoped(Long tenantId, String runPid, String taskPid,
                                   Map<String, Object> runUpdate, Map<String, Object> taskUpdate,
                                   Runnable committedSignal, boolean pendingApproval) {
        String status = String.valueOf(runUpdate.get("run_status"));
        String taskStatus = switch (status) {
            case "success" -> "done";
            case "failed" -> "blocked";
            case "cancelled" -> "cancelled";
            default -> "";
        };
        if (taskStatus.isEmpty() || !taskStatus.equals(taskUpdate.get("task_status"))) {
            throw new IllegalArgumentException("Invalid run/task completion status pair");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT r.run_status, r.actor_user_id, r.principal_type,
                       started.event_id AS started_event_id,
                       started.interaction_id AS started_interaction_id
                FROM ab_agent_run r JOIN ab_agent_task t
                  ON t.pid = r.task_id AND t.tenant_id = r.tenant_id
                LEFT JOIN LATERAL (
                    SELECT o.event_id, o.interaction_id FROM ab_behavior_outcome_outbox o
                    WHERE o.tenant_id = r.tenant_id AND o.run_id = r.pid
                      AND o.event_name = 'agent_execution_started'
                    ORDER BY o.id LIMIT 1
                ) started ON TRUE
                WHERE r.tenant_id = ? AND r.pid = ? AND t.pid = ?
                  AND t.deleted_flag = FALSE
                FOR UPDATE OF r, t
                """, tenantId, runPid, taskPid);
        if (rows.size() != 1) throw new IllegalStateException("Run/task relationship is unavailable");
        Map<String, Object> row = rows.get(0);
        String previous = String.valueOf(row.get("run_status"));
        if (Set.of("success", "failed", "cancelled").contains(previous)) return false;
        if ("cancelled".equals(status) && !"running".equals(previous)) return false;
        if (pendingApproval) {
            if (!"pending".equals(previous)) return false;
        } else if (!"running".equals(previous)) {
            throw new IllegalStateException("Run is not executing");
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Run completion requires a transaction");
        }
        if (data.update("ab_agent_run", runUpdate, Map.of("tenant_id", tenantId, "pid", runPid)) != 1
                || data.update("ab_agent_task", taskUpdate, Map.of("tenant_id", tenantId, "pid", taskPid)) != 1) {
            throw new IllegalStateException("Run/task completion did not update exactly one pair");
        }
        Object actor = row.get("actor_user_id");
        Long actorId = actor == null ? null : Long.valueOf(actor.toString());
        String eventId = UUID.nameUUIDFromBytes((tenantId + ":" + runPid + ":terminal")
                .getBytes(StandardCharsets.UTF_8)).toString();
        boolean inserted = outcomes.publish(BehaviorOutcomeEvent.builder()
                .tenantId(tenantId).userId(actorId).eventId(eventId)
                .eventName("agent_execution_completed").runId(runPid)
                .causedByEventId((String) row.get("started_event_id"))
                .interactionId((String) row.get("started_interaction_id"))
                .targetType("agent_run").targetKey(runPid)
                .props(Map.of("status", status, "taskPid", taskPid,
                        "principalType", row.get("principal_type") == null ? "unknown" : row.get("principal_type")))
                .build());
        if (!inserted) throw new IllegalStateException("Execution outcome already exists for a running run");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    committedSignal.run();
                } catch (RuntimeException error) {
                    // State is committed; polling remains authoritative if the in-process signal fails.
                    log.error("Committed run completion signal failed: runPid={}", runPid, error);
                }
            }
        });
        return true;
    }
}
