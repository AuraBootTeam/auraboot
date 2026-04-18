package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.service.ShadowRunScheduler;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
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
 * PR-37: ShadowRunScheduler — periodic driver for Shadow Mode execution.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("ShadowRunScheduler (PR-37)")
class ShadowRunSchedulerIntegrationTest extends BaseIntegrationTest {

    @Autowired private ShadowRunScheduler scheduler;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;

    @BeforeEach
    void setup() {
        tenantId = 9_550_000L + System.nanoTime() % 100_000;
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_shadow_run WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_agent_skill_draft WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_agent_action WHERE tenant_id = ?", tenantId);
    }

    private String seedDraft(String status, String yaml, String derivedJson) {
        String pid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_skill_draft " +
                        "(pid, tenant_id, draft_skill_code, source_pattern_hash, contract_yaml, " +
                        " derived_from_runs, status, created_at, reviewed_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, NOW(), NOW())",
                pid, tenantId, "auto.t." + pid.substring(0, 6), "h_" + pid, yaml,
                derivedJson, status);
        return pid;
    }

    private void seedAction(String runId) {
        jdbc.update("INSERT INTO ab_agent_action " +
                        "(pid, tenant_id, run_id, action_code, action_type, target_model, " +
                        " action_status, executed_at, updated_at) " +
                        "VALUES (?, ?, ?, 'crm.lead.list', 'query', 'crm_lead', " +
                        " 'success', NOW() - INTERVAL '100 milliseconds', NOW())",
                UniqueIdGenerator.generate(), tenantId, runId);
    }

    @Test
    @DisplayName("eligible REVIEWED_OK draft → records shadow runs and flips to SHADOW_RUNNING")
    void eligible_draft_runs_and_transitions() {
        String run1 = "RUN1" + System.nanoTime();
        String run2 = "RUN2" + System.nanoTime();
        seedAction(run1);
        seedAction(run2);

        String yaml = "substrate: dsl\naction_type: query\ntool_refs:\n  - nq_leads\n";
        String derived = "[{\"run_id\":\"" + run1 + "\"},{\"run_id\":\"" + run2 + "\"}]";
        String draftPid = seedDraft("REVIEWED_OK", yaml, derived);

        int executed = scheduler.runOnce();
        assertThat(executed).isGreaterThanOrEqualTo(1);

        String status = jdbc.queryForObject(
                "SELECT status FROM ab_agent_skill_draft WHERE pid = ?", String.class, draftPid);
        assertThat(status).isEqualTo("SHADOW_RUNNING");

        Integer shadowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_shadow_run WHERE draft_id = ?",
                Integer.class, draftPid);
        assertThat(shadowCount).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("idempotent — same pair (draft, original_run) not shadowed twice")
    void idempotent_on_rerun() {
        String run1 = "RUNA" + System.nanoTime();
        seedAction(run1);
        String yaml = "substrate: dsl\naction_type: query\ntool_refs:\n  - nq_leads\n";
        String draftPid = seedDraft("REVIEWED_OK", yaml,
                "[{\"run_id\":\"" + run1 + "\"}]");

        int firstPass = scheduler.runOnce();
        int secondPass = scheduler.runOnce();
        assertThat(firstPass).isGreaterThanOrEqualTo(1);
        assertThat(secondPass).isZero();

        Integer shadowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_shadow_run WHERE draft_id = ?",
                Integer.class, draftPid);
        assertThat(shadowCount).isEqualTo(1);
    }

    @Test
    @DisplayName("DRAFT_PENDING_REVIEW drafts are not picked up")
    void pending_review_ignored() {
        String run1 = "RUNP" + System.nanoTime();
        seedAction(run1);
        String yaml = "substrate: dsl\naction_type: query\ntool_refs:\n  - nq_leads\n";
        String draftPid = seedDraft("DRAFT_PENDING_REVIEW", yaml,
                "[{\"run_id\":\"" + run1 + "\"}]");

        int executed = scheduler.runOnce();

        Integer shadowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_shadow_run WHERE draft_id = ?",
                Integer.class, draftPid);
        assertThat(shadowCount).isZero();
        // executed tally may include other tenants' drafts; only our draft-scope is asserted.
        assertThat(executed).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("ineligible draft (write substrate, no dry-run support) produces no shadow rows")
    void ineligible_write_no_runs() {
        String run1 = "RUNW" + System.nanoTime();
        seedAction(run1);
        String yaml = "substrate: dsl\naction_type: update\ntool_refs:\n  - cmd_update_lead\n";
        String draftPid = seedDraft("REVIEWED_OK", yaml,
                "[{\"run_id\":\"" + run1 + "\"}]");

        scheduler.runOnce();

        Integer shadowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_shadow_run WHERE draft_id = ?",
                Integer.class, draftPid);
        assertThat(shadowCount).isZero();
        // Status must remain REVIEWED_OK — no shadow flipped it to SHADOW_RUNNING.
        String status = jdbc.queryForObject(
                "SELECT status FROM ab_agent_skill_draft WHERE pid = ?", String.class, draftPid);
        assertThat(status).isEqualTo("REVIEWED_OK");
    }
}
