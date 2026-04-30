package com.auraboot.framework.agent.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Spawn a child {@code ab_agent_run} from a parent run.
 *
 * <p>This is the "authorisation primitive" referenced by
 * {@link InterruptDispatcher} when a user-interrupt classifies as
 * {@link InterruptClassifier#INSERT_SUBTASK}. It is also the integration
 * surface the §6.10 {@code delegate_task} tool will call once it lands —
 * keeping the spawn logic in one place avoids two divergent code paths
 * for "fork a child run".
 *
 * <p>What this DOES (P0 scope):
 * <ul>
 *   <li>Seed an {@code ab_agent_task} row carrying the subtask message
 *       (assignee_type='agent', parent_id=parent task), then an
 *       {@code ab_agent_run} row with {@code parent_run_id} set and
 *       {@code subtask_origin='interrupt_subtask'} (or whatever audit
 *       label the caller passes). Both inserts run inside a single
 *       transaction so a failure of the second insert rolls the first
 *       back — no orphan {@code ab_agent_task} rows.</li>
 *   <li>Inherit tenant + {@code created_by} from the parent run row so
 *       the user run-tree query joins parent ↔ child by the same owner
 *       even when the spawn is triggered from a system-user context
 *       (e.g. {@code delegate_task} tool invocation).</li>
 *   <li>Inherit {@code agent_id} from the parent run — there is no
 *       fallback "default agent" because {@code ab_agent_run.agent_id}
 *       is {@code NOT NULL}, so a missing parent agent_id is a schema
 *       invariant violation and surfaces as an error.</li>
 *   <li>Refuse to spawn under a non-running parent (cancelled / failed /
 *       timeout / success). The parent row is selected {@code FOR SHARE}
 *       to prevent a TOCTOU race where the parent transitions to a
 *       terminal state between our SELECT and the child INSERT.</li>
 *   <li>Return the child run pid so callers (interrupt log writer,
 *       observation publisher) can cross-link.</li>
 * </ul>
 *
 * <p>What this does NOT do (left for P1 per task brief):
 * <ul>
 *   <li>Block the parent on child completion (no synchronous join).</li>
 *   <li>Cross-tenant or cross-user spawn — {@code tenantId} arg is the
 *       caller-supplied scope; SubAgentRunner refuses {@code null}.</li>
 *   <li>Trigger the actual LLM execution loop. The child run row sits in
 *       {@code run_status='running'} and is the responsibility of the
 *       async dispatcher (today: {@link AgentRunService#executeTask}).
 *       Tests pin this by inserting the row + flipping it manually so
 *       the unit-of-work is "row exists with correct parent linkage".</li>
 *   <li>Publish completion events — that already happens inside
 *       {@code AgentRunService} for any run that reaches terminal state,
 *       and the child row carries the same publisher contract as a
 *       root-initiated run.</li>
 * </ul>
 *
 * <p>Red-line compliance:
 * <ul>
 *   <li>No fallback / retry — DB errors surface to caller.</li>
 *   <li>No synthetic placeholder agentCode — caller decides via config.</li>
 *   <li>No cross-tenant escalation: tenantId mismatch with parent row's
 *       tenant is treated as caller bug, not silently coerced.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubAgentRunner {

    private final JdbcTemplate jdbcTemplate;

    @Data
    @Builder
    public static class SpawnResult {
        /** Newly-created {@code ab_agent_run.pid} for the child run. */
        private String childRunPid;
        /** Newly-created {@code ab_agent_task.pid} backing the child run. */
        private String childTaskPid;
        /** Audit label written into ab_agent_run.subtask_origin. */
        private String origin;
    }

    /**
     * Spawn a child run associated with {@code parentRunPid}.
     *
     * <p>Both DB writes (ab_agent_task INSERT + ab_agent_run INSERT) execute
     * in a single transaction; a failure of the second insert rolls back the
     * first to prevent orphan task rows pointing at a non-existent run.
     *
     * @param tenantId      non-null tenant — must match the parent run's tenant.
     * @param parentRunPid  non-null parent {@code ab_agent_run.pid}; the row
     *                      must exist (verified by FK-lookup, not by an actual
     *                      DB constraint — schema keeps {@code parent_run_id}
     *                      FK-free for the same reason ab_agent_run.task_id
     *                      is FK-free: ingestion paths sometimes orphan it),
     *                      AND must be in {@code run_status='running'} —
     *                      spawning under a cancelled / failed / completed
     *                      parent is an {@link IllegalStateException}.
     * @param sessionId     non-null session id from the gateway; written into
     *                      the child task description for traceability.
     * @param subtaskMessage non-null user message that motivated the subtask;
     *                       becomes the child task title (truncated to fit).
     * @param origin        non-null audit label, e.g. "interrupt_subtask".
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public SpawnResult spawn(Long tenantId,
                             String parentRunPid,
                             String sessionId,
                             String subtaskMessage,
                             String origin) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId required for sub-agent spawn");
        }
        if (parentRunPid == null || parentRunPid.isBlank()) {
            throw new IllegalArgumentException("parentRunPid required for sub-agent spawn");
        }
        if (subtaskMessage == null || subtaskMessage.isBlank()) {
            throw new IllegalArgumentException("subtaskMessage required for sub-agent spawn");
        }
        if (origin == null || origin.isBlank()) {
            throw new IllegalArgumentException("origin label required for sub-agent spawn");
        }

        // Verify parent row exists, lock it FOR SHARE to prevent it from
        // transitioning to a terminal state between our guard and the child
        // INSERT, and capture the audit fields we need (tenant for the
        // cross-tenant check, agent_id for the child run, created_by for the
        // child's owner — must mirror the parent's owner so user run-tree
        // queries don't fork the lineage when the spawn caller is a system
        // user, e.g. the delegate_task tool).
        List<Map<String, Object>> parentRows = jdbcTemplate.queryForList(
                "SELECT tenant_id, run_status, agent_id, task_id, created_by " +
                        "FROM ab_agent_run WHERE pid = ? FOR SHARE",
                parentRunPid);
        if (parentRows.isEmpty()) {
            throw new IllegalStateException("Parent run not found: " + parentRunPid);
        }
        Map<String, Object> parent = parentRows.get(0);
        Long parentTenant = parent.get("tenant_id") == null
                ? null : ((Number) parent.get("tenant_id")).longValue();
        if (!tenantId.equals(parentTenant)) {
            throw new IllegalStateException(
                    "Parent run tenant " + parentTenant + " does not match caller tenant " + tenantId);
        }

        String parentStatus = (String) parent.get("run_status");
        if (!"running".equals(parentStatus)) {
            throw new IllegalStateException(
                    "cannot spawn under non-running parent: status=" + parentStatus
                            + " parent_run=" + parentRunPid);
        }

        String agentCode = (String) parent.get("agent_id");
        if (agentCode == null || agentCode.isBlank()) {
            // ab_agent_run.agent_id is NOT NULL, so reaching here means the
            // schema invariant was violated by a direct DB write. No fallback.
            throw new IllegalStateException(
                    "parent run agent_id is null — schema invariant violated for parent_run=" + parentRunPid);
        }

        String childTaskPid = UniqueIdGenerator.generate();
        String childRunPid = UniqueIdGenerator.generate();
        String parentTaskPid = (String) parent.get("task_id");

        // created_by must mirror the parent run, NOT MetaContext.getCurrentUserId().
        // Spawn paths can be invoked from system-user contexts (delegate_task tool,
        // scheduled_split) where MetaContext is the platform principal; if we
        // wrote that into the child row, joins like
        //   "all runs owned by user U" → would skip the child even though it
        // logically belongs to U's parent.
        Long createdBy = parent.get("created_by") == null
                ? null : ((Number) parent.get("created_by")).longValue();
        // Fallback to the MetaContext user only when the parent has no owner
        // (legacy seed rows or system-initiated parent). This is not the
        // common path; we accept null too (NOT NULL is not enforced on
        // ab_agent_run.created_by).
        if (createdBy == null) {
            createdBy = MetaContext.getCurrentUserId();
        }
        String taskTitle = truncate(subtaskMessage, 480);

        // 1) Seed task row first — ab_agent_run.task_id must reference an
        //    ab_agent_task pid by convention (no FK enforces this, but the
        //    completeRun() / dispatch path expects a row to exist).
        jdbcTemplate.update(
                "INSERT INTO ab_agent_task (pid, tenant_id, parent_id, title, description, " +
                        " task_status, task_priority, assignee_type, assignee_id, " +
                        " input_data, started_at, created_at, updated_at, created_by, updated_by) " +
                        "VALUES (?, ?, ?, ?, ?, 'in_progress', 'medium', 'agent', ?, ?, " +
                        "        NOW(), NOW(), NOW(), ?, ?)",
                childTaskPid, tenantId, parentTaskPid,
                taskTitle,
                "Subtask spawned from interrupt (session=" + sessionId + ", parent_run=" + parentRunPid + ")",
                agentCode, subtaskMessage,
                createdBy, createdBy);

        // 2) Seed run row pointing at the new task + parent run. We DO NOT
        //    set run_status='queued' here — running is the right status for
        //    a freshly-spawned run that the executor is expected to pick up.
        //    (Mirrors RunLifecycleService.createRunRecord.) Failure here
        //    rolls back the task insert above (single @Transactional unit).
        jdbcTemplate.update(
                "INSERT INTO ab_agent_run (pid, tenant_id, task_id, agent_id, run_status, " +
                        " parent_run_id, subtask_origin, " +
                        " input_tokens, output_tokens, total_cost, " +
                        " started_at, created_at, updated_at, created_by, updated_by) " +
                        "VALUES (?, ?, ?, ?, 'running', ?, ?, 0, 0, 0, " +
                        "        NOW(), NOW(), NOW(), ?, ?)",
                childRunPid, tenantId, childTaskPid, agentCode,
                parentRunPid, origin,
                createdBy, createdBy);

        log.info("SubAgentRunner.spawn: parent={} → child={} (task={}, agent={}, origin={})",
                parentRunPid, childRunPid, childTaskPid, agentCode, origin);

        return SpawnResult.builder()
                .childRunPid(childRunPid)
                .childTaskPid(childTaskPid)
                .origin(origin)
                .build();
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
