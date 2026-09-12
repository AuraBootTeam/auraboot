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
    private final JdbcTemplate jdbc;
    private final DynamicDataMapper data;
    private final BehaviorOutcomePublisher outcomes;

    /** Creates a run and updates its owning task atomically; admission is not an execution-start fact. */
    @Transactional
    public void create(Long tenantId, String runPid, String taskPid,
                       Map<String, Object> run, Map<String, Object> taskUpdate) {
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
        if (data.insert("ab_agent_run", run) != 1
                || data.update("ab_agent_task", taskUpdate,
                Map.of("tenant_id", tenantId, "pid", taskPid)) != 1) {
            throw new IllegalStateException("Run creation did not persist exactly one run/task pair");
        }
    }

    @Transactional
    public boolean complete(Long tenantId, String runPid, String taskPid,
                            Map<String, Object> runUpdate, Map<String, Object> taskUpdate,
                            Runnable committedSignal) {
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
                SELECT r.run_status, r.actor_user_id, r.principal_type
                FROM ab_agent_run r JOIN ab_agent_task t
                  ON t.pid = r.task_id AND t.tenant_id = r.tenant_id
                WHERE r.tenant_id = ? AND r.pid = ? AND t.pid = ?
                  AND t.deleted_flag = FALSE
                FOR UPDATE OF r, t
                """, tenantId, runPid, taskPid);
        if (rows.size() != 1) throw new IllegalStateException("Run/task relationship is unavailable");
        Map<String, Object> row = rows.get(0);
        String previous = String.valueOf(row.get("run_status"));
        if (Set.of("success", "failed", "cancelled").contains(previous)) return false;
        if ("cancelled".equals(status) && !"running".equals(previous)) return false;
        if (!"running".equals(previous)) throw new IllegalStateException("Run is not executing");
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
