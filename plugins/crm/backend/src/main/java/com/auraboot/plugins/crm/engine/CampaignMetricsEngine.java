package com.auraboot.plugins.crm.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure marketing-campaign metrics computation (CRM gap #8).
 *
 * <p>Derives response rate, conversion rate and cost-per-member from a
 * campaign's member counts and budget. Separated from the handler so the rules
 * are unit-testable without a Spring context or database.
 *
 * <p>Rates are percentages (0..100). Division guards: zero members yields zero
 * rates (no divide-by-zero); zero budget yields zero cost-per-member.
 */
public final class CampaignMetricsEngine {

    private CampaignMetricsEngine() {
    }

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public record Input(int totalMembers, int respondedMembers, int convertedMembers, BigDecimal budget) {
    }

    public record Result(BigDecimal responseRate, BigDecimal conversionRate, BigDecimal costPerMember) {
    }

    public static Result compute(Input in) {
        int total = Math.max(0, in.totalMembers());
        BigDecimal budget = in.budget() == null || in.budget().signum() < 0 ? BigDecimal.ZERO : in.budget();
        if (total == 0) {
            return new Result(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        BigDecimal totalBd = new BigDecimal(total);
        BigDecimal responseRate = pct(in.respondedMembers(), totalBd);
        BigDecimal conversionRate = pct(in.convertedMembers(), totalBd);
        BigDecimal costPerMember = budget.signum() == 0 ? BigDecimal.ZERO
                : budget.divide(totalBd, 2, RoundingMode.HALF_UP);
        return new Result(responseRate, conversionRate, costPerMember);
    }

    private static BigDecimal pct(int part, BigDecimal total) {
        int p = Math.max(0, part);
        return new BigDecimal(p).multiply(HUNDRED).divide(total, 2, RoundingMode.HALF_UP);
    }
}
