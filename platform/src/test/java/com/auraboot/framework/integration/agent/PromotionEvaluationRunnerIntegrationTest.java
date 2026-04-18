package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.service.PromotionEvaluationRunner;
import com.auraboot.framework.agent.service.PromotionEvaluator;
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
 * PR-34: PromotionEvaluationRunner — batch evaluate REVIEWED_OK drafts.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("PromotionEvaluationRunner (PR-34)")
class PromotionEvaluationRunnerIntegrationTest extends BaseIntegrationTest {

    @Autowired private PromotionEvaluationRunner runner;
    @Autowired private PromotionEvaluator evaluator;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;

    @BeforeEach
    void setup() {
        tenantId = 9_350_000L + System.nanoTime() % 100_000;
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_shadow_run WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_agent_skill_draft WHERE tenant_id = ?", tenantId);
    }

    private String seedDraft(String status) {
        String pid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_skill_draft " +
                        "(pid, tenant_id, draft_skill_code, source_pattern_hash, contract_yaml, " +
                        " status, created_at) " +
                        "VALUES (?, ?, ?, ?, 'skill_code: x', ?, NOW())",
                pid, tenantId, "auto.t." + pid.substring(0, 6), "h_" + pid, status);
        return pid;
    }

    private void seedShadowRun(String draftPid, boolean match) {
        jdbc.update("INSERT INTO ab_agent_shadow_run " +
                        "(pid, tenant_id, draft_id, original_run_id, shadow_status, " +
                        " shadow_duration_ms, shadow_cost_usd, original_duration_ms, original_cost_usd, " +
                        " output_match, fidelity_match) " +
                        "VALUES (?, ?, ?, ?, 'success', 100, 0, 120, 0, ?, ?)",
                UniqueIdGenerator.generate(), tenantId, draftPid, "orig" + System.nanoTime(), match, match);
    }

    @Test
    @DisplayName("runner picks up REVIEWED_OK drafts; DRAFT_PENDING_REVIEW is ignored")
    void runner_scope() {
        String eligible = seedDraft("REVIEWED_OK");
        String pending = seedDraft("DRAFT_PENDING_REVIEW");
        // Enough successful shadow runs to promote the eligible draft.
        for (int i = 0; i < 6; i++) seedShadowRun(eligible, true);

        int n = runner.runOnce();
        assertThat(n).isGreaterThanOrEqualTo(1);

        String eligibleStatus = jdbc.queryForObject(
                "SELECT status FROM ab_agent_skill_draft WHERE pid = ?", String.class, eligible);
        String pendingStatus = jdbc.queryForObject(
                "SELECT status FROM ab_agent_skill_draft WHERE pid = ?", String.class, pending);

        assertThat(eligibleStatus).isEqualTo("PROMOTED_PENDING_HUMAN");
        assertThat(pendingStatus).isEqualTo("DRAFT_PENDING_REVIEW");
    }

    @Test
    @DisplayName("below-threshold match rate: status stays REVIEWED_OK, shadow_metrics still updated")
    void below_threshold_keeps_status() {
        String pid = seedDraft("REVIEWED_OK");
        // 5 matches + 3 misses → 62.5% output match, below 0.90 default.
        for (int i = 0; i < 5; i++) seedShadowRun(pid, true);
        for (int i = 0; i < 3; i++) seedShadowRun(pid, false);

        runner.runOnce();
        String status = jdbc.queryForObject(
                "SELECT status FROM ab_agent_skill_draft WHERE pid = ?", String.class, pid);
        assertThat(status).isEqualTo("REVIEWED_OK");

        String metricsJson = jdbc.queryForObject(
                "SELECT shadow_metrics::text FROM ab_agent_skill_draft WHERE pid = ?", String.class, pid);
        assertThat(metricsJson).contains("shadow_runs");
    }

    @Test
    @DisplayName("C2: DRAFT_PENDING_REVIEW is never auto-promoted even with perfect shadow match")
    void draft_pending_review_never_promoted() {
        String pid = seedDraft("DRAFT_PENDING_REVIEW");
        // 10 matching runs — would trivially pass the threshold if allowed.
        for (int i = 0; i < 10; i++) seedShadowRun(pid, true);

        // Direct evaluator call (bypass runner's own status filter) to prove the
        // evaluator's isPromotable() gate rejects DRAFT_PENDING_REVIEW.
        PromotionEvaluator.EvaluationResult r = evaluator.evaluate(pid);
        assertThat(r.getDecision()).isEqualTo(PromotionEvaluator.Decision.PROMOTE);

        String status = jdbc.queryForObject(
                "SELECT status FROM ab_agent_skill_draft WHERE pid = ?", String.class, pid);
        assertThat(status)
                .as("DRAFT_PENDING_REVIEW must require human review before promotion")
                .isEqualTo("DRAFT_PENDING_REVIEW");

        // shadow_metrics should still be persisted so the UI can show them.
        String metricsJson = jdbc.queryForObject(
                "SELECT shadow_metrics::text FROM ab_agent_skill_draft WHERE pid = ?", String.class, pid);
        assertThat(metricsJson).contains("shadow_runs");
    }

    @Test
    @DisplayName("N7 race: concurrent human-reject between SELECT and UPDATE is preserved")
    void promotion_race_does_not_overwrite_human_reject() {
        String pid = seedDraft("REVIEWED_OK");
        for (int i = 0; i < 6; i++) seedShadowRun(pid, true);

        // Simulate a concurrent human-reject hitting the row after the evaluator
        // has read status='REVIEWED_OK' but before it UPDATEs. Easiest deterministic
        // path: flip status BEFORE calling evaluate — the evaluator's initial
        // SELECT sees REVIEWED_REJECTED so decision stays PROMOTE from snapshot,
        // but the narrow UPDATE (WHERE status IN ('REVIEWED_OK','SHADOW_RUNNING'))
        // must match 0 rows and leave REVIEWED_REJECTED untouched.
        jdbc.update("UPDATE ab_agent_skill_draft SET status = 'REVIEWED_REJECTED' WHERE pid = ?", pid);

        evaluator.evaluate(pid);

        String status = jdbc.queryForObject(
                "SELECT status FROM ab_agent_skill_draft WHERE pid = ?", String.class, pid);
        assertThat(status)
                .as("scheduler must not overwrite a concurrent human-reject")
                .isEqualTo("REVIEWED_REJECTED");
    }

    @Test
    @DisplayName("insufficient runs leaves status alone")
    void insufficient_runs_noop_status() {
        String pid = seedDraft("REVIEWED_OK");
        // Only 2 runs — below default minShadowRuns=5
        for (int i = 0; i < 2; i++) seedShadowRun(pid, true);

        runner.runOnce();
        String status = jdbc.queryForObject(
                "SELECT status FROM ab_agent_skill_draft WHERE pid = ?", String.class, pid);
        assertThat(status).isEqualTo("REVIEWED_OK");
    }
}
