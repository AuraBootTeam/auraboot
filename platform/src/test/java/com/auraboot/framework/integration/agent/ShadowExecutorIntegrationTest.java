package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.service.DryRunSupportRegistry;
import com.auraboot.framework.agent.service.ShadowEligibilityChecker;
import com.auraboot.framework.agent.service.ShadowExecutor;
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
 * PR-32: Shadow Mode — Dry-Run Support Registry + ShadowExecutor.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("Shadow Mode Executor + DryRunSupportRegistry (PR-32)")
class ShadowExecutorIntegrationTest extends BaseIntegrationTest {

    @Autowired private DryRunSupportRegistry registry;
    @Autowired private ShadowEligibilityChecker checker;
    @Autowired private ShadowExecutor executor;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;

    @BeforeEach
    void setup() {
        tenantId = 9_250_000L + System.nanoTime() % 100_000;
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_shadow_run WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_agent_skill_draft WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_agent_dry_run_support WHERE tenant_id = ?", tenantId);
    }

    private String seedDraft(String yaml) {
        String pid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_skill_draft " +
                        "(pid, tenant_id, draft_skill_code, source_pattern_hash, contract_yaml, " +
                        " status, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, 'DRAFT_PENDING_REVIEW', NOW())",
                pid, tenantId, "auto.test.x", "h_" + pid, yaml);
        return pid;
    }

    // =========================================================================
    // Registry
    // =========================================================================

    @Test
    @DisplayName("platform defaults — nq_* and dsl.query are FULL, cmd_* and dsl.command are NONE")
    void platform_defaults_loaded() {
        assertThat(registry.lookup(tenantId, "nq_crm_leads")).isEqualTo(DryRunSupportRegistry.SupportLevel.FULL);
        assertThat(registry.lookup(tenantId, "dsl.query")).isEqualTo(DryRunSupportRegistry.SupportLevel.FULL);
        assertThat(registry.lookup(tenantId, "cmd_create_lead")).isEqualTo(DryRunSupportRegistry.SupportLevel.NONE);
        assertThat(registry.lookup(tenantId, "dsl.command")).isEqualTo(DryRunSupportRegistry.SupportLevel.NONE);
    }

    @Test
    @DisplayName("unmatched tool_ref fails secure → NONE")
    void unmatched_defaults_to_none() {
        assertThat(registry.lookup(tenantId, "mystery_tool")).isEqualTo(DryRunSupportRegistry.SupportLevel.NONE);
    }

    @Test
    @DisplayName("tenant override beats platform default")
    void tenant_override_wins() {
        // Platform default for cmd_* is NONE; tenant opts in to SIMULATED.
        jdbc.update("INSERT INTO ab_agent_dry_run_support " +
                        "(pid, tenant_id, tool_ref_pattern, support_level, created_at, updated_at) " +
                        "VALUES (?, ?, 'cmd_*', 'SIMULATED', NOW(), NOW())",
                UniqueIdGenerator.generate(), tenantId);
        assertThat(registry.lookup(tenantId, "cmd_create_lead")).isEqualTo(DryRunSupportRegistry.SupportLevel.SIMULATED);
    }

    // =========================================================================
    // ShadowEligibilityChecker via registry
    // =========================================================================

    @Test
    @DisplayName("read-only draft is ELIGIBLE_DIRECT (action_type=query)")
    void read_action_eligible() {
        String yaml = "substrate: dsl\naction_type: query\ntool_refs:\n  - nq_leads\n";
        assertThat(checker.classify(tenantId, yaml)).isEqualTo(ShadowEligibilityChecker.Eligibility.ELIGIBLE_DIRECT);
    }

    @Test
    @DisplayName("write draft with all-FULL tool_refs → ELIGIBLE_DIRECT")
    void write_all_full_eligible_direct() {
        // Give tenant a FULL override for a write-like ref
        jdbc.update("INSERT INTO ab_agent_dry_run_support " +
                        "(pid, tenant_id, tool_ref_pattern, support_level, created_at, updated_at) " +
                        "VALUES (?, ?, 'cmd_safe_read', 'FULL', NOW(), NOW())",
                UniqueIdGenerator.generate(), tenantId);
        String yaml = "substrate: dsl\naction_type: update\ntool_refs:\n  - cmd_safe_read\n";
        assertThat(checker.classify(tenantId, yaml)).isEqualTo(ShadowEligibilityChecker.Eligibility.ELIGIBLE_DIRECT);
    }

    @Test
    @DisplayName("write draft with SIMULATED tool_ref → ELIGIBLE_DRY_RUN")
    void write_simulated_eligible_dryrun() {
        jdbc.update("INSERT INTO ab_agent_dry_run_support " +
                        "(pid, tenant_id, tool_ref_pattern, support_level, created_at, updated_at) " +
                        "VALUES (?, ?, 'cmd_*', 'SIMULATED', NOW(), NOW())",
                UniqueIdGenerator.generate(), tenantId);
        String yaml = "substrate: dsl\naction_type: update\ntool_refs:\n  - cmd_update_lead\n";
        assertThat(checker.classify(tenantId, yaml)).isEqualTo(ShadowEligibilityChecker.Eligibility.ELIGIBLE_DRY_RUN);
    }

    @Test
    @DisplayName("write draft with any NONE tool_ref → INELIGIBLE_NO_DRY_RUN_SUPPORT")
    void write_any_none_ineligible() {
        String yaml = "substrate: dsl\naction_type: update\ntool_refs:\n  - cmd_create_lead\n";
        assertThat(checker.classify(tenantId, yaml))
                .isEqualTo(ShadowEligibilityChecker.Eligibility.INELIGIBLE_NO_DRY_RUN_SUPPORT);
    }

    @Test
    @DisplayName("code substrate remains INELIGIBLE_CODE_SIDE_EFFECT_UNKNOWN regardless of registry")
    void code_substrate_blocked() {
        String yaml = "substrate: code\naction_type: update\ntool_refs:\n  - nq_x\n";
        assertThat(checker.classify(tenantId, yaml))
                .isEqualTo(ShadowEligibilityChecker.Eligibility.INELIGIBLE_CODE_SIDE_EFFECT_UNKNOWN);
    }

    // =========================================================================
    // ShadowExecutor end-to-end
    // =========================================================================

    @Test
    @DisplayName("executor skips ineligible write draft without recording a shadow run")
    void executor_skips_ineligible() {
        String pid = seedDraft("substrate: dsl\naction_type: update\ntool_refs:\n  - cmd_create_lead\n");
        ShadowExecutor.ExecutionResult r = executor.execute(ShadowExecutor.ExecutionRequest.builder()
                .draftPid(pid).originalRunId("orig1").originalOutputHash("h0")
                .originalDurationMs(100L).originalStatus("success").build());
        assertThat(r.getOutcome()).isEqualTo("skipped_ineligible");
        assertThat(r.getShadowRunPid()).isNull();

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_shadow_run WHERE draft_id = ?",
                Integer.class, pid);
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("executor records a shadow run for eligible read-only draft (with no invokers → status=skipped)")
    void executor_runs_eligible_draft() {
        String pid = seedDraft("substrate: dsl\naction_type: query\ntool_refs:\n  - nq_leads\n");
        ShadowExecutor.ExecutionResult r = executor.execute(ShadowExecutor.ExecutionRequest.builder()
                .draftPid(pid).originalRunId("orig2").originalOutputHash("hX")
                .originalDurationMs(200L).originalStatus("success").build());
        assertThat(r.getOutcome()).isEqualTo("executed");
        assertThat(r.getShadowRunPid()).isNotNull();

        String status = jdbc.queryForObject(
                "SELECT shadow_status FROM ab_agent_shadow_run WHERE pid = ?",
                String.class, r.getShadowRunPid());
        // No ShadowToolInvoker beans registered in test context → every tool_ref is skipped.
        assertThat(status).isEqualTo("skipped");
    }

    @Test
    @DisplayName("executor reports skipped_not_found for unknown draft")
    void executor_not_found() {
        ShadowExecutor.ExecutionResult r = executor.execute(ShadowExecutor.ExecutionRequest.builder()
                .draftPid("NONEXISTENTPID1234567890").originalRunId("orig3")
                .originalOutputHash("h").originalDurationMs(1L).originalStatus("success").build());
        assertThat(r.getOutcome()).isEqualTo("skipped_not_found");
    }
}
