package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.metrics.LearningLoopMetrics;
import com.auraboot.framework.agent.service.PromotionEvaluator;
import com.auraboot.framework.agent.service.ShadowExecutor;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
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
 * PR-49: Micrometer counters emitted by ShadowExecutor + PromotionEvaluator.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("LearningLoopMetrics counters (PR-49)")
class LearningLoopMetricsIntegrationTest extends BaseIntegrationTest {

    @Autowired private ShadowExecutor shadowExecutor;
    @Autowired private PromotionEvaluator promotionEvaluator;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;

    @BeforeEach
    void setup() {
        tenantId = 10_050_000L + System.nanoTime() % 100_000;
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_shadow_run WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_agent_skill_draft WHERE tenant_id = ?", tenantId);
    }

    private String seedDraft(String yaml, String status) {
        String pid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_skill_draft " +
                        "(pid, tenant_id, draft_skill_code, source_pattern_hash, contract_yaml, " +
                        " status, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, NOW())",
                pid, tenantId, "auto.t." + pid.substring(0, 6), "h_" + pid, yaml, status);
        return pid;
    }

    private double count(String meter, String... tagKV) {
        Search s = meterRegistry.find(meter);
        for (int i = 0; i + 1 < tagKV.length; i += 2) {
            s = s.tag(tagKV[i], tagKV[i + 1]);
        }
        return s.counters().stream().mapToDouble(c -> c.count()).sum();
    }

    @Test
    @DisplayName("ShadowExecutor emits skipped_not_found for unknown draft")
    void shadow_not_found_metric() {
        double before = count(LearningLoopMetrics.SHADOW_RUN_OUTCOME, "outcome", "skipped_not_found");
        shadowExecutor.execute(ShadowExecutor.ExecutionRequest.builder()
                .draftPid("NONEXISTENTPID1234567890").build());
        double after = count(LearningLoopMetrics.SHADOW_RUN_OUTCOME, "outcome", "skipped_not_found");
        assertThat(after - before).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("ShadowExecutor emits skipped_ineligible for api_* write draft")
    void shadow_ineligible_metric() {
        String pid = seedDraft("substrate: api\naction_type: update\ntool_refs:\n  - api_stripe_charge\n",
                "REVIEWED_OK");
        double before = count(LearningLoopMetrics.SHADOW_RUN_OUTCOME,
                "tenant", tenantId.toString(), "outcome", "skipped_ineligible");
        shadowExecutor.execute(ShadowExecutor.ExecutionRequest.builder()
                .draftPid(pid).originalRunId("o").originalOutputHash("h")
                .originalDurationMs(1L).originalStatus("success").build());
        double after = count(LearningLoopMetrics.SHADOW_RUN_OUTCOME,
                "tenant", tenantId.toString(), "outcome", "skipped_ineligible");
        assertThat(after - before).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("PromotionEvaluator emits decision counter tagged by tenant + outcome")
    void promotion_decision_metric() {
        String pid = seedDraft("skill_code: x\n", "REVIEWED_OK");
        double before = count(LearningLoopMetrics.PROMOTION_DECISION,
                "tenant", tenantId.toString(), "decision", "INSUFFICIENT_RUNS");
        promotionEvaluator.evaluate(pid);
        double after = count(LearningLoopMetrics.PROMOTION_DECISION,
                "tenant", tenantId.toString(), "decision", "INSUFFICIENT_RUNS");
        assertThat(after - before).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("PromotionEvaluator NOT_FOUND decision tagged as unknown tenant")
    void promotion_not_found_metric() {
        double before = count(LearningLoopMetrics.PROMOTION_DECISION, "decision", "NOT_FOUND");
        promotionEvaluator.evaluate("NOPIDNOPIDNOPIDNOPIDNOPIDA");
        double after = count(LearningLoopMetrics.PROMOTION_DECISION, "decision", "NOT_FOUND");
        assertThat(after - before).isGreaterThanOrEqualTo(1);
    }
}
