package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.controller.AgentRunController;
import com.auraboot.framework.agent.dto.replay.AgentActionItem;
import com.auraboot.framework.agent.dto.replay.AgentInterruptItem;
import com.auraboot.framework.agent.dto.replay.AgentRunDetail;
import com.auraboot.framework.agent.dto.replay.AgentRunListItem;
import com.auraboot.framework.agent.dto.replay.AgentRunPage;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.integration.TestIdGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Replay UI MVP — {@link AgentRunController} integration tests.
 *
 * <p>Drives the controller via direct method invocation (the URL-prefix
 * admin guard is covered separately in the security suite). Each test seeds
 * realistic {@code ab_agent_run} / {@code ab_agent_action} /
 * {@code ab_agent_interrupt_log} / {@code ab_agent_bif} rows, then asserts the
 * projection back-out matches expectations field-by-field.
 *
 * <p>Coverage:
 * <ul>
 *   <li>{@code list_paginated} — multi-page case, total + page math correct</li>
 *   <li>{@code list_filterByStatus_returnsOnlyMatching} — status predicate</li>
 *   <li>{@code list_filterByParentRunId_returnsOnlyChildren} — parent filter</li>
 *   <li>{@code list_filterByKeyword_returnsMatchingRowsOnly} — substring match</li>
 *   <li>{@code list_intentSummaryFromBif} — BIF LEFT JOIN populates intent</li>
 *   <li>{@code detail_returnsAllSections} — run + actions + interrupts + bif + children</li>
 *   <li>{@code detail_unknownRunId_returns404} — strict not-found</li>
 *   <li>{@code tenant_isolation_otherTenantRunInvisible} — cross-tenant leak guard</li>
 * </ul>
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("AgentRunController — replay UI MVP")
class AgentRunControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired private AgentRunController controller;
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
            jdbc.update("DELETE FROM ab_agent_action WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM ab_agent_interrupt_log WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM ab_agent_bif WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM ab_agent_run WHERE tenant_id = ?", tenantId);
        }
    }

    // =========================================================================
    // Seeding helpers
    // =========================================================================

    private String seedRun(String agentCode, String status) {
        return seedRun(agentCode, status, null, null);
    }

    /**
     * Seed an {@code ab_agent_run}. {@code parentPid} + {@code subtaskOrigin}
     * activate the parent_run_id audit pair (whitelisted values:
     * interrupt_subtask / delegate_task / scheduled_split).
     */
    private String seedRun(String agentCode, String status,
                           String parentPid, String subtaskOrigin) {
        String pid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_run " +
                        "(pid, tenant_id, task_id, agent_id, run_status, started_at, " +
                        " completed_at, duration_ms, total_cost, parent_run_id, subtask_origin, " +
                        " created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, NOW() - INTERVAL '5 minutes', " +
                        "        CASE WHEN ? IN ('succeeded','failed','cancelled') THEN NOW() ELSE NULL END, " +
                        "        CASE WHEN ? IN ('succeeded','failed','cancelled') THEN 1234 ELSE NULL END, " +
                        "        0.012345, ?, ?, NOW(), NOW())",
                pid, tenantId, UniqueIdGenerator.generate(), agentCode, status,
                status, status, parentPid, subtaskOrigin);
        return pid;
    }

    private String seedAction(String runPid, String actionCode, String status) {
        String pid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_action " +
                        "(pid, tenant_id, run_id, action_code, action_type, target_model, " +
                        " action_status, executed_at, before_snapshot, after_snapshot, " +
                        " field_changes, risk_level, cost_usd) " +
                        "VALUES (?, ?, ?, ?, 'data_write', 'crm_account', ?, NOW(), " +
                        " '{\"name\":\"old\"}'::jsonb, '{\"name\":\"new\"}'::jsonb, " +
                        " '[{\"field\":\"name\",\"from\":\"old\",\"to\":\"new\"}]'::jsonb, " +
                        " 'L1', 0.000123)",
                pid, tenantId, runPid, actionCode, status);
        return pid;
    }

    private String seedInterrupt(String runPid, String subPolicy, String actionTaken) {
        String pid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_interrupt_log " +
                        "(pid, tenant_id, session_id, active_run_id, new_message_excerpt, " +
                        " sub_policy, classifier_tier, confidence, reason, action_taken, created_at) " +
                        "VALUES (?, ?, ?, ?, '停一下，先做另一件事', ?, 'keyword', 0.95, " +
                        " 'matched zh stop keyword', ?, NOW())",
                pid, tenantId, "sess_" + System.nanoTime(), runPid, subPolicy, actionTaken);
        return pid;
    }

    private void seedBif(String runPid, String intent, String dispatchedSkill) {
        String pid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_bif " +
                        "(pid, tenant_id, run_id, nl_input, intent, primary_object, " +
                        " risk_level, confidence, dispatched_skill, channel, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, 'crm_account', 'L1', " +
                        " '{\"object\":0.9,\"intent\":0.95}'::jsonb, ?, 'web', NOW())",
                pid, tenantId, runPid, "查询最近活跃客户", intent, dispatchedSkill);
    }

    // =========================================================================
    // List endpoint
    // =========================================================================

    @Test
    @DisplayName("list paginates: total + page slice math correct")
    void list_paginated() {
        for (int i = 0; i < 5; i++) {
            seedRun("aurabot", "succeeded");
        }

        ApiResponse<AgentRunPage> page0 = controller.list(0, 2, null, null, null, null);
        assertThat(page0.isSuccess()).isTrue();
        AgentRunPage p0 = page0.getData();
        assertThat(p0.getTotal()).isEqualTo(5L);
        assertThat(p0.getPage()).isEqualTo(0);
        assertThat(p0.getSize()).isEqualTo(2);
        assertThat(p0.getItems()).hasSize(2);

        ApiResponse<AgentRunPage> page2 = controller.list(2, 2, null, null, null, null);
        AgentRunPage p2 = page2.getData();
        assertThat(p2.getItems()).hasSize(1); // 5 rows / size 2 → page 2 has the last 1
        assertThat(p2.getTotal()).isEqualTo(5L);
    }

    @Test
    @DisplayName("list filters by status — only matching rows returned")
    void list_filterByStatus_returnsOnlyMatching() {
        String okPid = seedRun("aurabot", "succeeded");
        seedRun("aurabot", "failed");
        seedRun("aurabot", "running");

        ApiResponse<AgentRunPage> resp = controller.list(0, 50, "succeeded", null, null, null);
        AgentRunPage p = resp.getData();
        assertThat(p.getTotal()).isEqualTo(1L);
        assertThat(p.getItems()).extracting(AgentRunListItem::getRunId).containsExactly(okPid);
        assertThat(p.getItems().get(0).getRunStatus()).isEqualTo("succeeded");
    }

    @Test
    @DisplayName("list filters by parentRunId — returns only child runs")
    void list_filterByParentRunId_returnsOnlyChildren() {
        String parent = seedRun("aurabot", "running");
        String childA = seedRun("aurabot", "succeeded", parent, "interrupt_subtask");
        String childB = seedRun("aurabot", "running", parent, "delegate_task");
        // unrelated other-parent run
        seedRun("aurabot", "succeeded");

        ApiResponse<AgentRunPage> resp = controller.list(0, 50, null, null, parent, null);
        AgentRunPage p = resp.getData();
        assertThat(p.getTotal()).isEqualTo(2L);
        assertThat(p.getItems())
                .extracting(AgentRunListItem::getRunId)
                .containsExactlyInAnyOrder(childA, childB);
        assertThat(p.getItems())
                .extracting(AgentRunListItem::getParentRunId)
                .containsOnly(parent);
        assertThat(p.getItems())
                .extracting(AgentRunListItem::getSubtaskOrigin)
                .containsExactlyInAnyOrder("interrupt_subtask", "delegate_task");
    }

    @Test
    @DisplayName("list filters by keyword (case-insensitive substring across pid / agent_id / task_id)")
    void list_filterByKeyword_returnsMatchingRowsOnly() {
        String hit = seedRun("aurabot-special", "running");
        seedRun("aurabot", "running");

        ApiResponse<AgentRunPage> resp = controller.list(0, 50, null, null, null, "SPECIAL");
        AgentRunPage p = resp.getData();
        assertThat(p.getTotal()).isEqualTo(1L);
        assertThat(p.getItems()).extracting(AgentRunListItem::getRunId).containsExactly(hit);
    }

    @Test
    @DisplayName("list surfaces intent summary via LEFT JOIN ab_agent_bif")
    void list_intentSummaryFromBif() {
        String pid = seedRun("aurabot", "succeeded");
        seedBif(pid, "QUERY_RECORDS", "crm.account.search");

        ApiResponse<AgentRunPage> resp = controller.list(0, 10, null, null, null, null);
        AgentRunListItem only = resp.getData().getItems().get(0);
        assertThat(only.getRunId()).isEqualTo(pid);
        assertThat(only.getIntentSummary()).isEqualTo("QUERY_RECORDS");
    }

    // =========================================================================
    // Detail endpoint
    // =========================================================================

    @Test
    @DisplayName("detail returns run + actions + interrupts + child runs + bif")
    void detail_returnsAllSections() {
        String parent = seedRun("aurabot", "running");
        String child = seedRun("aurabot", "succeeded", parent, "interrupt_subtask");
        String actionA = seedAction(parent, "crm.account.update", "success");
        String actionB = seedAction(parent, "crm.account.notify", "success");
        String interruptPid = seedInterrupt(parent, "insert_subtask", "subtask_enqueued");
        seedBif(parent, "UPDATE_RECORD", "crm.account.update");

        ApiResponse<AgentRunDetail> resp = controller.detail(parent);
        assertThat(resp.isSuccess()).isTrue();
        AgentRunDetail d = resp.getData();

        // Run header
        assertThat(d.getRun()).isNotNull();
        assertThat(d.getRun().getRunId()).isEqualTo(parent);
        assertThat(d.getRun().getRunStatus()).isEqualTo("running");
        assertThat(d.getRun().getIntentSummary()).isEqualTo("UPDATE_RECORD");

        // Actions
        assertThat(d.getActions()).hasSize(2);
        assertThat(d.getActions())
                .extracting(AgentActionItem::getPid)
                .containsExactlyInAnyOrder(actionA, actionB);
        AgentActionItem first = d.getActions().get(0);
        assertThat(first.getActionCode()).startsWith("crm.account.");
        assertThat(first.getTargetModel()).isEqualTo("crm_account");
        assertThat(first.getRiskLevel()).isEqualTo("L1");
        assertThat(first.getBeforeSnapshot()).contains("\"name\"").contains("old");
        assertThat(first.getAfterSnapshot()).contains("new");
        assertThat(first.getFieldChanges()).contains("\"field\"").contains("\"name\"");

        // Interrupts
        assertThat(d.getInterruptLog()).hasSize(1);
        AgentInterruptItem ic = d.getInterruptLog().get(0);
        assertThat(ic.getPid()).isEqualTo(interruptPid);
        assertThat(ic.getSubPolicy()).isEqualTo("insert_subtask");
        assertThat(ic.getActionTaken()).isEqualTo("subtask_enqueued");

        // Child runs
        assertThat(d.getChildRuns()).hasSize(1);
        assertThat(d.getChildRuns().get(0).getRunId()).isEqualTo(child);
        assertThat(d.getChildRuns().get(0).getSubtaskOrigin()).isEqualTo("interrupt_subtask");

        // BIF
        assertThat(d.getBif()).isNotNull();
        assertThat(d.getBif().getIntent()).isEqualTo("UPDATE_RECORD");
        assertThat(d.getBif().getDispatchedSkill()).isEqualTo("crm.account.update");
        assertThat(d.getBif().getPrimaryObject()).isEqualTo("crm_account");
        assertThat(d.getBif().getChannel()).isEqualTo("web");
    }

    @Test
    @DisplayName("detail unknown runId -> 404")
    void detail_unknownRunId_returns404() {
        ApiResponse<AgentRunDetail> resp = controller.detail("RUN_does_not_exist");
        assertThat(resp.getCode()).isEqualTo("404");
        assertThat(resp.getMessage()).isEqualTo("agent_run_not_found");
        assertThat(resp.getData()).isNull();
    }

    @Test
    @DisplayName("durationMs: stored value preferred, derived fallback, then 0")
    void list_durationMs_threeBranches() {
        // Branch 1: stored duration_ms = 5000 (bound as BIGINT via Long literal).
        String storedPid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_run " +
                        "(pid, tenant_id, task_id, agent_id, run_status, started_at, " +
                        " completed_at, duration_ms, total_cost, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 'aurabot', 'succeeded', NOW() - INTERVAL '5 minutes', " +
                        "        NOW(), ?, 0.001, NOW(), NOW())",
                storedPid, tenantId, UniqueIdGenerator.generate(), 5000L);

        // Branch 2: duration_ms NULL, but created_at + completed_at present
        // (fixed 7-second delta) — controller derives 7000 from the timestamps.
        String derivedPid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_run " +
                        "(pid, tenant_id, task_id, agent_id, run_status, started_at, " +
                        " completed_at, duration_ms, total_cost, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 'aurabot', 'succeeded', " +
                        "        TIMESTAMP '2026-01-01 00:00:00', " +
                        "        TIMESTAMP '2026-01-01 00:00:07', " +
                        "        NULL, 0.001, " +
                        "        TIMESTAMP '2026-01-01 00:00:00', " +
                        "        TIMESTAMP '2026-01-01 00:00:07')",
                derivedPid, tenantId, UniqueIdGenerator.generate());

        // Branch 3: duration_ms NULL and completed_at NULL — running row, falls
        // through to the 0L default.
        String zeroPid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_run " +
                        "(pid, tenant_id, task_id, agent_id, run_status, started_at, " +
                        " completed_at, duration_ms, total_cost, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 'aurabot', 'running', NOW(), " +
                        "        NULL, NULL, 0.001, NOW(), NOW())",
                zeroPid, tenantId, UniqueIdGenerator.generate());

        ApiResponse<AgentRunPage> resp = controller.list(0, 50, null, null, null, null);
        assertThat(resp.isSuccess()).isTrue();
        AgentRunPage page = resp.getData();
        assertThat(page.getTotal()).isEqualTo(3L);

        AgentRunListItem stored = page.getItems().stream()
                .filter(i -> storedPid.equals(i.getRunId())).findFirst().orElseThrow();
        AgentRunListItem derived = page.getItems().stream()
                .filter(i -> derivedPid.equals(i.getRunId())).findFirst().orElseThrow();
        AgentRunListItem zero = page.getItems().stream()
                .filter(i -> zeroPid.equals(i.getRunId())).findFirst().orElseThrow();

        // Branch 1: stored value is preferred and propagated unchanged.
        assertThat(stored.getDurationMs())
                .as("non-null duration_ms must propagate as Long without ClassCastException")
                .isEqualTo(5000L);

        // Branch 2: derived from completed_at - created_at = 7 seconds.
        assertThat(derived.getDurationMs())
                .as("when duration_ms NULL, fallback derives from timestamps (7s = 7000ms)")
                .isEqualTo(7000L);

        // Branch 3: nothing to derive from — defaults to 0.
        assertThat(zero.getDurationMs())
                .as("when both duration_ms and completed_at NULL, durationMs defaults to 0")
                .isEqualTo(0L);
    }

    @Test
    @DisplayName("tenant isolation — run from another tenant invisible to caller")
    void tenant_isolation_otherTenantRunInvisible() {
        Long otherTenant = TestIdGenerator.uniqueTenantId();
        String otherPid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_run " +
                        "(pid, tenant_id, task_id, agent_id, run_status, started_at, " +
                        " created_at, updated_at) " +
                        "VALUES (?, ?, ?, 'aurabot', 'succeeded', NOW(), NOW(), NOW())",
                otherPid, otherTenant, UniqueIdGenerator.generate());

        try {
            // Caller is on tenantId, querying must see zero rows.
            ApiResponse<AgentRunPage> listResp = controller.list(0, 50, null, null, null, null);
            assertThat(listResp.getData().getTotal()).isEqualTo(0L);
            assertThat(listResp.getData().getItems()).isEmpty();

            // Detail by another tenant's pid must surface as not-found, NOT leak.
            ApiResponse<AgentRunDetail> detailResp = controller.detail(otherPid);
            assertThat(detailResp.getCode()).isEqualTo("404");
            assertThat(detailResp.getData()).isNull();
        } finally {
            jdbc.update("DELETE FROM ab_agent_run WHERE tenant_id = ?", otherTenant);
        }
    }
}
