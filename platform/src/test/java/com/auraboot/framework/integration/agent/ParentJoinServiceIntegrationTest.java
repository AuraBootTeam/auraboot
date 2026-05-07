package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.memory.SessionEndedEvent;
import com.auraboot.framework.agent.service.ChildRunCompletedEvent;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A.1 follow-up — ACP P0-6 / P1: bridge {@link SessionEndedEvent} (fired when a
 * run reaches a terminal state) into {@link ChildRunCompletedEvent} (fired only
 * for child runs that have a {@code parent_run_id}). The bridge is implemented
 * by {@code ParentJoinService.onSessionEnded(SessionEndedEvent)} via a
 * synchronous {@code @EventListener}.
 *
 * <p>Reference source files (read-only):
 * <ul>
 *   <li>{@code platform/src/main/java/com/auraboot/framework/agent/service/ParentJoinService.java}</li>
 *   <li>{@code platform/src/main/java/com/auraboot/framework/agent/service/ChildRunCompletedEvent.java}</li>
 *   <li>{@code platform/src/main/java/com/auraboot/framework/agent/memory/SessionEndedEvent.java}</li>
 * </ul>
 *
 * <p>Cases covered:
 * <ul>
 *   <li>P1 — child run with parent_run_id terminal (succeeded) → exactly one
 *       {@link ChildRunCompletedEvent} published, payload (parentRunId,
 *       childRunId, outcome, tenantId) carries the values from the
 *       {@code ab_agent_run} row, NOT from the spawning thread.</li>
 *   <li>P2 — child run terminal with CANCELLED outcome → outcome label is
 *       lower-case "cancelled" on the bridged event (covers the cancel/fail
 *       fan-out spec asks for; no missing terminals).</li>
 *   <li>P3 — root run (parent_run_id IS NULL) terminal → bridge short-circuits
 *       and emits NO {@code ChildRunCompletedEvent}.</li>
 *   <li>P4 — listener uses the {@code ab_agent_run.tenant_id} of the child row,
 *       not the publisher-supplied {@code tenantId}, so a SessionEndedEvent
 *       carrying a different tenant is treated as caller bug data and the
 *       bridged event still reflects the row's true tenant. This protects the
 *       "strict tenant boundary" red-line asked for by the spec.</li>
 *   <li>P5 — child run id pointing at a non-existent row → no bridged event
 *       (silent skip; no exceptions).</li>
 *   <li>P6 — failed terminal → outcome="failed" lower-case label (covers the
 *       third terminal enum branch).</li>
 * </ul>
 *
 * <p>Test contract:
 * <ul>
 *   <li>Real Postgres + Redis via {@link BaseIntegrationTest}.</li>
 *   <li>Spring's {@code @RecordApplicationEvents} captures every published
 *       {@link org.springframework.context.ApplicationEvent} so the test can
 *       count and assert {@link ChildRunCompletedEvent} instances without
 *       installing a custom listener (would create an inter-test ordering
 *       hazard with the other tests sharing the same context).</li>
 *   <li>{@code @Commit} so the seeded {@code ab_agent_run} rows are visible
 *       to the {@code @EventListener} thread which queries them via
 *       {@link JdbcTemplate}; cleanup happens in {@code @AfterEach} by
 *       deleting all rows for our tenant.</li>
 *   <li>Each test uses a unique {@code tenantId} (random offset) so parallel
 *       Surefire runs cannot collide on shared fixture rows.</li>
 * </ul>
 */
@Commit
@RecordApplicationEvents
@Transactional(propagation = Propagation.NEVER)
@DisplayName("A.1 — ParentJoinService bridges SessionEndedEvent → ChildRunCompletedEvent")
class ParentJoinServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ApplicationEvents events;

    private Long tenantId;
    private Long otherTenantId;

    @BeforeEach
    void setup() {
        tenantId = 9_810_000L + System.nanoTime() % 100_000;
        otherTenantId = tenantId + 7L;
        MetaContext.setContext(tenantId, testUser.getId(), testUser.getPid(), testUser.getUserName());
    }

    @AfterEach
    void cleanup() {
        // Bound by tenant — each test allocated its own tenantId so this is
        // safe and does not race with the BaseIntegrationTest fixture rows.
        jdbc.update("DELETE FROM ab_agent_run WHERE tenant_id IN (?, ?)", tenantId, otherTenantId);
        jdbc.update("DELETE FROM ab_agent_task WHERE tenant_id IN (?, ?)", tenantId, otherTenantId);
        MetaContext.clear();
    }

    /**
     * Seed an {@code ab_agent_task} + {@code ab_agent_run} row pair under the
     * given tenant; the run row optionally carries a {@code parent_run_id}.
     * Returns the run pid.
     */
    private String seedRun(Long runTenantId, String parentRunId) {
        String runPid = UniqueIdGenerator.generate();
        String taskPid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_task (pid, tenant_id, title, task_status, " +
                        " assignee_type, assignee_id, created_at, updated_at, created_by) " +
                        "VALUES (?, ?, 'parent join test', 'in_progress', 'agent', 'aurabot', " +
                        "        NOW(), NOW(), ?)",
                taskPid, runTenantId, testUser.getId());
        jdbc.update("INSERT INTO ab_agent_run (pid, tenant_id, task_id, agent_id, run_status, " +
                        " parent_run_id, started_at, created_at, updated_at, created_by) " +
                        "VALUES (?, ?, ?, 'aurabot', 'running', ?, NOW(), NOW(), NOW(), ?)",
                runPid, runTenantId, taskPid, parentRunId, testUser.getId());
        return runPid;
    }

    /** Stream all ChildRunCompletedEvent instances captured so far. */
    private List<ChildRunCompletedEvent> capturedChildEvents() {
        return events.stream(ChildRunCompletedEvent.class).toList();
    }

    // =========================================================================
    // P1 — happy path: child run with parent_run_id terminal SUCCEEDED →
    // ChildRunCompletedEvent published with row-derived parent/child/tenant
    // and lowercase outcome label.
    // =========================================================================

    @Test
    @DisplayName("P1: child run SUCCEEDED → ChildRunCompletedEvent fires once with row-derived payload")
    void p1_child_succeeded_publishes_bridged_event() {
        // Seed parent (no parent_run_id) and child (parent_run_id = parent.pid)
        String parentRunPid = seedRun(tenantId, /* parent_run_id */ null);
        String childRunPid = seedRun(tenantId, parentRunPid);

        // Flip child to terminal state (mirrors what AgentRunService does
        // before publishing SessionEndedEvent in production).
        jdbc.update("UPDATE ab_agent_run SET run_status = 'success', completed_at = NOW(), " +
                        "    updated_at = NOW() WHERE pid = ?", childRunPid);

        eventPublisher.publishEvent(new SessionEndedEvent(
                tenantId, childRunPid, "aurabot",
                String.valueOf(testUser.getId()),
                SessionEndedEvent.TerminalOutcome.SUCCEEDED));

        // The @EventListener on ParentJoinService is synchronous, so by the
        // time publishEvent returns the bridged event has already been published
        // (or short-circuited). No sleep needed.
        List<ChildRunCompletedEvent> child = capturedChildEvents();
        assertThat(child).as("exactly one ChildRunCompletedEvent published").hasSize(1);

        ChildRunCompletedEvent ev = child.get(0);
        assertThat(ev.getParentRunId()).isEqualTo(parentRunPid);
        assertThat(ev.getChildRunId()).isEqualTo(childRunPid);
        assertThat(ev.getOutcome()).isEqualTo("succeeded");
        // tenantId on the bridged event must match the run row tenant — the
        // listener derives it from ab_agent_run.tenant_id, NOT from the
        // SessionEndedEvent payload's tenantId field. (Same value here, but
        // P4 below verifies the divergence path explicitly.)
        assertThat(ev.getTenantId()).isEqualTo(tenantId);
        assertThat(ev.getEventType()).isEqualTo("agent_child_run_completed");
        assertThat(ev.getModelCode()).isEqualTo("ab_agent_run");
        assertThat(ev.getRecordId()).isEqualTo(childRunPid);
        // Payload mirrors the constructor args (no fallbacks).
        assertThat(ev.getPayload())
                .containsEntry("parentRunId", parentRunPid)
                .containsEntry("childRunId", childRunPid)
                .containsEntry("outcome", "succeeded");
    }

    // =========================================================================
    // P2 — cancelled terminal: outcome label is lower-case "cancelled".
    // =========================================================================

    @Test
    @DisplayName("P2: child run CANCELLED → bridged event outcome='cancelled' (lower-case)")
    void p2_child_cancelled_outcome_lowercase() {
        String parentRunPid = seedRun(tenantId, null);
        String childRunPid = seedRun(tenantId, parentRunPid);

        jdbc.update("UPDATE ab_agent_run SET run_status = 'cancelled', completed_at = NOW(), " +
                        "    updated_at = NOW() WHERE pid = ?", childRunPid);

        eventPublisher.publishEvent(new SessionEndedEvent(
                tenantId, childRunPid, "aurabot",
                String.valueOf(testUser.getId()),
                SessionEndedEvent.TerminalOutcome.CANCELLED));

        List<ChildRunCompletedEvent> child = capturedChildEvents();
        assertThat(child).hasSize(1);
        ChildRunCompletedEvent ev = child.get(0);
        assertThat(ev.getOutcome())
                .as("outcome must be lower-case mirror of SessionEndedEvent.TerminalOutcome.CANCELLED")
                .isEqualTo("cancelled");
        assertThat(ev.getParentRunId()).isEqualTo(parentRunPid);
        assertThat(ev.getChildRunId()).isEqualTo(childRunPid);
    }

    // =========================================================================
    // P3 — root run (no parent_run_id) terminal → bridge short-circuits, NO
    // ChildRunCompletedEvent published.
    // =========================================================================

    @Test
    @DisplayName("P3: root run terminal (parent_run_id IS NULL) → no bridged event")
    void p3_root_run_no_bridged_event() {
        String rootRunPid = seedRun(tenantId, /* no parent */ null);

        jdbc.update("UPDATE ab_agent_run SET run_status = 'success', completed_at = NOW(), " +
                        "    updated_at = NOW() WHERE pid = ?", rootRunPid);

        eventPublisher.publishEvent(new SessionEndedEvent(
                tenantId, rootRunPid, "aurabot",
                String.valueOf(testUser.getId()),
                SessionEndedEvent.TerminalOutcome.SUCCEEDED));

        List<ChildRunCompletedEvent> child = capturedChildEvents();
        assertThat(child)
                .as("root run has no parent_run_id, listener must short-circuit and emit nothing")
                .isEmpty();
    }

    // =========================================================================
    // P4 — strict tenant boundary: listener trusts ab_agent_run.tenant_id, not
    // the SessionEndedEvent.tenantId. Even if a publisher claims a different
    // tenant on the SessionEndedEvent, the bridged ChildRunCompletedEvent must
    // carry the row's true tenant — otherwise downstream tenant-scoped listeners
    // would receive cross-tenant notifications.
    // =========================================================================

    @Test
    @DisplayName("P4: bridged event carries row tenant_id, not the SessionEndedEvent payload tenantId")
    void p4_listener_derives_tenant_from_row() {
        // Parent + child in tenantId. Publisher (mistakenly?) labels the
        // SessionEndedEvent with otherTenantId. The bridge must still emit
        // a ChildRunCompletedEvent scoped to the row's true tenant — not the
        // publisher-supplied one.
        String parentRunPid = seedRun(tenantId, null);
        String childRunPid = seedRun(tenantId, parentRunPid);

        jdbc.update("UPDATE ab_agent_run SET run_status = 'success', completed_at = NOW(), " +
                        "    updated_at = NOW() WHERE pid = ?", childRunPid);

        eventPublisher.publishEvent(new SessionEndedEvent(
                otherTenantId, childRunPid, "aurabot",
                String.valueOf(testUser.getId()),
                SessionEndedEvent.TerminalOutcome.SUCCEEDED));

        List<ChildRunCompletedEvent> child = capturedChildEvents();
        assertThat(child).hasSize(1);
        ChildRunCompletedEvent ev = child.get(0);
        assertThat(ev.getTenantId())
                .as("listener must read tenant_id from the run row, not the publisher's payload")
                .isEqualTo(tenantId)
                .isNotEqualTo(otherTenantId);
        assertThat(ev.getParentRunId()).isEqualTo(parentRunPid);
        assertThat(ev.getChildRunId()).isEqualTo(childRunPid);
    }

    // =========================================================================
    // P5 — missing run row (e.g. already deleted): bridge silently skips,
    // does NOT throw, no event.
    // =========================================================================

    @Test
    @DisplayName("P5: SessionEndedEvent for non-existent run id → silent skip, no event, no exception")
    void p5_missing_run_silent_skip() {
        String ghostRunPid = UniqueIdGenerator.generate();

        eventPublisher.publishEvent(new SessionEndedEvent(
                tenantId, ghostRunPid, "aurabot",
                String.valueOf(testUser.getId()),
                SessionEndedEvent.TerminalOutcome.SUCCEEDED));

        List<ChildRunCompletedEvent> child = capturedChildEvents();
        assertThat(child).as("non-existent run id must not produce a bridged event").isEmpty();
    }

    // =========================================================================
    // P6 — FAILED terminal completes the third branch of TerminalOutcome.
    // =========================================================================

    @Test
    @DisplayName("P6: child run FAILED → bridged outcome='failed'")
    void p6_child_failed_outcome_lowercase() {
        String parentRunPid = seedRun(tenantId, null);
        String childRunPid = seedRun(tenantId, parentRunPid);

        jdbc.update("UPDATE ab_agent_run SET run_status = 'failed', completed_at = NOW(), " +
                        "    updated_at = NOW() WHERE pid = ?", childRunPid);

        eventPublisher.publishEvent(new SessionEndedEvent(
                tenantId, childRunPid, "aurabot",
                String.valueOf(testUser.getId()),
                SessionEndedEvent.TerminalOutcome.FAILED));

        List<ChildRunCompletedEvent> child = capturedChildEvents();
        assertThat(child).hasSize(1);
        assertThat(child.get(0).getOutcome()).isEqualTo("failed");
        assertThat(child.get(0).getParentRunId()).isEqualTo(parentRunPid);
        assertThat(child.get(0).getChildRunId()).isEqualTo(childRunPid);
    }
}
