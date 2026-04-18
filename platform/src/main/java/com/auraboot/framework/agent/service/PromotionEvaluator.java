package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.metrics.LearningLoopMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ACP Learning Loop — Phase 4 promotion (design/learning-loop.md §6.2 / §7).
 *
 * Reads the shadow_runs a draft accumulated and decides whether it meets the
 * promotion bar:
 *   - at least {@code minShadowRuns} recorded runs
 *   - {@code outputMatchRate} ≥ threshold (default 0.90)
 *   - {@code fidelityMatchRate} ≥ threshold (default 0.90)
 *
 * A qualifying draft moves to status='PROMOTED_PENDING_HUMAN' — the final
 * ACTIVE flip still needs a human sign-off in the Mission Control UI, this
 * service only stages the decision and writes the shadow_metrics summary.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromotionEvaluator {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private LearningLoopMetrics metrics;

    @Value("${acp.learning.promotion.min-shadow-runs:5}")
    private int minShadowRuns;

    @Value("${acp.learning.promotion.min-output-match-rate:0.90}")
    private double minOutputMatchRate;

    @Value("${acp.learning.promotion.min-fidelity-match-rate:0.90}")
    private double minFidelityMatchRate;

    /**
     * Evaluate a single draft. Writes shadow_metrics on the draft row
     * regardless of outcome, flips status if promotion-ready.
     */
    public EvaluationResult evaluate(String draftPid) {
        List<Map<String, Object>> draftRows = jdbcTemplate.queryForList(
                "SELECT status FROM ab_agent_skill_draft WHERE pid = ?", draftPid);
        if (draftRows.isEmpty()) {
            if (metrics != null) metrics.recordPromotionDecision(null, Decision.NOT_FOUND.name());
            return EvaluationResult.builder().draftPid(draftPid).decision(Decision.NOT_FOUND).build();
        }
        String currentStatus = (String) draftRows.get(0).get("status");
        Long tenantId = jdbcTemplate.queryForObject(
                "SELECT tenant_id FROM ab_agent_skill_draft WHERE pid = ?",
                Long.class, draftPid);

        Map<String, Object> stats = jdbcTemplate.queryForMap(
                "SELECT " +
                        "  COUNT(*) AS n, " +
                        "  COALESCE(AVG(CASE WHEN output_match   THEN 1.0 ELSE 0.0 END), 0)::double precision AS output_match_rate, " +
                        "  COALESCE(AVG(CASE WHEN fidelity_match THEN 1.0 ELSE 0.0 END), 0)::double precision AS fidelity_match_rate, " +
                        "  COALESCE(AVG(shadow_cost_usd - original_cost_usd), 0)::double precision            AS cost_delta, " +
                        "  COALESCE(AVG(shadow_duration_ms - original_duration_ms), 0)::double precision      AS duration_delta_ms " +
                        "FROM ab_agent_shadow_run " +
                        "WHERE draft_id = ?",
                draftPid);

        long n = ((Number) stats.get("n")).longValue();
        double outMatch = stats.get("output_match_rate") == null ? 0.0
                : ((Number) stats.get("output_match_rate")).doubleValue();
        double fidMatch = stats.get("fidelity_match_rate") == null ? 0.0
                : ((Number) stats.get("fidelity_match_rate")).doubleValue();
        double costDelta = ((Number) stats.get("cost_delta")).doubleValue();
        double durationDelta = ((Number) stats.get("duration_delta_ms")).doubleValue();

        Map<String, Object> metricsJson = new LinkedHashMap<>();
        metricsJson.put("shadow_runs", n);
        metricsJson.put("output_match_rate", round(outMatch));
        metricsJson.put("fidelity_match_rate", round(fidMatch));
        metricsJson.put("cost_delta", round(costDelta));
        metricsJson.put("duration_delta_ms", round(durationDelta));

        Decision decision;
        if (n < minShadowRuns) {
            decision = Decision.INSUFFICIENT_RUNS;
        } else if (outMatch < minOutputMatchRate || fidMatch < minFidelityMatchRate) {
            decision = Decision.BELOW_THRESHOLD;
        } else {
            decision = Decision.PROMOTE;
        }

        persistMetrics(draftPid, metricsJson, decision, currentStatus);
        if (metrics != null) metrics.recordPromotionDecision(tenantId, decision.name());

        log.info("Promotion eval: draft={} runs={} out_match={} fid_match={} decision={}",
                draftPid, n, outMatch, fidMatch, decision);

        return EvaluationResult.builder()
                .draftPid(draftPid)
                .shadowRuns(n)
                .outputMatchRate(outMatch)
                .fidelityMatchRate(fidMatch)
                .costDelta(costDelta)
                .durationDeltaMs(durationDelta)
                .decision(decision)
                .build();
    }

    private void persistMetrics(String draftPid, Map<String, Object> metrics,
                                 Decision decision, String currentStatus) {
        String metricsJson;
        try {
            metricsJson = objectMapper.writeValueAsString(metrics);
        } catch (Exception e) {
            metricsJson = "{}";
        }

        if (decision == Decision.PROMOTE && isPromotable(currentStatus)) {
            jdbcTemplate.update(
                    "UPDATE ab_agent_skill_draft " +
                            "SET shadow_metrics = ?::jsonb, status = 'PROMOTED_PENDING_HUMAN', " +
                            "    shadow_started_at = COALESCE(shadow_started_at, NOW()) " +
                            "WHERE pid = ?",
                    metricsJson, draftPid);
        } else {
            jdbcTemplate.update(
                    "UPDATE ab_agent_skill_draft SET shadow_metrics = ?::jsonb WHERE pid = ?",
                    metricsJson, draftPid);
        }
    }

    private boolean isPromotable(String currentStatus) {
        return "DRAFT_PENDING_REVIEW".equals(currentStatus)
                || "REVIEWED_OK".equals(currentStatus)
                || "SHADOW_RUNNING".equals(currentStatus);
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    public enum Decision { PROMOTE, BELOW_THRESHOLD, INSUFFICIENT_RUNS, NOT_FOUND }

    @Data
    @Builder
    public static class EvaluationResult {
        private String draftPid;
        private long shadowRuns;
        private double outputMatchRate;
        private double fidelityMatchRate;
        private double costDelta;
        private double durationDeltaMs;
        private Decision decision;
    }
}
