package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.memory.SessionEndedEvent;
import com.auraboot.framework.agent.service.ChildRunCompletedEvent;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.integration.TestIdGenerator;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Backlog D.3 — child run cost / tokens reverse rollup integration tests.
 *
 * <p>Drives the full T1+D.3 chain through real Spring event publication:
 * {@code SessionEndedEvent} → {@code ParentJoinService.onSessionEnded} →
 * {@code ChildRunCompletedEvent} → {@code ParentJoinService.onChildCompleted}
 * → atomic UPDATE on {@code ab_agent_run.child_aggregate_*}.
 *
 * <p>Real PostgreSQL only — no @Mock of the listener / event publisher / JDBC.
 * Each test seeds a parent + child {@code ab_agent_run} row, fires a real
 * {@code SessionEndedEvent}, and asserts the parent's rollup columns.
 *
 * <p>Cases:
 * <ul>
 *   <li>Case A: single child terminal → parent rolls up cost + tokens</li>
 *   <li>Case B: 3 children sequentially → parent aggregates the sum</li>
 *   <li>Case C: parent already terminal before child completes → still rolls up
 *       (late-arrival is the whole point of D.3)</li>
 *   <li>Case D: cross-tenant event payload → defensive UPDATE filter prevents
 *       rollup on a parent in a different tenant</li>
 *   <li>Case E: regression — root run with no children → both rollup columns
 *       remain at the schema DEFAULT 0</li>
 *   <li>Case F (T1 regression): root run terminal does NOT publish
 *       ChildRunCompletedEvent — short-circuits on null parent_run_id</li>
 * </ul>
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("ParentJoinService — D.3 child cost reverse rollup")
class ChildCostRollupIntegrationTest extends BaseIntegrationTest {

    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;

    @BeforeEach
    void setup() {
        tenantId = TestIdGenerator.uniqueTenantId();
        MetaContext.setContext(tenantId, testUser.getId(), testUser.getPid(), testUser.getUserName());
    }

    @AfterEach
    void cleanup() {
        if (tenantId != null) {
            jdbc.update("DELETE FROM ab_agent_run WHERE tenant_id = ?", tenantId);
        }
    }

    // =========================================================================
    // Seeding helpers
    // =========================================================================

    /**
     * Seed a parent run row with no parent_run_id. Cost / tokens default to 0.
     */
    private String seedParent(String runStatus) {
        String pid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_run " +
                        "(pid, tenant_id, task_id, agent_id, run_status, started_at, " +
                        " input_tokens, output_tokens, total_cost, " +
                        " created_at, updated_at) " +
                        "VALUES (?, ?, ?, 'demo_agent', ?, NOW(), 0, 0, 0, NOW(), NOW())",
                pid, tenantId, UniqueIdGenerator.generate(), runStatus);
        return pid;
    }

    /**
     * Seed a child run row carrying a parent_run_id, with the given final
     * cost / token totals already written (matching real-world ordering: the
     * AgentRunService writes totals before publishing SessionEndedEvent).
     */
    private String seedChild(String parentPid, BigDecimal cost, int inputTokens, int outputTokens) {
        return seedChildForTenant(tenantId, parentPid, cost, inputTokens, outputTokens);
    }

    private String seedChildForTenant(Long tenant, String parentPid, BigDecimal cost,
                                      int inputTokens, int outputTokens) {
        String pid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_run " +
                        "(pid, tenant_id, task_id, agent_id, run_status, started_at, completed_at, " +
                        " input_tokens, output_tokens, total_cost, parent_run_id, subtask_origin, " +
                        " created_at, updated_at) " +
                        "VALUES (?, ?, ?, 'demo_agent', 'succeeded', NOW() - INTERVAL '1 minute', NOW(), " +
                        " ?, ?, ?, ?, 'delegate_task', NOW(), NOW())",
                pid, tenant, UniqueIdGenerator.generate(), inputTokens, outputTokens, cost,
                parentPid);
        return pid;
    }

    private Map<String, Object> loadParentRow(String parentPid) {
        return jdbc.queryForMap(
                "SELECT child_aggregate_cost, child_aggregate_tokens, run_status " +
                        "FROM ab_agent_run WHERE pid = ? AND tenant_id = ?",
                parentPid, tenantId);
    }

    /**
     * Fire the full T1+D.3 chain by publishing the seed event the real
     * AgentRunService would publish on terminal.
     */
    private void fireSessionEnded(String childPid) {
        eventPublisher.publishEvent(new SessionEndedEvent(
                tenantId, childPid, "demo_agent", testUser.getId().toString(),
                SessionEndedEvent.TerminalOutcome.SUCCEEDED));
    }

    // =========================================================================
    // Case A — single child rolls up
    // =========================================================================

    @Test
    @DisplayName("Case A: single child terminal → parent.child_aggregate_* gets exact totals")
    void caseA_singleChildRollsUp() {
        String parentPid = seedParent("running");
        seedChild(parentPid, new BigDecimal("0.050000"), 1000, 500);

        // Find the child pid back so we can fire the event for it.
        String childPid = jdbc.queryForObject(
                "SELECT pid FROM ab_agent_run WHERE parent_run_id = ? AND tenant_id = ?",
                String.class, parentPid, tenantId);
        fireSessionEnded(childPid);

        Map<String, Object> parent = loadParentRow(parentPid);
        assertThat(((BigDecimal) parent.get("child_aggregate_cost")))
                .isEqualByComparingTo(new BigDecimal("0.050000"));
        assertThat(((Number) parent.get("child_aggregate_tokens")).longValue())
                .isEqualTo(1500L);
    }

    // =========================================================================
    // Case B — three children aggregate
    // =========================================================================

    @Test
    @DisplayName("Case B: three children complete sequentially → parent aggregates the sum")
    void caseB_multipleChildrenAggregate() {
        String parentPid = seedParent("running");
        String c1 = seedChild(parentPid, new BigDecimal("0.010000"), 100, 50);
        String c2 = seedChild(parentPid, new BigDecimal("0.020000"), 200, 100);
        String c3 = seedChild(parentPid, new BigDecimal("0.030000"), 300, 150);

        fireSessionEnded(c1);
        fireSessionEnded(c2);
        fireSessionEnded(c3);

        Map<String, Object> parent = loadParentRow(parentPid);
        assertThat(((BigDecimal) parent.get("child_aggregate_cost")))
                .isEqualByComparingTo(new BigDecimal("0.060000"));
        assertThat(((Number) parent.get("child_aggregate_tokens")).longValue())
                .isEqualTo(900L); // (100+50)+(200+100)+(300+150)
    }

    // =========================================================================
    // Case C — late-arrival: parent already terminal
    // =========================================================================

    @Test
    @DisplayName("Case C: parent already terminal when child completes → still rolls up")
    void caseC_lateArrivalParentTerminal() {
        // Parent has already reached terminal — finance / quota accounting
        // would silently lose the child's cost without D.3.
        String parentPid = seedParent("succeeded");
        String childPid = seedChild(parentPid, new BigDecimal("0.075000"), 700, 300);

        fireSessionEnded(childPid);

        Map<String, Object> parent = loadParentRow(parentPid);
        // run_status untouched — rollup is independent of parent terminal.
        assertThat((String) parent.get("run_status")).isEqualTo("succeeded");
        assertThat(((BigDecimal) parent.get("child_aggregate_cost")))
                .isEqualByComparingTo(new BigDecimal("0.075000"));
        assertThat(((Number) parent.get("child_aggregate_tokens")).longValue())
                .isEqualTo(1000L);
    }

    // =========================================================================
    // Case D — cross-tenant defence
    // =========================================================================

    @Test
    @DisplayName("Case D: cross-tenant child event → no rollup on a parent in a different tenant")
    void caseD_crossTenantDefensiveSkip() {
        // Parent lives in tenantId. Child lives in OTHER tenant but carries the
        // parent's pid — simulates a malformed or replayed event.
        String parentPid = seedParent("running");
        Long otherTenant = TestIdGenerator.uniqueTenantId();
        String childPid = seedChildForTenant(otherTenant, parentPid,
                new BigDecimal("0.999999"), 9999, 9999);

        // Publish event using the OTHER tenant — listener UPDATE filters on
        // tenant_id, so it should match zero rows.
        eventPublisher.publishEvent(new ChildRunCompletedEvent(
                otherTenant, parentPid, childPid, "succeeded",
                new BigDecimal("0.999999"), 19998L));

        Map<String, Object> parent = loadParentRow(parentPid);
        assertThat(((BigDecimal) parent.get("child_aggregate_cost")))
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(((Number) parent.get("child_aggregate_tokens")).longValue())
                .isEqualTo(0L);

        // Cleanup the cross-tenant row we seeded directly.
        jdbc.update("DELETE FROM ab_agent_run WHERE tenant_id = ?", otherTenant);
    }

    // =========================================================================
    // Case E — root run with no children
    // =========================================================================

    @Test
    @DisplayName("Case E: root run with no children → rollup columns stay at DEFAULT 0")
    void caseE_rootRunNoChildren() {
        String parentPid = seedParent("succeeded");

        Map<String, Object> parent = loadParentRow(parentPid);
        assertThat(((BigDecimal) parent.get("child_aggregate_cost")))
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(((Number) parent.get("child_aggregate_tokens")).longValue())
                .isEqualTo(0L);
    }

    // =========================================================================
    // Case F — T1 regression: root run terminal does NOT trigger rollup
    // =========================================================================

    @Test
    @DisplayName("Case F: root run terminal SessionEndedEvent → no ChildRunCompletedEvent fired")
    void caseF_rootRunTerminalShortCircuits() {
        // A run with no parent_run_id should short-circuit in
        // ParentJoinService.onSessionEnded — no child event published, and
        // therefore no rollup attempted on any other row. We verify by
        // confirming the row's own rollup columns stay at 0 even after the
        // event fires (a buggy listener might rollup into the wrong row).
        String rootPid = seedParent("running");
        eventPublisher.publishEvent(new SessionEndedEvent(
                tenantId, rootPid, "demo_agent", testUser.getId().toString(),
                SessionEndedEvent.TerminalOutcome.SUCCEEDED));

        Map<String, Object> parent = loadParentRow(rootPid);
        assertThat(((BigDecimal) parent.get("child_aggregate_cost")))
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(((Number) parent.get("child_aggregate_tokens")).longValue())
                .isEqualTo(0L);
    }
}
