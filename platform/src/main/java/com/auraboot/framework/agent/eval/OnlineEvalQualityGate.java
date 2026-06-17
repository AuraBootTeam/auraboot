package com.auraboot.framework.agent.eval;

import com.auraboot.framework.agent.eval.AgentOnlineEvalService.OnlineEvalSummary;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Pure quality gate for L4 online eval (test-strategy doc
 * {@code docs/backlog/2026-06-12-agent-testing-strategy-and-eval-loop.md}, item ④).
 *
 * <p>Given an {@link OnlineEvalSummary} aggregated over sampled <em>production</em> turns,
 * decide whether live agent quality has degraded below configured bounds. This mirrors
 * {@link CapabilityEvalRegressionGate} (the offline L3 gate) but operates on the online
 * production distribution rather than a curated eval set. It is a pure function — no DB,
 * Spring, or LLM — so the threshold logic is unit-tested in isolation.
 *
 * <p>An empty sample (no turns in the window) is a no-op pass ({@code no_sample}), never a
 * violation: there is simply nothing to judge. Bounds are inclusive — a value exactly at
 * the bound is acceptable.
 */
public final class OnlineEvalQualityGate {

    private OnlineEvalQualityGate() {
    }

    /** Lower/upper bounds on the four aggregate online-quality signals. */
    public record Thresholds(double minHealthyRate, double maxFailRate,
                             double maxCostFlaggedRate, double minAvgScore) {
    }

    /**
     * One breached signal.
     *
     * @param below true if the value fell below a lower bound; false if it exceeded an
     *              upper bound.
     */
    public record Violation(String dimension, double value, double bound, boolean below) {
    }

    public record Verdict(boolean ok, String summary, List<Violation> violations) {
    }

    public static Verdict evaluate(OnlineEvalSummary summary, Thresholds t) {
        if (summary == null || summary.sampledTurns() == 0) {
            return new Verdict(true, "no_sample", List.of());
        }
        List<Violation> violations = new ArrayList<>();
        if (summary.healthyRate() < t.minHealthyRate()) {
            violations.add(new Violation("healthyRate", summary.healthyRate(), t.minHealthyRate(), true));
        }
        if (summary.failRate() > t.maxFailRate()) {
            violations.add(new Violation("failRate", summary.failRate(), t.maxFailRate(), false));
        }
        if (summary.costFlaggedRate() > t.maxCostFlaggedRate()) {
            violations.add(new Violation("costFlaggedRate", summary.costFlaggedRate(), t.maxCostFlaggedRate(), false));
        }
        if (summary.avgScore() < t.minAvgScore()) {
            violations.add(new Violation("avgScore", summary.avgScore(), t.minAvgScore(), true));
        }
        if (violations.isEmpty()) {
            return new Verdict(true, String.format(
                    "ok (n=%d, healthy=%.2f, fail=%.2f, avg=%.2f)",
                    summary.sampledTurns(), summary.healthyRate(), summary.failRate(), summary.avgScore()),
                    List.of());
        }
        StringJoiner sj = new StringJoiner(", ");
        for (Violation v : violations) {
            sj.add(String.format("%s=%.2f %s %.2f", v.dimension(), v.value(),
                    v.below() ? "<" : ">", v.bound()));
        }
        return new Verdict(false, "degraded: " + sj, violations);
    }
}
