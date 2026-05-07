package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.controller.InterruptController;
import com.auraboot.framework.agent.memory.SessionEndedEvent;
import com.auraboot.framework.agent.service.InterruptClassifier;
import com.auraboot.framework.agent.service.InterruptDispatcher;
import com.auraboot.framework.agent.service.SubAgentRunner;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P0-6: ACP Multi-Agent Spawn — InterruptDispatcher.insert_subtask wires
 * to {@link SubAgentRunner} to actually fork a child {@code ab_agent_run}
 * (parent_run_id linked + subtask_origin='interrupt_subtask') instead of
 * just stamping {@code action_taken='subtask_enqueued'} and dropping the
 * intent.
 *
 * <p>Scope guarded by AGENTS.md:
 * <ul>
 *   <li>parent does NOT block on child (no synchronous join — that's P1).</li>
 *   <li>child inherits tenant + user from parent; no cross-tenant policy.</li>
 *   <li>SessionEndedEvent publication on child terminal state is the
 *       observable hook the parent run can listen to (also P1 wiring).</li>
 * </ul>
 *
 * <p>Cases covered (per task brief):
 * <ul>
 *   <li>A — POST /interrupt with INSERT_SUBTASK keyword spawns a child run row
 *           in ab_agent_run with parent_run_id = active run pid.</li>
 *   <li>B — DispatchResult exposes the spawned child runId (non-null).</li>
 *   <li>C — Single ab_agent_interrupt_log row links to the new child via
 *           subtask_run_id (FK style, no actual constraint).</li>
 *   <li>D — Concurrent subtasks under the same parent each get a distinct
 *           child run, all carrying the same parent_run_id (no shared row).</li>
 * </ul>
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("Multi-Agent Spawn (P0-6 — InterruptDispatcher.insert_subtask)")
class SubAgentRunnerIntegrationTest extends BaseIntegrationTest {

    @Autowired private InterruptClassifier classifier;
    @Autowired private InterruptDispatcher dispatcher;
    @Autowired private InterruptController controller;
    @Autowired private SubAgentRunner subAgentRunner;
    @Autowired private ApplicationEventPublisher eventPublisher;
    // SpyBean so G4 can stub the second jdbcTemplate.update(...) to throw and
    // assert the surrounding @Transactional unit on SubAgentRunner.spawn rolls
    // the first INSERT back. SpyBean delegates to the real bean unless an
    // explicit doThrow/doReturn is set, so other test cases keep working.
    @SpyBean private JdbcTemplate jdbc;

    private Long tenantId;
    private String sessionId;

    @BeforeEach
    void setup() {
        tenantId = 9_750_000L + System.nanoTime() % 100_000;
        sessionId = "sess_subagent_" + System.nanoTime();
        MetaContext.setContext(tenantId, testUser.getId(), testUser.getPid(), testUser.getUserName());
    }

    @AfterEach
    void cleanup() {
        // Reset any per-test Mockito stubs on the SpyBean before cleanup writes
        // run — otherwise a G4 stub on jdbc.update(...) would still throw here.
        Mockito.reset(jdbc);
        // children first (FK-free but logically dependent)
        jdbc.update("DELETE FROM ab_agent_interrupt_log WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_agent_run WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_agent_task WHERE tenant_id = ?", tenantId);
        MetaContext.clear();
    }

    /** Seed a parent running run so insert_subtask has something to attach to. */
    private String seedParentRun() {
        return seedParentRunWith(tenantId, "running", testUser.getId());
    }

    /**
     * Seed a parent run with explicit tenant / status / owner. Used by the
     * status-guard, cross-tenant, and created_by-inheritance tests.
     */
    private String seedParentRunWith(Long parentTenantId, String runStatus, Long createdBy) {
        String runPid = UniqueIdGenerator.generate();
        String taskPid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_task (pid, tenant_id, title, task_status, " +
                        " assignee_type, assignee_id, created_at, updated_at, created_by) " +
                        "VALUES (?, ?, 'parent task', 'in_progress', 'agent', 'aurabot', NOW(), NOW(), ?)",
                taskPid, parentTenantId, createdBy);
        jdbc.update("INSERT INTO ab_agent_run (pid, tenant_id, task_id, agent_id, run_status, " +
                        " started_at, created_at, updated_at, created_by) " +
                        "VALUES (?, ?, ?, 'aurabot', ?, NOW(), NOW(), NOW(), ?)",
                runPid, parentTenantId, taskPid, runStatus, createdBy);
        return runPid;
    }

    // =========================================================================
    // Case A — POST /interrupt with insert_subtask spawns a child run
    // =========================================================================

    @Test
    @DisplayName("A: POST /interrupt insert_subtask spawns child ab_agent_run with parent_run_id")
    void caseA_post_interrupt_spawns_child_run() {
        String parentRunPid = seedParentRun();

        ApiResponse<Map<String, Object>> r = controller.handleInterrupt(sessionId,
                Map.of("new_message", "另外帮我把张三的邮箱改成 bob@x.com",
                        "active_run_id", parentRunPid,
                        "current_intent_summary", "正在生成报告"));

        assertThat(r.getData().get("sub_policy")).isEqualTo("insert_subtask");
        assertThat(r.getData().get("action_taken")).isEqualTo("subtask_enqueued");

        String subtaskRunId = (String) r.getData().get("subtask_run_id");
        assertThat(subtaskRunId).as("controller must surface spawned child runId").isNotNull();

        Map<String, Object> child = jdbc.queryForMap(
                "SELECT pid, parent_run_id, subtask_origin, run_status, agent_id, tenant_id " +
                        "FROM ab_agent_run WHERE pid = ?", subtaskRunId);
        assertThat(child.get("pid")).isEqualTo(subtaskRunId);
        assertThat(child.get("parent_run_id")).isEqualTo(parentRunPid);
        assertThat(child.get("subtask_origin")).isEqualTo("interrupt_subtask");
        assertThat(((Number) child.get("tenant_id")).longValue()).isEqualTo(tenantId);
        assertThat(child.get("agent_id")).isNotNull();
    }

    // =========================================================================
    // Case B — DispatchResult exposes child runId; SubAgentRunner.spawn is
    // the real path (not a stub)
    // =========================================================================

    @Test
    @DisplayName("B: DispatchResult.subtaskRunId is non-null and refers to a real child run")
    void caseB_dispatch_result_carries_child_run_id() {
        String parentRunPid = seedParentRun();
        InterruptClassifier.Classification c = classifier.classify(
                "另外帮我同步 alice 的电话", "currently working");
        assertThat(c.getSubPolicy()).isEqualTo(InterruptClassifier.INSERT_SUBTASK);

        InterruptDispatcher.DispatchResult result = dispatcher.dispatch(
                tenantId, sessionId, parentRunPid,
                "另外帮我同步 alice 的电话", c);

        assertThat(result.getActionTaken()).isEqualTo("subtask_enqueued");
        assertThat(result.getSubtaskRunId()).as("dispatcher exposes spawned child runId").isNotNull();

        // Real DB row check — proves SubAgentRunner.spawn ran (not a stub returning fake id)
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_run WHERE pid = ? AND parent_run_id = ?",
                Integer.class, result.getSubtaskRunId(), parentRunPid);
        assertThat(count).isEqualTo(1);
    }

    // =========================================================================
    // Case C — interrupt_log row carries subtask_run_id cross-link
    // =========================================================================

    @Test
    @DisplayName("C: interrupt_log row links to subtask_run via subtask_run_id")
    void caseC_interrupt_log_links_to_subtask() {
        String parentRunPid = seedParentRun();
        // "另外" alone with no augmentation marker → INSERT_SUBTASK
        // ("顺便" overlaps with APPEND_KEYWORDS_ZH so the classifier rule
        // "insertSignal && !appendSignal" excludes it; we keep the message
        // unambiguous for this assertion.)
        InterruptClassifier.Classification c = classifier.classify(
                "另外帮我把 acme 客户的标签改成 vip", "running long task");
        assertThat(c.getSubPolicy()).isEqualTo(InterruptClassifier.INSERT_SUBTASK);

        InterruptDispatcher.DispatchResult result = dispatcher.dispatch(
                tenantId, sessionId, parentRunPid,
                "另外帮我把 acme 客户的标签改成 vip", c);

        assertThat(result.getSubtaskRunId()).isNotNull();
        Map<String, Object> logRow = jdbc.queryForMap(
                "SELECT sub_policy, action_taken, active_run_id, subtask_run_id " +
                        "FROM ab_agent_interrupt_log WHERE pid = ?",
                result.getInterruptLogPid());
        assertThat(logRow.get("sub_policy")).isEqualTo("insert_subtask");
        assertThat(logRow.get("action_taken")).isEqualTo("subtask_enqueued");
        assertThat(logRow.get("active_run_id")).isEqualTo(parentRunPid);
        assertThat(logRow.get("subtask_run_id")).isEqualTo(result.getSubtaskRunId());
    }

    // =========================================================================
    // Case D — multiple insert_subtask under the same parent → distinct child
    // runs sharing the same parent_run_id
    // =========================================================================

    @Test
    @DisplayName("D: multiple insert_subtask under same parent yield distinct child runs")
    void caseD_multiple_subtasks_same_parent() {
        String parentRunPid = seedParentRun();

        InterruptClassifier.Classification c = classifier.classify(
                "另外把 alice 的邮箱改了", "doing X");
        assertThat(c.getSubPolicy()).isEqualTo(InterruptClassifier.INSERT_SUBTASK);

        InterruptDispatcher.DispatchResult r1 = dispatcher.dispatch(
                tenantId, sessionId, parentRunPid, "另外把 alice 的邮箱改了", c);
        InterruptDispatcher.DispatchResult r2 = dispatcher.dispatch(
                tenantId, sessionId, parentRunPid, "另外把 bob 的邮箱改了", c);
        InterruptDispatcher.DispatchResult r3 = dispatcher.dispatch(
                tenantId, sessionId, parentRunPid, "另外把 charlie 的邮箱改了", c);

        assertThat(r1.getSubtaskRunId())
                .isNotEqualTo(r2.getSubtaskRunId())
                .isNotEqualTo(r3.getSubtaskRunId());
        assertThat(r2.getSubtaskRunId()).isNotEqualTo(r3.getSubtaskRunId());

        List<Map<String, Object>> children = jdbc.queryForList(
                "SELECT pid, parent_run_id, subtask_origin FROM ab_agent_run " +
                        "WHERE tenant_id = ? AND parent_run_id = ? ORDER BY started_at",
                tenantId, parentRunPid);
        assertThat(children).hasSize(3);
        assertThat(children).allMatch(row -> "interrupt_subtask".equals(row.get("subtask_origin")));
        assertThat(children).allMatch(row -> parentRunPid.equals(row.get("parent_run_id")));
    }

    // =========================================================================
    // Case E — direct SubAgentRunner.spawn (unit-style sanity for non-keyword
    // callers and for asserting the contract independently of the dispatcher)
    // =========================================================================

    @Test
    @DisplayName("E: SubAgentRunner.spawn directly creates a child run inheriting tenant/user")
    void caseE_subagent_runner_spawn_direct() {
        String parentRunPid = seedParentRun();
        SubAgentRunner.SpawnResult sr = subAgentRunner.spawn(
                tenantId, parentRunPid, sessionId, "do the side task",
                "interrupt_subtask");

        assertThat(sr).isNotNull();
        assertThat(sr.getChildRunPid()).isNotNull();
        assertThat(sr.getChildTaskPid()).isNotNull();

        Map<String, Object> child = jdbc.queryForMap(
                "SELECT pid, parent_run_id, subtask_origin, agent_id, run_status, tenant_id, task_id " +
                        "FROM ab_agent_run WHERE pid = ?", sr.getChildRunPid());
        assertThat(child.get("parent_run_id")).isEqualTo(parentRunPid);
        assertThat(child.get("subtask_origin")).isEqualTo("interrupt_subtask");
        assertThat(((Number) child.get("tenant_id")).longValue()).isEqualTo(tenantId);
        assertThat(child.get("task_id")).isEqualTo(sr.getChildTaskPid());

        // Also verify a child task row was seeded with the message excerpt
        Map<String, Object> task = jdbc.queryForMap(
                "SELECT pid, parent_id, title, assignee_type, assignee_id, tenant_id " +
                        "FROM ab_agent_task WHERE pid = ?", sr.getChildTaskPid());
        assertThat(task.get("assignee_type")).isEqualTo("agent");
        assertThat(((Number) task.get("tenant_id")).longValue()).isEqualTo(tenantId);
        assertThat((String) task.get("title")).contains("do the side task");
    }

    // =========================================================================
    // Case F — child run terminal-state event reaches listeners with parent_run_id
    // available via DB lookup (no synchronous wait — only proves association).
    // =========================================================================

    @Test
    @DisplayName("F: child run row exposes parent_run_id for downstream join queries")
    void caseF_parent_join_via_db() {
        String parentRunPid = seedParentRun();
        SubAgentRunner.SpawnResult sr = subAgentRunner.spawn(
                tenantId, parentRunPid, sessionId, "background lookup",
                "interrupt_subtask");

        // Simulate child reaching terminal state — flip its row + publish event.
        // (In production this is done by AgentRunService.executeTaskSync; here we
        // just want to prove the parent association survives the terminal flip
        // and that both rows are queryable as a tree.)
        jdbc.update("UPDATE ab_agent_run SET run_status = 'success', completed_at = NOW(), " +
                        "    updated_at = NOW() WHERE pid = ?", sr.getChildRunPid());
        eventPublisher.publishEvent(new SessionEndedEvent(
                tenantId, sr.getChildRunPid(), "aurabot",
                String.valueOf(testUser.getId()),
                SessionEndedEvent.TerminalOutcome.SUCCEEDED));

        List<Map<String, Object>> tree = jdbc.queryForList(
                "SELECT pid, parent_run_id, run_status FROM ab_agent_run " +
                        "WHERE pid = ? OR parent_run_id = ? ORDER BY started_at",
                parentRunPid, parentRunPid);
        assertThat(tree).hasSize(2);
        assertThat(tree.get(0).get("pid")).isEqualTo(parentRunPid);
        assertThat(tree.get(1).get("parent_run_id")).isEqualTo(parentRunPid);
        assertThat(tree.get(1).get("run_status")).isEqualTo("success");
    }

    // =========================================================================
    // G1 — cross-tenant rejection: caller tenant != parent.tenant_id throws
    // and child rows are NOT created.
    // =========================================================================

    @Test
    @DisplayName("G1: spawn refuses cross-tenant parent and leaves no child rows")
    void caseG1_cross_tenant_rejected() {
        Long otherTenant = tenantId + 1L;
        String parentRunPid = seedParentRunWith(otherTenant, "running", testUser.getId());

        assertThatThrownBy(() -> subAgentRunner.spawn(
                tenantId, parentRunPid, sessionId, "another tenant subtask",
                "interrupt_subtask"))
                .isInstanceOf(IllegalStateException.class)
                // C.2: cross-tenant without grant is now denied via ACL,
                // surfacing CrossTenantAclDeniedException (subclass of
                // IllegalStateException) with structured message.
                .hasMessageContaining("cross-tenant spawn requires explicit grant")
                .hasMessageContaining("denied_no_grant");

        // No child run rows in either tenant.
        Integer countCallerTenant = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_run WHERE tenant_id = ? AND parent_run_id = ?",
                Integer.class, tenantId, parentRunPid);
        Integer countParentTenant = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_run WHERE tenant_id = ? AND parent_run_id = ?",
                Integer.class, otherTenant, parentRunPid);
        assertThat(countCallerTenant).isZero();
        assertThat(countParentTenant).isZero();

        // Cleanup the cross-tenant fixture rows we explicitly inserted.
        jdbc.update("DELETE FROM ab_agent_run WHERE tenant_id = ?", otherTenant);
        jdbc.update("DELETE FROM ab_agent_task WHERE tenant_id = ?", otherTenant);
    }

    // =========================================================================
    // G2 — parent run not found: spawn throws IllegalStateException and no
    // partial rows are written.
    // =========================================================================

    @Test
    @DisplayName("G2: spawn under non-existent parent_run_id throws and writes no rows")
    void caseG2_parent_not_found() {
        String fakeParent = UniqueIdGenerator.generate();

        assertThatThrownBy(() -> subAgentRunner.spawn(
                tenantId, fakeParent, sessionId, "orphan subtask", "interrupt_subtask"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Parent run not found");

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_run WHERE parent_run_id = ?",
                Integer.class, fakeParent);
        assertThat(count).isZero();
    }

    // =========================================================================
    // G3 (B2 verification) — non-running parent: spawn refuses to attach
    // a child to a cancelled / failed / timeout / success parent.
    // =========================================================================

    @Test
    @DisplayName("G3: spawn under cancelled parent throws and writes no child run")
    void caseG3_non_running_parent_rejected() {
        String parentRunPid = seedParentRunWith(tenantId, "cancelled", testUser.getId());

        assertThatThrownBy(() -> subAgentRunner.spawn(
                tenantId, parentRunPid, sessionId, "late subtask",
                "interrupt_subtask"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot spawn under non-running parent");

        Integer childRuns = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_run WHERE parent_run_id = ?",
                Integer.class, parentRunPid);
        assertThat(childRuns).isZero();

        // Same guard for failed parent
        String failedParent = seedParentRunWith(tenantId, "failed", testUser.getId());
        assertThatThrownBy(() -> subAgentRunner.spawn(
                tenantId, failedParent, sessionId, "late subtask 2", "interrupt_subtask"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot spawn under non-running parent");
    }

    // =========================================================================
    // G4 (B1 verification) — atomicity: if the second INSERT (ab_agent_run)
    // fails, the first INSERT (ab_agent_task) must be rolled back so we
    // do not leak orphan task rows that stay 'in_progress' forever.
    // =========================================================================

    @Test
    @DisplayName("G4: ab_agent_run INSERT failure rolls back ab_agent_task INSERT")
    void caseG4_two_step_insert_atomicity() {
        String parentRunPid = seedParentRun();

        // Count task rows before so we can prove no new ab_agent_task row
        // survived the rollback.
        Integer tasksBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_task WHERE tenant_id = ?",
                Integer.class, tenantId);
        Integer runsBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_run WHERE tenant_id = ?",
                Integer.class, tenantId);

        // Stub: any INSERT INTO ab_agent_run with 11 args (the child run insert
        // signature) throws. The first INSERT (ab_agent_task) is unaffected
        // and runs against the real DB; spawn's @Transactional should roll it
        // back when the run insert blows up.
        Mockito.doThrow(new RuntimeException("simulated child run INSERT failure"))
                .when(jdbc).update(
                        Mockito.argThat(sql -> sql != null && sql.startsWith("INSERT INTO ab_agent_run")),
                        Mockito.any(Object[].class));
        // Some Spring proxies route through the varargs overload; cover both:
        Mockito.doThrow(new RuntimeException("simulated child run INSERT failure"))
                .when(jdbc).update(
                        Mockito.argThat((String sql) -> sql != null && sql.startsWith("INSERT INTO ab_agent_run")),
                        Mockito.<Object>any(), Mockito.<Object>any(), Mockito.<Object>any(),
                        Mockito.<Object>any(), Mockito.<Object>any(), Mockito.<Object>any(),
                        Mockito.<Object>any(), Mockito.<Object>any(), Mockito.<Object>any(),
                        Mockito.<Object>any(), Mockito.<Object>any(), Mockito.<Object>any(),
                        Mockito.<Object>any(), Mockito.<Object>any(), Mockito.<Object>any());

        assertThatThrownBy(() -> subAgentRunner.spawn(
                tenantId, parentRunPid, sessionId, "rollback me", "interrupt_subtask"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("simulated child run INSERT failure");

        // Reset the spy before reading back so the SELECT is real.
        Mockito.reset(jdbc);

        Integer tasksAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_task WHERE tenant_id = ?",
                Integer.class, tenantId);
        Integer runsAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_run WHERE tenant_id = ?",
                Integer.class, tenantId);

        // No orphan task / run from the failed spawn.
        assertThat(tasksAfter).as("ab_agent_task INSERT must roll back").isEqualTo(tasksBefore);
        assertThat(runsAfter).as("ab_agent_run INSERT must not exist").isEqualTo(runsBefore);
    }

    // =========================================================================
    // G5 (B4 verification) — created_by inheritance: the child run inherits
    // the parent run's created_by, NOT the spawning thread's MetaContext.
    // This protects user run-tree queries when the spawn is triggered from a
    // system-user context (delegate_task tool, scheduled split).
    // =========================================================================

    @Test
    @DisplayName("G5: child run inherits parent.created_by even when MetaContext user differs")
    void caseG5_created_by_inherits_from_parent() {
        Long parentOwner = testUser.getId();              // userA
        Long spawningCallerUser = testUser.getId() + 999; // userB — different user calling spawn

        String parentRunPid = seedParentRunWith(tenantId, "running", parentOwner);

        // Switch MetaContext to userB to mimic delegate_task / system-user spawn.
        MetaContext.setContext(tenantId, spawningCallerUser, "user-b-pid", "user-b");

        SubAgentRunner.SpawnResult sr = subAgentRunner.spawn(
                tenantId, parentRunPid, sessionId, "side task by user B",
                "interrupt_subtask");

        // Restore for cleanup
        MetaContext.setContext(tenantId, testUser.getId(), testUser.getPid(), testUser.getUserName());

        Map<String, Object> child = jdbc.queryForMap(
                "SELECT created_by, parent_run_id FROM ab_agent_run WHERE pid = ?",
                sr.getChildRunPid());
        assertThat(child.get("parent_run_id")).isEqualTo(parentRunPid);
        Long childCreatedBy = child.get("created_by") == null
                ? null : ((Number) child.get("created_by")).longValue();
        assertThat(childCreatedBy)
                .as("child must inherit parent.created_by, not the spawning caller's userId")
                .isEqualTo(parentOwner)
                .isNotEqualTo(spawningCallerUser);

        // Same expectation on the child task row (audit symmetry).
        Map<String, Object> childTask = jdbc.queryForMap(
                "SELECT created_by FROM ab_agent_task WHERE pid = ?", sr.getChildTaskPid());
        Long taskCreatedBy = childTask.get("created_by") == null
                ? null : ((Number) childTask.get("created_by")).longValue();
        assertThat(taskCreatedBy).isEqualTo(parentOwner);
    }
}
