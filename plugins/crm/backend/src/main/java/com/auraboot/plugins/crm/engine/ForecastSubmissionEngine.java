package com.auraboot.plugins.crm.engine;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure validation logic for a sales-forecast submission (CRM gap #5b).
 *
 * <p>A rep commits, per forecast period, three monotonic figures that mirror the
 * Salesforce-style forecast categories — each a superset of the previous:
 * <pre>
 *   commit &lt;= bestCase &lt;= pipeline,  all &gt;= 0
 * </pre>
 * <ul>
 *   <li>{@code commit} — deals the rep will close ("money in the bank");</li>
 *   <li>{@code bestCase} — commit plus deals that could realistically close;</li>
 *   <li>{@code pipeline} — best case plus the remaining open pipeline.</li>
 * </ul>
 *
 * <p>Separated from {@code SubmitForecastHandler} so the rules are unit-testable
 * without a Spring context or database. Fail-loud (red line §8): violations are
 * returned, never silently coerced.
 */
public final class ForecastSubmissionEngine {

    private ForecastSubmissionEngine() {
    }

    /** A rep's forecast figures for one period. Null amounts are treated as zero. */
    public record Submission(BigDecimal commit, BigDecimal bestCase, BigDecimal pipeline, String period) {
    }

    /** Validation outcome. {@code valid} is true iff {@code violations} is empty. */
    public record Result(boolean valid, List<String> violations) {
    }

    public static Result validate(Submission s) {
        if (s == null) {
            return new Result(false, List.of("submission is null"));
        }
        List<String> v = new ArrayList<>();
        if (s.period() == null || s.period().isBlank()) {
            v.add("period is required");
        }
        BigDecimal commit = nz(s.commit());
        BigDecimal best = nz(s.bestCase());
        BigDecimal pipeline = nz(s.pipeline());
        if (commit.signum() < 0) {
            v.add("commit amount must be >= 0");
        }
        if (best.signum() < 0) {
            v.add("best_case amount must be >= 0");
        }
        if (pipeline.signum() < 0) {
            v.add("pipeline amount must be >= 0");
        }
        if (commit.compareTo(best) > 0) {
            v.add("commit must not exceed best_case");
        }
        if (best.compareTo(pipeline) > 0) {
            v.add("best_case must not exceed pipeline");
        }
        return new Result(v.isEmpty(), List.copyOf(v));
    }

    private static BigDecimal nz(BigDecimal b) {
        return b == null ? BigDecimal.ZERO : b;
    }
}
