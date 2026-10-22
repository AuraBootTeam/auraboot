package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.crosstenant.CrossTenantAclDeniedException;
import com.auraboot.framework.agent.memory.SessionEndedEvent;
import com.auraboot.framework.agent.service.ChildRunCompletedEvent;
import com.auraboot.framework.agent.service.SubAgentRunner;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.test.annotation.Commit;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A.1 follow-up — ACP P0-6 / P1: end-to-end async wiring of {@link SubAgentRunner}
 * spawn → {@code TransactionSynchronization.afterCommit} → @Async dispatch →
 * terminal SessionEndedEvent → {@link ChildRunCompletedEvent} bridged event.
 *
 * <p>Where the existing {@link SubAgentRunnerIntegrationTest} focuses on the
 * spawn primitive (transaction atomicity, FK linkage, status guards), this IT
 * focuses on the <b>async terminal-event chain</b> the parent run / dispatcher
 * relies on for fire-and-forget multi-agent workflows.
 *
 * <p>The {@link SubAgentRunner} unit tests cannot drive the actual LLM loop in
 * a hermetic IT (no Anthropic key in CI, would also be flaky with real API).
 * Instead these cases prove the wiring step-by-step:
 *
 * <ol>
 *   <li><b>E1 latch on bridged event</b>: spawn → manually flip child status to
 *       terminal + publish {@link SessionEndedEvent} (mirrors what
 *       {@code AgentRunService.publishSessionEndedIfApplicable} does in
 *       production) → a {@link CountDownLatch}-backed test listener for
 *       {@link ChildRunCompletedEvent} signals within the timeout, with
 *       parent/child/outcome payload populated from the row. The assertion
 *       proves the {@code SessionEndedEvent → @EventListener →
 *       ChildRunCompletedEvent} pipeline is wired, not just the latch
 *       behaviour. Listener is reset between tests via {@link
 *       LatchedChildRunCompletedListener#reset()} to keep test isolation.</li>
 *
 *   <li><b>E2 cross-tenant rejection</b>: a parent in tenantA spawned by a
 *       caller bound to tenantB throws {@link IllegalStateException} per
 *       the red-line {@code feedback_subagent_worktree_verify}; no rows are
 *       written. (Mirrors {@code SubAgentRunnerIntegrationTest.G1} but kept
 *       here so this file is self-contained as the spec asks for ≥3 cases.)</li>
 *
 *   <li><b>E3 spawn dispatches @Async executor</b>: spawn outside of a
 *       transaction (we mark this test {@code @Transactional(NEVER)}) →
 *       {@code SubAgentRunner.spawn} falls back to direct dispatch because
 *       no TransactionSynchronization is active. The @Async executor on
 *       {@code AgentRunService.executeTaskForExistingRun} runs on a worker
 *       thread; with no LLM provider configured, the run lifecycle flips
 *       the row from {@code running} to {@code failed} via
 *       {@code RunLifecycleService.failRun}. We poll up to a bounded
 *       deadline for that terminal flip — the change in {@code run_status}
 *       is the smoking gun that proves the async dispatch fired.</li>
 *
 *   <li><b>E4 multiple children, single parent</b>: fan out N spawns → each
 *       child gets its own SessionEndedEvent → exactly N
 *       {@link ChildRunCompletedEvent}s observed by the latch listener,
 *       each carrying the same parentRunId.</li>
 * </ol>
 *
 * <p>Reference source files (read-only):
 * <ul>
 *   <li>{@code platform/src/main/java/com/auraboot/framework/agent/service/SubAgentRunner.java}</li>
 *   <li>{@code platform/src/main/java/com/auraboot/framework/agent/service/AgentRunService.java}
 *       (executeTaskForExistingRun async entry)</li>
 *   <li>{@code platform/src/main/java/com/auraboot/framework/agent/service/ParentJoinService.java}
 *       (the @EventListener bridge)</li>
 * </ul>
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("A.1 — SubAgentRunner async terminal wiring (CountDownLatch + ChildRunCompletedEvent)")
class SubAgentRunnerExecutionIntegrationTest extends BaseIntegrationTest {

    /**
     * Test-scoped {@link ChildRunCompletedEvent} sink. Picked up by Spring's
     * {@code @ComponentScan(basePackages = "com.auraboot.framework")} on
     * {@code TestApplication} (the parent class for {@code @SpringBootTest}),
     * because this nested class lives under {@code com.auraboot.framework.*}.
     * Mirrors the pattern in
     * {@code SchemaPublishedEventIntegrationTest.TestSchemaPublishedEventListener}.
     *
     * <p>Default Spring event semantics apply: the listener fires synchronously
     * because {@link ChildRunCompletedEvent} is not configured for the async
     * executor.
     *
     * <p>Stateful — must be {@link #reset()} between tests so a previous test's
     * latched events don't leak. The listener bean is shared across all tests
     * in the same Spring context, but only this test wires it up — other tests
     * never publish {@link ChildRunCompletedEvent} during their bodies (the
     * production path runs through async channels not exercised by their
     * fixtures), so cross-test interference is not observed in practice.
     */
    @Component
    public static class LatchedChildRunCompletedListener {
        private final CopyOnWriteArrayList<ChildRunCompletedEvent> events = new CopyOnWriteArrayList<>();
        private volatile CountDownLatch latch = new CountDownLatch(1);

        @EventListener
        public void onChildCompleted(ChildRunCompletedEvent event) {
            events.add(event);
            latch.countDown();
        }

        public void awaitCount(int target, long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            // The first latch handles the typical "1 event" case; for fan-out
            // we re-await after each countDown until size >= target or timeout.
            while (events.size() < target && System.currentTimeMillis() < deadline) {
                long remaining = Math.max(1, deadline - System.currentTimeMillis());
                latch.await(remaining, TimeUnit.MILLISECONDS);
                // Re-arm so subsequent countDowns are observable.
                if (events.size() < target) {
                    latch = new CountDownLatch(1);
                }
            }
        }

        public List<ChildRunCompletedEvent> events() {
            return List.copyOf(events);
        }

        public void reset() {
            events.clear();
            latch = new CountDownLatch(1);
        }
    }

    @Autowired private SubAgentRunner subAgentRunner;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LatchedChildRunCompletedListener latchedListener;

    private Long tenantId;
    private Long otherTenantId;
    private String sessionId;

    @BeforeEach
    void setup() {
        tenantId = 9_830_000L + System.nanoTime() % 100_000;
        otherTenantId = tenantId + 13L;
        sessionId = "sess_subexec_" + System.nanoTime();
        MetaContext.setContext(tenantId, testUser.getId(), testUser.getPid(), testUser.getUserName());
        latchedListener.reset();
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_run WHERE tenant_id IN (?, ?)", tenantId, otherTenantId);
        jdbc.update("DELETE FROM ab_agent_task WHERE tenant_id IN (?, ?)", tenantId, otherTenantId);
        latchedListener.reset();
        MetaContext.clear();
    }

    /** Seed a running parent under the given tenant; returns the run pid. */
    private String seedParentRun(Long parentTenantId) {
        String runPid = UniqueIdGenerator.generate();
        String taskPid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_task (pid, tenant_id, title, task_status, " +
                        " assignee_type, assignee_id, created_at, updated_at, created_by) " +
                        "VALUES (?, ?, 'parent for execution wiring', 'in_progress', 'agent', 'aurabot', " +
                        "        NOW(), NOW(), ?)",
                taskPid, parentTenantId, testUser.getId());
        jdbc.update("INSERT INTO ab_agent_run (pid, tenant_id, task_id, agent_id, run_status, " +
                        " started_at, created_at, updated_at, created_by) " +
                        "VALUES (?, ?, ?, 'aurabot', 'running', NOW(), NOW(), NOW(), ?)",
                runPid, parentTenantId, taskPid, testUser.getId());
        return runPid;
    }

    // =========================================================================
    // E1 — latch on bridged event after manually-driven terminal SessionEnded.
    // Proves the SessionEndedEvent → ParentJoinService → ChildRunCompletedEvent
    // chain delivers within a bounded wait, with the right payload.
    // =========================================================================

    @Test
    @DisplayName("E1: spawn + terminal SessionEndedEvent → ChildRunCompletedEvent latched within timeout")
    void e1_latch_on_bridged_event() throws InterruptedException {
        String parentRunPid = seedParentRun(tenantId);

        SubAgentRunner.SpawnResult spawn = subAgentRunner.spawn(
                tenantId, parentRunPid, sessionId,
                "Background lookup task", "interrupt_subtask");
        assertThat(spawn.getChildRunPid()).isNotBlank();

        // Right after spawn, the child row exists in 'running' state — verifies
        // the synchronous spawn part of the contract before we assert the
        // async terminal arrival.
        Map<String, Object> initial = jdbc.queryForMap(
                "SELECT run_status, parent_run_id FROM ab_agent_run WHERE pid = ?",
                spawn.getChildRunPid());
        assertThat(initial.get("run_status")).isEqualTo("running");
        assertThat(initial.get("parent_run_id")).isEqualTo(parentRunPid);

        // Drive a deterministic terminal: flip the child to 'success' and
        // publish SessionEndedEvent (mirrors AgentRunService production path
        // without needing an LLM key configured in CI).
        jdbc.update("UPDATE ab_agent_run SET run_status = 'success', completed_at = NOW(), " +
                        "    updated_at = NOW() WHERE pid = ?",
                spawn.getChildRunPid());
        eventPublisher.publishEvent(new SessionEndedEvent(
                tenantId, spawn.getChildRunPid(), "aurabot",
                String.valueOf(testUser.getId()),
                SessionEndedEvent.TerminalOutcome.SUCCEEDED));

        // ParentJoinService is a synchronous @EventListener so the latch
        // should already be down — but we still bound the wait to keep the
        // test resilient against future changes that move the listener to
        // a TaskExecutor.
        latchedListener.awaitCount(1, /* timeoutMs */ 5_000);

        List<ChildRunCompletedEvent> events = latchedListener.events();
        assertThat(events)
                .as("exactly one ChildRunCompletedEvent received within 5s deadline")
                .hasSize(1);
        ChildRunCompletedEvent ev = events.get(0);
        assertThat(ev.getParentRunId()).isEqualTo(parentRunPid);
        assertThat(ev.getChildRunId()).isEqualTo(spawn.getChildRunPid());
        assertThat(ev.getOutcome()).isEqualTo("succeeded");
        assertThat(ev.getTenantId()).isEqualTo(tenantId);
    }

    // =========================================================================
    // E2 — cross-tenant rejection: caller tenant != parent.tenant_id throws,
    // no rows written. Same red-line as G1 in SubAgentRunnerIntegrationTest;
    // duplicated here so this IT remains self-contained per spec.
    // =========================================================================

    @Test
    @DisplayName("E2: spawn rejects cross-tenant parent and writes no rows")
    void e2_cross_tenant_rejected() {
        String parentRunPid = seedParentRun(otherTenantId);

        // Updated: SubAgentRunner now consults CrossTenantAclService and
        // throws CrossTenantAclDeniedException (a subclass of
        // IllegalStateException carrying the structured decision tuple). The
        // message is sourced from the deny-decision audit row format.
        assertThatThrownBy(() -> subAgentRunner.spawn(
                tenantId, parentRunPid, sessionId, "cross-tenant subtask",
                "interrupt_subtask"))
                .isInstanceOf(CrossTenantAclDeniedException.class)
                .hasMessageContaining("cross-tenant spawn requires explicit grant");

        Integer countCallerTenant = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_run WHERE tenant_id = ? AND parent_run_id = ?",
                Integer.class, tenantId, parentRunPid);
        Integer countParentTenant = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_run WHERE tenant_id = ? AND parent_run_id = ?",
                Integer.class, otherTenantId, parentRunPid);
        assertThat(countCallerTenant).isZero();
        assertThat(countParentTenant).isZero();
        // No latched event either — nothing was ever spawned.
        assertThat(latchedListener.events()).isEmpty();
    }

    // =========================================================================
    // E3 — async dispatch fires: spawn outside a managed transaction → direct
    // dispatch path → @Async executor on AgentRunService eventually flips
    // run_status away from 'running'. Without LLM provider config the terminal
    // value is 'failed' (no-provider branch), but the row state CHANGE proves
    // the async wiring fired. We poll up to 15s with 200ms cadence — typical
    // observed latency on local PG is <2s.
    // =========================================================================

    @Test
    @DisplayName("E3: spawn outside TX → @Async executor flips child run_status from 'running'")
    void e3_async_executor_changes_run_status() throws InterruptedException {
        String parentRunPid = seedParentRun(tenantId);

        // No active transaction here (class is @Transactional(NEVER)), so
        // SubAgentRunner.spawn falls into the `else` branch and dispatches
        // directly. AgentRunService.executeTaskForExistingRun is @Async, so
        // the work runs on a worker thread — we observe via DB polling.
        SubAgentRunner.SpawnResult spawn = subAgentRunner.spawn(
                tenantId, parentRunPid, sessionId,
                "background async dispatch test", "interrupt_subtask");

        long deadline = System.currentTimeMillis() + 15_000;
        String terminalStatus = null;
        while (System.currentTimeMillis() < deadline) {
            String status = jdbc.queryForObject(
                    "SELECT run_status FROM ab_agent_run WHERE pid = ?",
                    String.class, spawn.getChildRunPid());
            if (!"running".equals(status)) {
                terminalStatus = status;
                break;
            }
            Thread.sleep(200);
        }

        assertThat(terminalStatus)
                .as("@Async executor must transition child run away from 'running' " +
                        "within 15s — the value change proves the dispatch fired " +
                        "(no LLM key in CI → terminal is 'failed' via no-provider branch, " +
                        "which is acceptable; we test wiring, not LLM call)")
                .isNotNull()
                .isNotEqualTo("running")
                // Allowed terminal states the lifecycle may leave the row in
                // depending on whether an LLM provider is configured.
                .isIn("failed", "success", "queued");

        // Confirm error_message is populated when the terminal is 'failed'
        // (no-provider message, helps future debugging if this test starts
        // failing because the production path stopped writing it).
        if ("failed".equals(terminalStatus)) {
            String errMsg = jdbc.queryForObject(
                    "SELECT error_message FROM ab_agent_run WHERE pid = ?",
                    String.class, spawn.getChildRunPid());
            assertThat(errMsg)
                    .as("failed terminal should carry an error_message describing the cause")
                    .isNotBlank();
        }
    }

    // =========================================================================
    // E4 — fan-out: 3 distinct child spawns under the same parent → each gets
    // its own SessionEndedEvent → latched listener observes 3 distinct
    // ChildRunCompletedEvent rows, all with the same parentRunId.
    // =========================================================================

    @Test
    @DisplayName("E4: 3 spawns → 3 ChildRunCompletedEvent fan-out via latch listener")
    void e4_fan_out_three_children() throws InterruptedException {
        String parentRunPid = seedParentRun(tenantId);

        SubAgentRunner.SpawnResult c1 = subAgentRunner.spawn(
                tenantId, parentRunPid, sessionId, "child 1", "interrupt_subtask");
        SubAgentRunner.SpawnResult c2 = subAgentRunner.spawn(
                tenantId, parentRunPid, sessionId, "child 2", "interrupt_subtask");
        SubAgentRunner.SpawnResult c3 = subAgentRunner.spawn(
                tenantId, parentRunPid, sessionId, "child 3", "interrupt_subtask");

        for (String pid : List.of(c1.getChildRunPid(), c2.getChildRunPid(), c3.getChildRunPid())) {
            jdbc.update("UPDATE ab_agent_run SET run_status = 'success', completed_at = NOW(), " +
                            "    updated_at = NOW() WHERE pid = ?", pid);
            eventPublisher.publishEvent(new SessionEndedEvent(
                    tenantId, pid, "aurabot",
                    String.valueOf(testUser.getId()),
                    SessionEndedEvent.TerminalOutcome.SUCCEEDED));
        }

        latchedListener.awaitCount(3, /* timeoutMs */ 5_000);

        List<ChildRunCompletedEvent> events = latchedListener.events();
        assertThat(events).hasSize(3);
        assertThat(events).allMatch(e -> parentRunPid.equals(e.getParentRunId()));
        assertThat(events).allMatch(e -> "succeeded".equals(e.getOutcome()));
        // Each event carries a distinct childRunId — no duplicate fires.
        assertThat(events.stream().map(ChildRunCompletedEvent::getChildRunId).distinct().count())
                .isEqualTo(3);
    }
}
