package com.auraboot.plugins.crm.engine;

/**
 * Pure customer health-score computation (CRM gap #10).
 *
 * <p>Turns a set of account engagement/risk signals into a 0-100 score and a
 * band (healthy / at_risk / critical). Separated from the handler so the scoring
 * rules are unit-testable without a Spring context or database.
 *
 * <p>Rules (start at {@link #BASE}, then clamp to 0..100):
 * <ul>
 *   <li>+5 per won opportunity, capped at +20 (positive traction);</li>
 *   <li>+3 per open opportunity, capped at +15 (active pipeline);</li>
 *   <li>-15 per open complaint, capped at -45 (service risk);</li>
 *   <li>recency penalty: never engaged -10, &gt;90 days -20, &gt;30 days -10.</li>
 * </ul>
 * Band: score &gt;= 70 healthy; 40..69 at_risk; &lt; 40 critical.
 */
public final class HealthScoreEngine {

    private HealthScoreEngine() {
    }

    public static final int BASE = 70;

    /** Aggregated signals for one account. {@code daysSinceLastActivity} &lt; 0 means never engaged. */
    public record Signals(int openComplaints, int wonOpportunities, int openOpportunities,
                          int daysSinceLastActivity) {
    }

    public record Result(int score, String band) {
    }

    public static Result compute(Signals s) {
        int score = BASE;
        score += Math.min(s.wonOpportunities() * 5, 20);
        score += Math.min(s.openOpportunities() * 3, 15);
        score -= Math.min(s.openComplaints() * 15, 45);
        int d = s.daysSinceLastActivity();
        if (d < 0) {
            score -= 10;
        } else if (d > 90) {
            score -= 20;
        } else if (d > 30) {
            score -= 10;
        }
        score = Math.max(0, Math.min(100, score));
        String band = score >= 70 ? "healthy" : score >= 40 ? "at_risk" : "critical";
        return new Result(score, band);
    }
}
