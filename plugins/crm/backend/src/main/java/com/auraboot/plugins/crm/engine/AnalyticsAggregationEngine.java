package com.auraboot.plugins.crm.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Pure sales-analytics aggregation (CRM gap #17 DWH, M1 slice).
 *
 * <p>Rolls a collection of opportunities up into the materialized snapshot
 * metrics (total / won count / won amount / pipeline amount / conversion rate).
 * Separated from the refresh handler so the roll-up is unit-testable without a
 * Spring context or database — this is the L0→L1 materialization logic.
 *
 * <p>"Open" (pipeline) = any stage that is not {@code closed_won} or
 * {@code closed_lost}. Conversion rate is a 0..100 percentage; an empty input
 * yields all-zero metrics (no divide-by-zero).
 */
public final class AnalyticsAggregationEngine {

    private AnalyticsAggregationEngine() {
    }

    public static final String CLOSED_WON = "closed_won";
    public static final String CLOSED_LOST = "closed_lost";
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public record Opp(String stage, BigDecimal amount) {
    }

    public record Result(int totalOpps, int wonCount, BigDecimal wonAmount,
                         BigDecimal pipelineAmount, BigDecimal conversionRate) {
    }

    public static Result aggregate(List<Opp> opps) {
        if (opps == null || opps.isEmpty()) {
            return new Result(0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        int total = 0;
        int won = 0;
        BigDecimal wonAmount = BigDecimal.ZERO;
        BigDecimal pipelineAmount = BigDecimal.ZERO;
        for (Opp o : opps) {
            if (o == null) {
                continue;
            }
            total++;
            String stage = o.stage() == null ? "" : o.stage();
            BigDecimal amt = o.amount() == null ? BigDecimal.ZERO : o.amount();
            if (CLOSED_WON.equals(stage)) {
                won++;
                wonAmount = wonAmount.add(amt);
            } else if (!CLOSED_LOST.equals(stage)) {
                pipelineAmount = pipelineAmount.add(amt);
            }
        }
        BigDecimal conversion = total == 0 ? BigDecimal.ZERO
                : new BigDecimal(won).multiply(HUNDRED).divide(new BigDecimal(total), 2, RoundingMode.HALF_UP);
        return new Result(total, won, wonAmount, pipelineAmount, conversion);
    }
}
