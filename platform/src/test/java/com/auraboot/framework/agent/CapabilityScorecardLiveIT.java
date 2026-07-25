package com.auraboot.framework.agent;

import com.auraboot.framework.agent.dto.CapabilityEvalCase;
import com.auraboot.framework.agent.entity.AbCapabilityEvalRun;
import com.auraboot.framework.agent.eval.CapabilityEvalRegressionGate;
import com.auraboot.framework.agent.eval.GenericEvalCaseFixture;
import com.auraboot.framework.agent.mapper.AbCapabilityEvalRunMapper;
import com.auraboot.framework.agent.service.CapabilityEvalService;
import com.auraboot.framework.agent.util.LiveLlmSeeder;
import com.auraboot.framework.cloudconfig.service.CloudConfigService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The capability scorecard: does this digital employee actually pass its competency bar?
 *
 * <p>Every other live eval answers "did this one capability behave" — this one produces
 * the <strong>aggregate five-dimension score</strong> and judges it against
 * {@link CapabilityEvalRegressionGate.Thresholds#defaults()}. It is the only test that
 * can fail with "the agent is not competent enough" rather than "a capability broke".
 *
 * <p><strong>Why the fixture's tool codes matter.</strong> A case whose expected tools are
 * absent from the tenant catalog is scored <em>unavailable</em> (D3a) and dropped from every
 * denominator. A fixture built on invented codes therefore yields {@code totalCases=0} and
 * <em>no weighted score at all</em> — a run that looks like it passed while measuring
 * nothing. {@link GenericEvalCaseFixture} targets the platform's own registered tools so
 * the dimensions are genuinely computable; this test asserts that they were.
 *
 * <p>Opt-in: gated on a live LLM credential (see {@link LiveLlmSeeder}), tagged
 * {@code agent-eval-live}.
 * <pre>{@code DASHSCOPE_API_KEY=... ./gradlew :testAgent --tests '*CapabilityScorecardLiveIT*' -PincludeLiveEvals}</pre>
 */
@Tag("agent-eval-live")
@DisplayName("Capability scorecard: aggregate 5-dimension score vs the competency bar")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "agent.anthropic.api-key=",
        "agent.llm.stub-mode=false",
})
class CapabilityScorecardLiveIT extends BaseIntegrationTest {

    private LiveLlmSeeder.LiveProvider liveProvider;

    @Autowired private CapabilityEvalService capabilityEvalService;
    @Autowired private CloudConfigService cloudConfigService;
    @Autowired private AbCapabilityEvalRunMapper evalRunMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long tenantId;

    @BeforeEach
    void seedLiveProvider() {
        liveProvider = LiveLlmSeeder.resolve();
        Assumptions.assumeTrue(liveProvider != null, LiveLlmSeeder.skipReason());
        tenantId = getTestTenant().getId();
        LiveLlmSeeder.seed(liveProvider, tenantId, cloudConfigService, jdbcTemplate);
    }

    @AfterAll
    void cleanup() {
        if (tenantId != null && liveProvider != null) {
            LiveLlmSeeder.clear(liveProvider, tenantId, jdbcTemplate);
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    @DisplayName("a real model scores every dimension and the run clears the competency bar")
    void liveRunProducesScoredDimensionsAndClearsTheBar() {
        List<CapabilityEvalCase> cases = GenericEvalCaseFixture.cases();
        assertTrue(cases.size() >= 5,
                "the generic fixture must carry enough cases to make rates meaningful, got " + cases.size());

        Map<String, Object> report = capabilityEvalService.evaluateToolSelection(tenantId, "llm", cases);
        assertNotNull(report, "eval must return a report");

        // The run must have consulted the model — a silent degrade to keyword would make
        // every dimension below meaningless.
        assertEquals("llm", report.get("evalMode"),
                "with a live provider seeded the run must stay in llm mode, not degrade to keyword");

        // The scoring must have actually happened. This is the assertion that catches the
        // "fixture points at tools nobody registered" failure: it shows up as 0 scoreable
        // cases and no weightedScore, which would otherwise read as an uneventful pass.
        int scored = ((Number) report.getOrDefault("totalCases", 0)).intValue();
        int unavailable = ((Number) report.getOrDefault("unavailableCases", 0)).intValue();
        assertTrue(scored > 0,
                "no case was scoreable — the fixture's expected tools are absent from the tenant catalog, "
                        + "so this run measured nothing (unavailable=" + unavailable + ")");

        for (String dim : List.of("toolSelectionAccuracy", "parameterCompletionRate",
                "safetyComplianceRate", "composabilityScore", "hallucinationRate", "weightedScore")) {
            assertNotNull(report.get(dim), "a scored run must carry the " + dim + " dimension");
        }

        // Judge the persisted run against the same bar the nightly regression gate uses,
        // so "competent" means one thing in both places.
        AbCapabilityEvalRun latest = evalRunMapper.selectList(
                new LambdaQueryWrapper<AbCapabilityEvalRun>()
                        .eq(AbCapabilityEvalRun::getTenantId, tenantId)
                        .orderByDesc(AbCapabilityEvalRun::getRunAt)
                        .last("LIMIT 1")).stream().findFirst().orElse(null);
        assertNotNull(latest, "a scored run must be persisted to ab_capability_eval_run");
        assertEquals("llm", latest.getEvalMode(), "the persisted run must record eval_mode=llm");

        CapabilityEvalRegressionGate.Verdict verdict = CapabilityEvalRegressionGate.evaluate(
                latest, List.of(), CapabilityEvalRegressionGate.Thresholds.defaults());

        // Report the scorecard on the way through: on failure this is the evidence of
        // *which* competency fell short, not just that something did.
        System.out.printf("%n===== CAPABILITY SCORECARD (%s %s) =====%n",
                liveProvider.providerCode(), liveProvider.model());
        System.out.printf("  cases scored/unavailable : %d / %d%n", scored, unavailable);
        System.out.printf("  tool selection           : %.2f  (bar 0.70)%n", num(report, "toolSelectionAccuracy"));
        System.out.printf("  parameter completion     : %.2f  (bar 0.60)%n", num(report, "parameterCompletionRate"));
        System.out.printf("  safety compliance        : %.2f  (bar 0.90)%n", num(report, "safetyComplianceRate"));
        System.out.printf("  composability            : %.2f  (bar 0.50)%n", num(report, "composabilityScore"));
        System.out.printf("  hallucination rate       : %.2f  (bar <=0.10)%n", num(report, "hallucinationRate"));
        System.out.printf("  weighted score           : %.2f%n", num(report, "weightedScore"));
        System.out.printf("  verdict                  : %s%n", verdict.ok() ? "COMPETENT" : verdict.summary());
        System.out.println("=========================================\n");

        assertTrue(verdict.ok(),
                "the agent did not clear the competency bar: " + verdict.summary());
    }

    private static double num(Map<String, Object> report, String key) {
        Object v = report.get(key);
        return v instanceof Number n ? n.doubleValue() : Double.NaN;
    }
}
