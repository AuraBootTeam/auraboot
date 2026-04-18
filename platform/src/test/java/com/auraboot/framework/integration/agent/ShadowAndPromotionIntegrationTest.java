package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.service.PatternExtractor;
import com.auraboot.framework.agent.service.PromotionEvaluator;
import com.auraboot.framework.agent.service.ShadowEligibilityChecker;
import com.auraboot.framework.agent.service.ShadowRunner;
import com.auraboot.framework.agent.service.SkillDraftGenerator;
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

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-19: ShadowRunner + ShadowEligibilityChecker + PromotionEvaluator.
 * End-to-end: pattern → draft → eligibility → shadow runs → promotion.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("Learning Loop — Shadow + Promotion (PR-19)")
class ShadowAndPromotionIntegrationTest extends BaseIntegrationTest {

    @Autowired private PatternExtractor extractor;
    @Autowired private SkillDraftGenerator generator;
    @Autowired private ShadowEligibilityChecker eligibility;
    @Autowired private ShadowRunner runner;
    @Autowired private PromotionEvaluator promoter;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;

    @BeforeEach
    void setup() {
        tenantId = 9_800_000L + System.nanoTime() % 100_000;
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_shadow_run WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_agent_skill_draft WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_agent_learning_pattern WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_agent_action WHERE tenant_id = ?", tenantId);
    }

    /** Seed + extract + draft → return draft pid. */
    private String seedDraft(String sig, String model, String actionType, String fidelity, int n) {
        for (int i = 0; i < n; i++) {
            jdbc.update("INSERT INTO ab_agent_action " +
                            "(pid, tenant_id, run_id, action_code, action_type, target_model, " +
                            " command_signature, tool_ref, fidelity, action_status, " +
                            " executed_at, created_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'success', NOW(), NOW())",
                    UniqueIdGenerator.generate(), tenantId, UniqueIdGenerator.generate(),
                    model + "." + actionType, actionType, model, sig, "tool." + sig, fidelity);
        }
        extractor.extractPatterns();
        String patternPid = jdbc.queryForObject(
                "SELECT pid FROM ab_agent_learning_pattern " +
                        "WHERE tenant_id = ? AND pattern_signature->>'command_signature' = ?",
                String.class, tenantId, sig);
        return generator.generateDraftFor(patternPid);
    }

    // -----------------------------------------------------------------------
    // ShadowEligibilityChecker
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("read-action draft is ELIGIBLE_DIRECT (shadow execution safe)")
    void eligibility_read_direct() {
        String draftPid = seedDraft("sig_read_" + tenantId, "crm_lead", "query", "semantic", 5);
        assertThat(eligibility.check(draftPid)).isEqualTo(ShadowEligibilityChecker.Eligibility.ELIGIBLE_DIRECT);
    }

    @Test
    @DisplayName("write-action draft with dsl substrate is INELIGIBLE_NO_DRY_RUN_SUPPORT (v0)")
    void eligibility_write_blocked() {
        String draftPid = seedDraft("sig_write_" + tenantId, "crm_lead", "update", "full", 5);
        assertThat(eligibility.check(draftPid))
                .isEqualTo(ShadowEligibilityChecker.Eligibility.INELIGIBLE_NO_DRY_RUN_SUPPORT);
    }

    @Test
    @DisplayName("code substrate is INELIGIBLE_CODE_SIDE_EFFECT_UNKNOWN")
    void eligibility_code_blocked() {
        String draftPid = seedDraft("sig_code_" + tenantId, "crm_lead", "export", "blackbox", 5);
        assertThat(eligibility.check(draftPid))
                .isEqualTo(ShadowEligibilityChecker.Eligibility.INELIGIBLE_CODE_SIDE_EFFECT_UNKNOWN);
    }

    @Test
    @DisplayName("unknown draft pid returns NOT_FOUND")
    void eligibility_not_found() {
        assertThat(eligibility.check("01NOSUCH")).isEqualTo(ShadowEligibilityChecker.Eligibility.NOT_FOUND);
    }

    // -----------------------------------------------------------------------
    // ShadowRunner
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("recordRun persists shadow_run with output_diff JSONB + match booleans")
    void shadow_run_persists() {
        String draftPid = seedDraft("sig_sr_" + tenantId, "crm_lead", "query", "semantic", 5);
        String origRun = UniqueIdGenerator.generate();

        String shadowPid = runner.recordRun(ShadowRunner.ShadowOutcome.builder()
                .tenantId(tenantId)
                .draftPid(draftPid)
                .originalRunId(origRun)
                .shadowStatus("success")
                .shadowDurationMs(150L)
                .shadowCostUsd(new BigDecimal("0.002"))
                .shadowTokens(40)
                .shadowOutputHash("shadowhash")
                .originalStatus("success")
                .originalDurationMs(200L)
                .originalCostUsd(new BigDecimal("0.003"))
                .originalOutputHash("originalhash")
                .outputMatch(false)
                .outputDiff(Map.of("missing", java.util.List.of("owner_id")))
                .fidelityMatch(true)
                .build());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT draft_id, original_run_id, output_match, fidelity_match, " +
                        "       output_diff::text AS diff " +
                        "FROM ab_agent_shadow_run WHERE pid = ?", shadowPid);
        assertThat(row.get("draft_id")).isEqualTo(draftPid);
        assertThat(row.get("original_run_id")).isEqualTo(origRun);
        assertThat(row.get("output_match")).isEqualTo(false);
        assertThat(row.get("fidelity_match")).isEqualTo(true);
        assertThat((String) row.get("diff")).contains("missing").contains("owner_id");
    }

    // -----------------------------------------------------------------------
    // PromotionEvaluator
    // -----------------------------------------------------------------------

    private void seedShadowRuns(String draftPid, int total, int matches) {
        for (int i = 0; i < total; i++) {
            boolean match = i < matches;
            runner.recordRun(ShadowRunner.ShadowOutcome.builder()
                    .tenantId(tenantId).draftPid(draftPid)
                    .originalRunId(UniqueIdGenerator.generate())
                    .shadowStatus("success").shadowDurationMs(100L)
                    .shadowCostUsd(new BigDecimal("0.001")).shadowTokens(10)
                    .shadowOutputHash("h_s_" + i)
                    .originalStatus("success").originalDurationMs(120L)
                    .originalCostUsd(new BigDecimal("0.002"))
                    .originalOutputHash(match ? "h_s_" + i : "h_o_" + i)
                    .outputMatch(match)
                    .fidelityMatch(match)
                    .build());
        }
    }

    @Test
    @DisplayName("PROMOTE — draft with ≥5 runs all matching flips to PROMOTED_PENDING_HUMAN")
    void promote_when_thresholds_met() {
        String draftPid = seedDraft("sig_pro_" + tenantId, "crm_lead", "query", "semantic", 5);
        // PR-53 C2: only REVIEWED_OK / SHADOW_RUNNING are promotable — simulate prior human review.
        jdbc.update("UPDATE ab_agent_skill_draft SET status = 'REVIEWED_OK' WHERE pid = ?", draftPid);
        seedShadowRuns(draftPid, 5, 5);

        PromotionEvaluator.EvaluationResult r = promoter.evaluate(draftPid);
        assertThat(r.getDecision()).isEqualTo(PromotionEvaluator.Decision.PROMOTE);
        assertThat(r.getShadowRuns()).isEqualTo(5);
        assertThat(r.getOutputMatchRate()).isEqualTo(1.0);
        assertThat(r.getFidelityMatchRate()).isEqualTo(1.0);

        Map<String, Object> draft = jdbc.queryForMap(
                "SELECT status, shadow_metrics::text AS metrics, shadow_started_at " +
                        "FROM ab_agent_skill_draft WHERE pid = ?", draftPid);
        assertThat(draft.get("status")).isEqualTo("PROMOTED_PENDING_HUMAN");
        assertThat(draft.get("shadow_started_at")).isNotNull();
        assertThat((String) draft.get("metrics"))
                .contains("\"shadow_runs\": 5")
                .contains("\"output_match_rate\": 1.0");
    }

    @Test
    @DisplayName("INSUFFICIENT_RUNS — fewer than threshold runs blocks promotion")
    void insufficient_runs_blocks() {
        String draftPid = seedDraft("sig_few_" + tenantId, "crm_lead", "query", "semantic", 5);
        seedShadowRuns(draftPid, 2, 2);

        PromotionEvaluator.EvaluationResult r = promoter.evaluate(draftPid);
        assertThat(r.getDecision()).isEqualTo(PromotionEvaluator.Decision.INSUFFICIENT_RUNS);

        String status = jdbc.queryForObject(
                "SELECT status FROM ab_agent_skill_draft WHERE pid = ?", String.class, draftPid);
        assertThat(status).isEqualTo("DRAFT_PENDING_REVIEW"); // unchanged
    }

    @Test
    @DisplayName("BELOW_THRESHOLD — enough runs but low match rate keeps draft in current state")
    void below_threshold_keeps_draft() {
        String draftPid = seedDraft("sig_flaky_" + tenantId, "crm_lead", "query", "semantic", 5);
        seedShadowRuns(draftPid, 10, 5);   // 50% match, below 0.90

        PromotionEvaluator.EvaluationResult r = promoter.evaluate(draftPid);
        assertThat(r.getDecision()).isEqualTo(PromotionEvaluator.Decision.BELOW_THRESHOLD);
        assertThat(r.getOutputMatchRate()).isBetween(0.40, 0.60);

        String status = jdbc.queryForObject(
                "SELECT status FROM ab_agent_skill_draft WHERE pid = ?", String.class, draftPid);
        assertThat(status).isEqualTo("DRAFT_PENDING_REVIEW");
        // But shadow_metrics is still written so the HITL UI can see the stats.
        String metrics = jdbc.queryForObject(
                "SELECT shadow_metrics::text FROM ab_agent_skill_draft WHERE pid = ?",
                String.class, draftPid);
        assertThat(metrics).contains("shadow_runs");
    }

    @Test
    @DisplayName("unknown draft pid returns NOT_FOUND")
    void evaluate_unknown_draft() {
        PromotionEvaluator.EvaluationResult r = promoter.evaluate("01NOSUCH");
        assertThat(r.getDecision()).isEqualTo(PromotionEvaluator.Decision.NOT_FOUND);
    }
}
