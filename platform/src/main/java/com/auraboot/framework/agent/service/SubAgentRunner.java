package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.crosstenant.CrossTenantAclDeniedException;
import com.auraboot.framework.agent.crosstenant.CrossTenantAclService;
import com.auraboot.framework.agent.crosstenant.CrossTenantDecision;
import com.auraboot.framework.agent.crosstenant.CrossTenantGrantType;
import com.auraboot.framework.agent.crosstenant.CrossTenantSpawnAuditWriter;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
 * <p>What this DOES (P1 — multi-agent execute wiring):
 * <ul>
 *   <li>After the spawn transaction commits, register a
 *       {@link TransactionSynchronization#afterCommit()} hook that submits
 *       a fire-and-forget call to
 *       {@link AgentRunService#executeTaskForExistingRun} so the child
 *       row's LLM loop actually starts executing. The dispatch is async
 *       (executeTaskForExistingRun is {@code @Async}); the parent does NOT
 *       block — completion is observed via {@link SessionEndedEvent} which
 *       {@link ParentJoinService} bridges into {@code ChildRunCompletedEvent}.</li>
 *   <li>If no transaction is active (caller is mid-rollback or in a unit
 *       test bypassing TX), fall back to a direct dispatch — the @Async
 *       executor still runs the LLM loop on a worker thread.</li>
 * </ul>
 *
 * <p>What this does NOT do (deferred):
 * <ul>
 *   <li>Block the parent on child completion (no synchronous join — P2).</li>
 *   <li>Cross-tenant or cross-user spawn — {@code tenantId} arg is the
 *       caller-supplied scope; SubAgentRunner refuses {@code null}.</li>
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
public class SubAgentRunner {

    private final JdbcTemplate jdbcTemplate;
    /**
     * Lazy provider for {@link AgentRunService} to avoid a hard circular
     * dependency: AgentRunService → (eventually, via dispatchChildTasks
     * or similar) → SubAgentRunner. Resolved on first use after Spring
     * has finished wiring all beans.
     */
    private final ObjectProvider<AgentRunService> agentRunServiceProvider;
    private final CrossTenantAclService crossTenantAclService;
    private final CrossTenantSpawnAuditWriter crossTenantAuditWriter;

    @Autowired
    public SubAgentRunner(JdbcTemplate jdbcTemplate,
                          ObjectProvider<AgentRunService> agentRunServiceProvider,
                          CrossTenantAclService crossTenantAclService,
                          CrossTenantSpawnAuditWriter crossTenantAuditWriter) {
        this.jdbcTemplate = jdbcTemplate;
        this.agentRunServiceProvider = agentRunServiceProvider;
        this.crossTenantAclService = crossTenantAclService;
        this.crossTenantAuditWriter = crossTenantAuditWriter;
    }

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
        // Cross-tenant ACL gate. Same-tenant fast path is unchanged: the
        // CrossTenantAclService still answers "allowed" for parent==child
        // but we skip the call entirely to keep the per-spawn JDBC budget
        // identical to the pre-C.2 baseline.
        //
        // Cross-tenant: consult the grant table. Every decision (allowed or
        // denied) writes one row to ab_cross_tenant_spawn_audit. Denied
        // decisions throw CrossTenantAclDeniedException carrying the
        // structured (parent, child, decision) tuple so PlatformToolProvider
        // can convert into a tool-error per Q11.
        // Cross-tenant ACL — null when same-tenant (fast path), non-null and
        // allowed when grant exists. Denied path throws above the local
        // assignment so we never carry a denied decision past this block.
        CrossTenantDecision crossTenantAllowedDecision = null;
        if (!java.util.Objects.equals(tenantId, parentTenant)) {
            CrossTenantDecision decision = crossTenantAclService.evaluate(
                    parentTenant, tenantId, CrossTenantGrantType.SPAWN_SUB_AGENT);
            if (!decision.isAllowed()) {
                crossTenantAuditWriter.write(
                        /* grantId */ null,
                        parentTenant,
                        tenantId,
                        parentRunPid,
                        /* childRunPid */ null,
                        decision.code(),
                        decision.reason());
                throw new CrossTenantAclDeniedException(parentTenant, tenantId, decision);
            }
            crossTenantAllowedDecision = decision;
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

        // Cross-tenant audit: write the "allowed" row only after the child
        // run has been seeded so child_run_pid is populated. The writer uses
        // REQUIRES_NEW so the audit row is independent of the surrounding TX.
        if (crossTenantAllowedDecision != null) {
            crossTenantAuditWriter.write(
                    crossTenantAllowedDecision.grantId(),
                    parentTenant,
                    tenantId,
                    parentRunPid,
                    childRunPid,
                    CrossTenantDecision.ALLOWED,
                    /* errorMessage */ null);
        }

        // P1: After the spawn transaction commits, fire-and-forget the LLM
        // execution loop on the freshly-seeded child run. We register a
        // TransactionSynchronization so the @Async dispatch happens AFTER the
        // INSERTs are visible to other DB sessions (otherwise the executor
        // thread could read its own row before commit lands).
        //
        // If we are not running inside a managed transaction (synchronization
        // not active), fall back to a direct dispatch — this is the unit-test
        // path where REQUIRED transactions exist but the test framework still
        // lets us see writes via REPEATABLE_READ. The executor itself is
        // marked @Async so it runs on a worker thread either way.
        final Long capturedTenant = tenantId;
        final String capturedTaskPid = childTaskPid;
        final String capturedAgent = agentCode;
        final String capturedRunPid = childRunPid;
        Runnable dispatch = () -> {
            try {
                AgentRunService runService = agentRunServiceProvider.getIfAvailable();
                if (runService == null) {
                    log.warn("SubAgentRunner.spawn: AgentRunService bean unavailable; "
                            + "child run {} will not execute (parent={})", capturedRunPid, parentRunPid);
                    return;
                }
                runService.executeTaskForExistingRun(
                        capturedTenant, capturedTaskPid, capturedAgent, capturedRunPid);
            } catch (Exception ex) {
                // Don't propagate — the spawn transaction has already committed.
                // Failure to dispatch is logged so operators can investigate;
                // the child row stays in 'running' until the heartbeat / orphan
                // cron sweeps it.
                log.error("SubAgentRunner.spawn: async dispatch failed for child run {} (parent={}): {}",
                        capturedRunPid, parentRunPid, ex.getMessage(), ex);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch.run();
                }
            });
        } else {
            // No active TX — call directly. @Async on executeTaskForExistingRun
            // ensures the LLM loop still runs on a worker thread.
            dispatch.run();
        }

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
