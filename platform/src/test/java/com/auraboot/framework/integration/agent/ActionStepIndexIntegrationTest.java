package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.service.ActionRecorder;
import com.auraboot.framework.agent.service.StepContext;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: ActionRecorder reads StepContext ThreadLocal and stamps
 * step_index on read-Action rows. Spec §1 invariant: step_index ↔ execution_plan[i]
 * one-to-one correspondence.
 */
@Commit
@DisplayName("ACP ActionRecorder — step_index threading")
class ActionStepIndexIntegrationTest extends BaseIntegrationTest {

    @Autowired private ActionRecorder actionRecorder;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;
    private String nqCode;

    @BeforeEach
    void setup() {
        tenantId = 9_9001L + System.nanoTime() % 1000;
        MetaContext.setContext(tenantId, testUser.getId(), testUser.getPid(), testUser.getUserName());
        // ActionRecorder.recordReadAction needs an ab_named_query row to resolve model from from_sql.
        nqCode = "test_nq_" + System.nanoTime();
        jdbc.update("INSERT INTO ab_named_query (pid, tenant_id, code, from_sql, status, current_version) " +
                        "VALUES (?, ?, ?, ?, 'published', 1)",
                "nq-" + nqCode, tenantId, nqCode, "SELECT * FROM mt_crm_lead");
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_action WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_named_query WHERE tenant_id = ?", tenantId);
        StepContext.clear();
    }

    @Test
    @DisplayName("recordReadAction stamps current StepContext.stepIndex")
    void readAction_captures_step_index() {
        String runPid = com.auraboot.framework.common.util.UniqueIdGenerator.generate();

        StepContext.setStepIndex(3);
        String actionPid = actionRecorder.recordReadAction(
                tenantId, runPid, nqCode, null,
                java.util.Map.of(), 5, null);

        assertThat(actionPid).as("read action must persist").isNotNull();

        Integer stepIndex = jdbc.queryForObject(
                "SELECT step_index FROM ab_agent_action WHERE pid = ?", Integer.class, actionPid);
        assertThat(stepIndex).as("step_index must match StepContext").isEqualTo(3);
    }

    @Test
    @DisplayName("recordReadAction leaves step_index NULL when StepContext is empty")
    void readAction_null_step_index_without_context() {
        String runPid = com.auraboot.framework.common.util.UniqueIdGenerator.generate();

        StepContext.clear();
        String actionPid = actionRecorder.recordReadAction(
                tenantId, runPid, nqCode, null,
                java.util.Map.of(), 0, null);

        Integer stepIndex = jdbc.queryForObject(
                "SELECT step_index FROM ab_agent_action WHERE pid = ?", Integer.class, actionPid);
        assertThat(stepIndex).as("no StepContext → step_index stays null").isNull();
    }

    @Test
    @DisplayName("sequential read actions carry incrementing step_index across steps")
    void actions_from_different_steps_carry_different_indices() {
        String runPid = com.auraboot.framework.common.util.UniqueIdGenerator.generate();

        StepContext.setStepIndex(0);
        String a0 = actionRecorder.recordReadAction(tenantId, runPid, nqCode, null,
                java.util.Map.of(), 1, null);

        StepContext.setStepIndex(1);
        String a1 = actionRecorder.recordReadAction(tenantId, runPid, "crm_account_list", null,
                java.util.Map.of(), 2, null);

        StepContext.setStepIndex(2);
        String a2 = actionRecorder.recordReadAction(tenantId, runPid, "crm_contact_list", null,
                java.util.Map.of(), 3, null);

        assertThat(jdbc.queryForObject(
                "SELECT step_index FROM ab_agent_action WHERE pid = ?", Integer.class, a0)).isEqualTo(0);
        assertThat(jdbc.queryForObject(
                "SELECT step_index FROM ab_agent_action WHERE pid = ?", Integer.class, a1)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT step_index FROM ab_agent_action WHERE pid = ?", Integer.class, a2)).isEqualTo(2);
    }
}
