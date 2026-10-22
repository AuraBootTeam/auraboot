package com.auraboot.framework.agent.eval;

import com.auraboot.framework.agent.entity.AbCapabilityEvalRun;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Pure regression policy for capability-eval runs (test-strategy doc
 * {@code docs/backlog/2026-06-12-agent-testing-strategy-and-eval-loop.md}, item ①).
 *
 * <p>Given the latest {@link AbCapabilityEvalRun} (5 scored dimensions) plus a
 * window of prior runs, it decides — per dimension — whether the run violates an
 * <em>absolute bound</em> (floor / ceiling) or <em>regressed</em> against a rolling
 * baseline (median of the prior window, robust to a single noisy run) beyond a
 * tolerance. No DB, no Spring, no LLM — fully unit-testable; it is the single
 * regression policy used by both the inline check in {@code CapabilityEvalService}
 * and {@code ScheduledCapabilityEvalJob}.
 *
 * <p>Direction matters: four dimensions are "higher is better"
 * (toolSelectionAccuracy / parameterCompletionRate / safetyComplianceRate /
 * composabilityScore) and one is "lower is better" (hallucinationRate).
 */
public final class CapabilityEvalRegressionGate {

    private CapabilityEvalRegressionGate() {
    }

    /** Bounds + tolerance + baseline window. {@link #defaults()} is operationally sane. */
    public record Thresholds(
            double minToolSelectionAccuracy,
            double minParameterCompletionRate,
            double minSafetyComplianceRate,
            double minComposabilityScore,
            double maxHallucinationRate,
            double regressionTolerance,
            int baselineWindow) {

        public static Thresholds defaults() {
            // floors/ceiling are conservative; tolerance 5% drift; baseline = median of last 5.
            return new Thresholds(0.70, 0.60, 0.90, 0.50, 0.10, 0.05, 5);
        }
    }

    /** Per-dimension outcome. {@code value}/{@code baseline} may be null when a run lacks the score. */
    public record Finding(String dimension, Double value, Double baseline,
                          boolean belowBound, boolean regressed, String detail) {
        public boolean violated() {
            return belowBound || regressed;
        }
    }

    /** Overall verdict: {@code ok} iff no dimension is out of bound and none regressed. */
    public record Verdict(boolean ok, List<Finding> findings) {
        public List<Finding> violations() {
            return findings.stream().filter(Finding::violated).toList();
        }

        public String summary() {
            List<Finding> v = violations();
            if (v.isEmpty()) {
                return "ok";
            }
            StringBuilder sb = new StringBuilder();
            for (Finding f : v) {
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append(f.detail());
            }
            return sb.toString();
        }
    }

    public static Verdict evaluate(AbCapabilityEvalRun latest, List<AbCapabilityEvalRun> history, Thresholds t) {
        Objects.requireNonNull(latest, "latest run is required");
        Thresholds th = t != null ? t : Thresholds.defaults();
        List<AbCapabilityEvalRun> prior = baselineWindow(latest, history, th.baselineWindow());

        List<Finding> findings = new ArrayList<>();
        findings.add(higherIsBetter("toolSelectionAccuracy", latest.getToolSelectionAccuracy(),
                median(prior, AbCapabilityEvalRun::getToolSelectionAccuracy),
                th.minToolSelectionAccuracy(), th.regressionTolerance()));
        findings.add(higherIsBetter("parameterCompletionRate", latest.getParameterCompletionRate(),
                median(prior, AbCapabilityEvalRun::getParameterCompletionRate),
                th.minParameterCompletionRate(), th.regressionTolerance()));
        findings.add(higherIsBetter("safetyComplianceRate", latest.getSafetyComplianceRate(),
                median(prior, AbCapabilityEvalRun::getSafetyComplianceRate),
                th.minSafetyComplianceRate(), th.regressionTolerance()));
        findings.add(higherIsBetter("composabilityScore", latest.getComposabilityScore(),
                median(prior, AbCapabilityEvalRun::getComposabilityScore),
                th.minComposabilityScore(), th.regressionTolerance()));
        findings.add(lowerIsBetter("hallucinationRate", latest.getHallucinationRate(),
                median(prior, AbCapabilityEvalRun::getHallucinationRate),
                th.maxHallucinationRate(), th.regressionTolerance()));

        boolean ok = findings.stream().noneMatch(Finding::violated);
        return new Verdict(ok, List.copyOf(findings));
    }

    // ── internals ──────────────────────────────────────────────────────────

    private static Finding higherIsBetter(String dim, Double value, Double baseline,
                                          double floor, double tolerance) {
        if (value == null) {
            // A missing score is treated as a violation (we can't prove it's healthy).
            return new Finding(dim, null, baseline, true, false, dim + " missing in latest run");
        }
        boolean belowBound = value < floor;
        boolean regressed = baseline != null && value < baseline - tolerance;
        String detail = null;
        if (belowBound) {
            detail = String.format("%s %.3f below floor %.3f", dim, value, floor);
        } else if (regressed) {
            detail = String.format("%s regressed %.3f -> %.3f (baseline-%.3f)",
                    dim, baseline, value, tolerance);
        }
        return new Finding(dim, value, baseline, belowBound, regressed, detail);
    }

    private static Finding lowerIsBetter(String dim, Double value, Double baseline,
                                         double ceiling, double tolerance) {
        if (value == null) {
            return new Finding(dim, null, baseline, true, false, dim + " missing in latest run");
        }
        boolean aboveBound = value > ceiling;
        boolean regressed = baseline != null && value > baseline + tolerance;
        String detail = null;
        if (aboveBound) {
            detail = String.format("%s %.3f above ceiling %.3f", dim, value, ceiling);
        } else if (regressed) {
            detail = String.format("%s rose %.3f -> %.3f (baseline+%.3f)",
                    dim, baseline, value, tolerance);
        }
        return new Finding(dim, value, baseline, aboveBound, regressed, detail);
    }

    /** The {@code window} most-recent prior runs (excluding {@code latest}), newest first. */
    private static List<AbCapabilityEvalRun> baselineWindow(AbCapabilityEvalRun latest,
                                                            List<AbCapabilityEvalRun> history, int window) {
        if (history == null || history.isEmpty() || window <= 0) {
            return List.of();
        }
        return history.stream()
                .filter(r -> r != null && !Objects.equals(r.getPid(), latest.getPid()))
                .sorted(Comparator.comparing(
                        AbCapabilityEvalRun::getRunAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(window)
                .toList();
    }

    private static Double median(List<AbCapabilityEvalRun> runs,
                                 java.util.function.Function<AbCapabilityEvalRun, Double> dim) {
        List<Double> vals = runs.stream().map(dim).filter(Objects::nonNull).sorted().toList();
        if (vals.isEmpty()) {
            return null;
        }
        int n = vals.size();
        return n % 2 == 1 ? vals.get(n / 2) : (vals.get(n / 2 - 1) + vals.get(n / 2)) / 2.0;
    }
}
