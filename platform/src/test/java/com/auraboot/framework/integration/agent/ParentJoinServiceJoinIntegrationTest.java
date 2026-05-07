package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.memory.SessionEndedEvent;
import com.auraboot.framework.agent.service.ChildRunOutcome;
import com.auraboot.framework.agent.service.JoinTimeoutException;
import com.auraboot.framework.agent.service.ParentJoinService;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ACP backlog C.1 — {@link ParentJoinService#joinChildRun(String, String, long)}
 * blocking-join API. Cases:
 *
 * <ul>
 *   <li>A — child terminates after the join call: returns ChildRunOutcome
 *       with the right status / cost / tokens.</li>
 *   <li>B — child already terminal before the join call: returns immediately
 *       from the DB readback (no latch wait).</li>
 *   <li>C — child never reaches terminal within timeout: JoinTimeoutException
 *       carrying both run ids and the actual waited millis.</li>
 *   <li>D — cross-tenant parent/child: IllegalStateException, no waiting.</li>
 *   <li>E — two threads join the same (parent, child): both receive the same
 *       ChildRunOutcome (shared-slot semantics).</li>
 *   <li>F — slot cleanup: after join completes the internal slot map is
 *       empty (no unbounded growth).</li>
 * </ul>
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("ParentJoinService.joinChildRun (ACP C.1)")
class ParentJoinServiceJoinIntegrationTest extends BaseIntegrationTest {

    @Autowired private ParentJoinService parentJoinService;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;

    @BeforeEach
    void setup() {
        tenantId = 9_760_000L + System.nanoTime() % 100_000;
        MetaContext.setContext(tenantId, testUser.getId(), testUser.getPid(), testUser.getUserName());
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_run WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_agent_task WHERE tenant_id = ?", tenantId);
        // Cross-tenant fixture cleanup (used by case D)
        jdbc.update("DELETE FROM ab_agent_run WHERE tenant_id = ?", tenantId + 1L);
        jdbc.update("DELETE FROM ab_agent_task WHERE tenant_id = ?", tenantId + 1L);
        MetaContext.clear();
    }

    /** Seed parent + child run rows; child starts in 'running'. */
    private RunPair seedParentAndChild(Long parentTenant, Long childTenant) {
        String parentTaskPid = UniqueIdGenerator.generate();
        String parentRunPid = UniqueIdGenerator.generate();
        String childTaskPid = UniqueIdGenerator.generate();
        String childRunPid = UniqueIdGenerator.generate();

        jdbc.update("INSERT INTO ab_agent_task (pid, tenant_id, title, task_status, " +
                        " assignee_type, assignee_id, created_at, updated_at, created_by) " +
                        "VALUES (?, ?, 'parent', 'in_progress', 'agent', 'aurabot', NOW(), NOW(), ?)",
                parentTaskPid, parentTenant, testUser.getId());
        jdbc.update("INSERT INTO ab_agent_run (pid, tenant_id, task_id, agent_id, run_status, " +
                        " started_at, created_at, updated_at, created_by) " +
                        "VALUES (?, ?, ?, 'aurabot', 'running', NOW(), NOW(), NOW(), ?)",
                parentRunPid, parentTenant, parentTaskPid, testUser.getId());

        jdbc.update("INSERT INTO ab_agent_task (pid, tenant_id, parent_id, title, task_status, " +
                        " assignee_type, assignee_id, created_at, updated_at, created_by) " +
                        "VALUES (?, ?, ?, 'child', 'in_progress', 'agent', 'aurabot', NOW(), NOW(), ?)",
                childTaskPid, childTenant, parentTaskPid, testUser.getId());
        jdbc.update("INSERT INTO ab_agent_run (pid, tenant_id, task_id, agent_id, run_status, " +
                        " parent_run_id, subtask_origin, " +
                        " input_tokens, output_tokens, total_cost, " +
                        " started_at, created_at, updated_at, created_by) " +
                        "VALUES (?, ?, ?, 'aurabot', 'running', ?, 'interrupt_subtask', " +
                        "        0, 0, 0, NOW(), NOW(), NOW(), ?)",
                childRunPid, childTenant, childTaskPid, parentRunPid, testUser.getId());

        return new RunPair(parentRunPid, childRunPid);
    }

    /**
     * Mark the child terminal in the DB and publish the SessionEndedEvent
     * (mirrors what AgentRunService does on terminal). Uses a real Spring
     * publisher so the listener chain populates the join slot.
     */
    private void completeChild(String childRunId, String runStatus,
                               long inputTokens, long outputTokens, BigDecimal cost,
                               SessionEndedEvent.TerminalOutcome outcome) {
        jdbc.update("UPDATE ab_agent_run SET run_status = ?, completed_at = NOW(), " +
                        " input_tokens = ?, output_tokens = ?, total_cost = ?, " +
                        " updated_at = NOW() WHERE pid = ?",
                runStatus, inputTokens, outputTokens, cost, childRunId);
        eventPublisher.publishEvent(new SessionEndedEvent(
                tenantId, childRunId, "aurabot",
                String.valueOf(testUser.getId()), outcome));
    }

    // =========================================================================
    // A — child terminates AFTER joinChildRun call
    // =========================================================================

    @Test
    @DisplayName("A: joinChildRun blocks until child terminates, returns ChildRunOutcome")
    void caseA_blocks_until_child_terminates() throws Exception {
        RunPair p = seedParentAndChild(tenantId, tenantId);

        // Coordinator: tell the joiner thread we've reached the await point so
        // the producer thread can flip the row + publish the event.
        CountDownLatch joinerReady = new CountDownLatch(1);

        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<ChildRunOutcome> future = CompletableFuture.supplyAsync(() -> {
                joinerReady.countDown();
                return parentJoinService.joinChildRun(p.parentRunPid, p.childRunPid, 5000L);
            }, exec);

            // Wait for the joiner to be inside the call before producing.
            assertThat(joinerReady.await(2, TimeUnit.SECONDS)).isTrue();
            // Small handoff so the joiner is past the DB-readback and inside latch.await.
            // We use a CountDownLatch-based trick instead of Thread.sleep:
            // poll the slot count up to 1s using a loop on activeSlotCount.
            long deadline = System.currentTimeMillis() + 1000L;
            while (parentJoinService.activeSlotCount() == 0
                    && System.currentTimeMillis() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(parentJoinService.activeSlotCount())
                    .as("joiner thread should have registered a slot before producer fires")
                    .isEqualTo(1);

            completeChild(p.childRunPid, "success", 1234L, 567L,
                    new BigDecimal("0.012345"),
                    SessionEndedEvent.TerminalOutcome.SUCCEEDED);

            ChildRunOutcome out = future.get(5, TimeUnit.SECONDS);
            assertThat(out.childRunId()).isEqualTo(p.childRunPid);
            assertThat(out.terminalStatus()).isEqualTo("succeeded");
            assertThat(out.inputTokens()).isEqualTo(1234L);
            assertThat(out.outputTokens()).isEqualTo(567L);
            assertThat(out.totalCost()).isEqualByComparingTo("0.012345");
        } finally {
            exec.shutdownNow();
        }
    }

    // =========================================================================
    // B — child already terminal before joinChildRun called
    // =========================================================================

    @Test
    @DisplayName("B: joinChildRun returns immediately when child is already terminal")
    void caseB_returns_immediately_when_already_terminal() {
        RunPair p = seedParentAndChild(tenantId, tenantId);

        // Flip child to terminal BEFORE calling join. We don't even publish
        // the event — the DB-readback path must cover this case.
        jdbc.update("UPDATE ab_agent_run SET run_status = 'failed', completed_at = NOW(), " +
                        " input_tokens = 100, output_tokens = 50, total_cost = 0.0001, " +
                        " updated_at = NOW() WHERE pid = ?",
                p.childRunPid);

        long before = System.currentTimeMillis();
        ChildRunOutcome out = parentJoinService.joinChildRun(
                p.parentRunPid, p.childRunPid, 5000L);
        long elapsed = System.currentTimeMillis() - before;

        assertThat(out.terminalStatus()).isEqualTo("failed");
        assertThat(out.inputTokens()).isEqualTo(100L);
        assertThat(out.outputTokens()).isEqualTo(50L);
        assertThat(out.totalCost()).isEqualByComparingTo("0.0001");
        // "Returns immediately" — must be much less than the 5s timeout. Use
        // 500ms cap (plenty of slack for slow CI without being a sleep).
        assertThat(elapsed).as("must skip latch wait when DB already terminal").isLessThan(500L);
    }

    // =========================================================================
    // C — child never completes within timeout
    // =========================================================================

    @Test
    @DisplayName("C: joinChildRun throws JoinTimeoutException when child stays running")
    void caseC_timeout_throws_with_run_ids() {
        RunPair p = seedParentAndChild(tenantId, tenantId);

        long before = System.currentTimeMillis();
        assertThatThrownBy(() -> parentJoinService.joinChildRun(
                p.parentRunPid, p.childRunPid, 200L))
                .isInstanceOf(JoinTimeoutException.class)
                .hasMessageContaining(p.parentRunPid)
                .hasMessageContaining(p.childRunPid)
                .hasMessageContaining("waitedMs");
        long elapsed = System.currentTimeMillis() - before;
        // We waited at least the timeout, but not absurdly more.
        assertThat(elapsed).isGreaterThanOrEqualTo(200L).isLessThan(2000L);

        // Slot must be cleaned up even on timeout (red-line: no map leak).
        assertThat(parentJoinService.activeSlotCount()).isZero();
    }

    // =========================================================================
    // D — cross-tenant parent/child rejected with no waiting
    // =========================================================================

    @Test
    @DisplayName("D: cross-tenant parent/child throws IllegalStateException without blocking")
    void caseD_cross_tenant_rejected_immediately() {
        Long otherTenant = tenantId + 1L;
        // Seed parent in tenantId and child in otherTenant — manual mismatch.
        String parentTaskPid = UniqueIdGenerator.generate();
        String parentRunPid = UniqueIdGenerator.generate();
        String childTaskPid = UniqueIdGenerator.generate();
        String childRunPid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_task (pid, tenant_id, title, task_status, " +
                        " assignee_type, assignee_id, created_at, updated_at, created_by) " +
                        "VALUES (?, ?, 'parent', 'in_progress', 'agent', 'aurabot', NOW(), NOW(), ?)",
                parentTaskPid, tenantId, testUser.getId());
        jdbc.update("INSERT INTO ab_agent_run (pid, tenant_id, task_id, agent_id, run_status, " +
                        " started_at, created_at, updated_at, created_by) " +
                        "VALUES (?, ?, ?, 'aurabot', 'running', NOW(), NOW(), NOW(), ?)",
                parentRunPid, tenantId, parentTaskPid, testUser.getId());
        jdbc.update("INSERT INTO ab_agent_task (pid, tenant_id, parent_id, title, task_status, " +
                        " assignee_type, assignee_id, created_at, updated_at, created_by) " +
                        "VALUES (?, ?, ?, 'child', 'in_progress', 'agent', 'aurabot', NOW(), NOW(), ?)",
                childTaskPid, otherTenant, parentTaskPid, testUser.getId());
        jdbc.update("INSERT INTO ab_agent_run (pid, tenant_id, task_id, agent_id, run_status, " +
                        " parent_run_id, subtask_origin, started_at, created_at, updated_at, created_by) " +
                        "VALUES (?, ?, ?, 'aurabot', 'running', ?, 'interrupt_subtask', " +
                        "        NOW(), NOW(), NOW(), ?)",
                childRunPid, otherTenant, childTaskPid, parentRunPid, testUser.getId());

        long before = System.currentTimeMillis();
        // C.2 broadens the cross-tenant policy: instead of a hard refuse,
        // the join now consults CrossTenantAclService. With no grant, the
        // service returns denied_no_grant which surfaces as
        // CrossTenantAclDeniedException (still IllegalStateException).
        assertThatThrownBy(() -> parentJoinService.joinChildRun(parentRunPid, childRunPid, 5000L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cross-tenant spawn requires explicit grant")
                .hasMessageContaining("denied_no_grant");
        assertThatThrownBy(() -> parentJoinService.joinChildRun(parentRunPid, childRunPid, 5000L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match");
        long elapsed = System.currentTimeMillis() - before;
        assertThat(elapsed).as("tenant guard must fail fast, not block").isLessThan(500L);
        // No leftover slot from the rejected call.
        assertThat(parentJoinService.activeSlotCount()).isZero();
    }

    // =========================================================================
    // E — two threads joining the same (parent, child) both succeed
    // =========================================================================

    @Test
    @DisplayName("E: concurrent joiners on same (parent, child) both receive the same outcome")
    void caseE_concurrent_joiners_share_outcome() throws Exception {
        RunPair p = seedParentAndChild(tenantId, tenantId);

        ExecutorService exec = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch bothReady = new CountDownLatch(2);
            CompletableFuture<ChildRunOutcome> f1 = CompletableFuture.supplyAsync(() -> {
                bothReady.countDown();
                return parentJoinService.joinChildRun(p.parentRunPid, p.childRunPid, 5000L);
            }, exec);
            CompletableFuture<ChildRunOutcome> f2 = CompletableFuture.supplyAsync(() -> {
                bothReady.countDown();
                return parentJoinService.joinChildRun(p.parentRunPid, p.childRunPid, 5000L);
            }, exec);

            assertThat(bothReady.await(2, TimeUnit.SECONDS)).isTrue();
            // Spin until the slot has been registered (shared) before firing.
            long deadline = System.currentTimeMillis() + 1000L;
            while (parentJoinService.activeSlotCount() == 0
                    && System.currentTimeMillis() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(parentJoinService.activeSlotCount())
                    .as("two joiners on same key share one slot")
                    .isEqualTo(1);

            completeChild(p.childRunPid, "cancelled", 7L, 3L,
                    new BigDecimal("0.000123"),
                    SessionEndedEvent.TerminalOutcome.CANCELLED);

            ChildRunOutcome o1 = f1.get(5, TimeUnit.SECONDS);
            ChildRunOutcome o2 = f2.get(5, TimeUnit.SECONDS);
            assertThat(o1).isEqualTo(o2);
            assertThat(o1.terminalStatus()).isEqualTo("cancelled");
            assertThat(o1.inputTokens()).isEqualTo(7L);
            assertThat(o1.outputTokens()).isEqualTo(3L);
            assertThat(o1.totalCost()).isEqualByComparingTo("0.000123");
        } finally {
            exec.shutdownNow();
        }
    }

    // =========================================================================
    // F — slot cleanup after join completes
    // =========================================================================

    @Test
    @DisplayName("F: slot map is empty after each join (success / timeout / reject)")
    void caseF_slot_cleanup_no_leak() throws Exception {
        // Sanity baseline (other tests run with @AfterEach clean, but the
        // map is process-wide; assert at start of test to detect drift.)
        int baseline = parentJoinService.activeSlotCount();

        // Path 1 — success
        RunPair p1 = seedParentAndChild(tenantId, tenantId);
        completeChild(p1.childRunPid, "success", 1L, 1L, new BigDecimal("0.000001"),
                SessionEndedEvent.TerminalOutcome.SUCCEEDED);
        parentJoinService.joinChildRun(p1.parentRunPid, p1.childRunPid, 5000L);
        assertThat(parentJoinService.activeSlotCount()).isEqualTo(baseline);

        // Path 2 — timeout
        RunPair p2 = seedParentAndChild(tenantId, tenantId);
        try {
            parentJoinService.joinChildRun(p2.parentRunPid, p2.childRunPid, 100L);
        } catch (JoinTimeoutException expected) {
            // expected
        }
        assertThat(parentJoinService.activeSlotCount()).isEqualTo(baseline);

        // Path 3 — argument validation does not even register a slot
        try {
            parentJoinService.joinChildRun("", "x", 100L);
        } catch (IllegalArgumentException expected) {
            // expected
        }
        assertThat(parentJoinService.activeSlotCount()).isEqualTo(baseline);
    }

    private record RunPair(String parentRunPid, String childRunPid) {}
}
