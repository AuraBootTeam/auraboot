package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.service.DryRunSupportRegistry;
import com.auraboot.framework.agent.service.ShadowEligibilityChecker;
import com.auraboot.framework.agent.service.ShadowExecutor;
import com.auraboot.framework.agent.service.ShadowToolInvoker;
import com.auraboot.framework.agent.util.OutputSignatureProjector;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-32: Shadow Mode — Dry-Run Support Registry + ShadowExecutor.
 * PR-60: output-signature projection.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("Shadow Mode Executor + DryRunSupportRegistry (PR-32)")
@Import(ShadowExecutorIntegrationTest.QueryInvokerTestConfig.class)
class ShadowExecutorIntegrationTest extends BaseIntegrationTest {

    /** PR-60: stub invoker for nq_leads that returns a shuffled rows list.
     *  {@code @Order(0)} places it ahead of the real {@link com.auraboot.framework.agent.service.NamedQueryShadowInvoker}
     *  in the {@code List<ShadowToolInvoker>} injected into {@link ShadowExecutor},
     *  so our stub claims the {@code nq_leads_projection} tool_ref before the
     *  real invoker tries to look it up in {@code ab_named_query}. */
    @TestConfiguration
    static class QueryInvokerTestConfig {
        @Bean
        @Order(0)
        ShadowToolInvoker nqLeadsProjectionInvoker() {
            return new ShadowToolInvoker() {
                @Override public boolean supports(String toolRef) { return "nq_leads_projection".equals(toolRef); }
                @Override public Map<String, Object> invokeShadow(Long tenantId, String toolRef, Map<String, Object> args) {
                    return Map.of(
                            "query_code", "leads_projection",
                            "total", 5L,
                            "rows", List.of(
                                    Map.of("id", 3),
                                    Map.of("id", 1),
                                    Map.of("id", 2),
                                    Map.of("id", 5),
                                    Map.of("id", 4)));
                }
            };
        }
    }

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
    @DisplayName("platform defaults — nq_*/dsl.query=FULL, cmd_*/dsl.command=SIMULATED (post PR-40), code/api=NONE")
    void platform_defaults_loaded() {
        assertThat(registry.lookup(tenantId, "nq_crm_leads")).isEqualTo(DryRunSupportRegistry.SupportLevel.FULL);
        assertThat(registry.lookup(tenantId, "dsl.query")).isEqualTo(DryRunSupportRegistry.SupportLevel.FULL);
        assertThat(registry.lookup(tenantId, "cmd_create_lead")).isEqualTo(DryRunSupportRegistry.SupportLevel.SIMULATED);
        assertThat(registry.lookup(tenantId, "dsl.command")).isEqualTo(DryRunSupportRegistry.SupportLevel.SIMULATED);
        assertThat(registry.lookup(tenantId, "code.run")).isEqualTo(DryRunSupportRegistry.SupportLevel.NONE);
        assertThat(registry.lookup(tenantId, "api_stripe_charge")).isEqualTo(DryRunSupportRegistry.SupportLevel.NONE);
    }

    @Test
    @DisplayName("unmatched tool_ref fails secure → NONE")
    void unmatched_defaults_to_none() {
        assertThat(registry.lookup(tenantId, "mystery_tool")).isEqualTo(DryRunSupportRegistry.SupportLevel.NONE);
    }

    @Test
    @DisplayName("tenant override beats platform default")
    void tenant_override_wins() {
        // Platform default for code.* is NONE; tenant forces FULL for a specific bucket.
        jdbc.update("INSERT INTO ab_agent_dry_run_support " +
                        "(pid, tenant_id, tool_ref_pattern, support_level, created_at, updated_at) " +
                        "VALUES (?, ?, 'code.*', 'FULL', NOW(), NOW())",
                UniqueIdGenerator.generate(), tenantId);
        assertThat(registry.lookup(tenantId, "code.sandbox_eval")).isEqualTo(DryRunSupportRegistry.SupportLevel.FULL);
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
        // api_* is NONE by platform default — an API call tool can never be shadowed.
        String yaml = "substrate: api\naction_type: update\ntool_refs:\n  - api_stripe_charge\n";
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
        String pid = seedDraft("substrate: api\naction_type: update\ntool_refs:\n  - api_stripe_charge\n");
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
    @DisplayName("executor records a shadow run for eligible read-only draft; tool_ref with no invoker → status=skipped")
    void executor_runs_eligible_draft() {
        jdbc.update("INSERT INTO ab_agent_dry_run_support " +
                        "(pid, tenant_id, tool_ref_pattern, support_level, created_at, updated_at) " +
                        "VALUES (?, ?, 'mcp_*', 'FULL', NOW(), NOW())",
                UniqueIdGenerator.generate(), tenantId);
        String pid = seedDraft("substrate: dsl\naction_type: query\ntool_refs:\n  - mcp_unknown_tool\n");
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
    @DisplayName("PR-54: canonical hash is stable across repeated executions with same draft + args")
    void canonical_hash_stable() {
        jdbc.update("INSERT INTO ab_agent_dry_run_support " +
                        "(pid, tenant_id, tool_ref_pattern, support_level, created_at, updated_at) " +
                        "VALUES (?, ?, 'mcp_*', 'FULL', NOW(), NOW())",
                UniqueIdGenerator.generate(), tenantId);
        String pid = seedDraft("substrate: dsl\naction_type: query\ntool_refs:\n  - mcp_stable_tool\n");

        ShadowExecutor.ExecutionResult r1 = executor.execute(ShadowExecutor.ExecutionRequest.builder()
                .draftPid(pid).originalRunId("origA").originalOutputHash("h")
                .originalDurationMs(1L).originalStatus("success").build());
        ShadowExecutor.ExecutionResult r2 = executor.execute(ShadowExecutor.ExecutionRequest.builder()
                .draftPid(pid).originalRunId("origB").originalOutputHash("h")
                .originalDurationMs(1L).originalStatus("success").build());

        String hash1 = jdbc.queryForObject(
                "SELECT shadow_output_hash FROM ab_agent_shadow_run WHERE pid = ?",
                String.class, r1.getShadowRunPid());
        String hash2 = jdbc.queryForObject(
                "SELECT shadow_output_hash FROM ab_agent_shadow_run WHERE pid = ?",
                String.class, r2.getShadowRunPid());
        assertThat(hash1).isNotNull().hasSize(64);
        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    @DisplayName("PR-60: shadow_output_hash uses projection for single-tool query draft")
    void shadow_hash_uses_projection_for_query_tool() {
        String pid = seedDraft("substrate: dsl\naction_type: query\ntool_refs:\n  - nq_leads_projection\n");
        ShadowExecutor.ExecutionResult r = executor.execute(ShadowExecutor.ExecutionRequest.builder()
                .draftPid(pid).originalRunId("origQ").originalOutputHash("ignored")
                .originalDurationMs(1L).originalStatus("success").build());
        assertThat(r.getOutcome()).isEqualTo("executed");

        String shadowHash = jdbc.queryForObject(
                "SELECT shadow_output_hash FROM ab_agent_shadow_run WHERE pid = ?",
                String.class, r.getShadowRunPid());

        // Expected projection: {type:query, tool_ref:nq_leads_projection, record_count:5}
        Map<String, Object> expected = OutputSignatureProjector.projectShadow(
                "nq_leads_projection",
                Map.of("total", 5L, "query_code", "leads_projection",
                        "rows", List.of(Map.of("id", 1))));
        String expectedHash = OutputSignatureProjector.computeMatchHash(expected);
        assertThat(shadowHash).isEqualTo(expectedHash);
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
