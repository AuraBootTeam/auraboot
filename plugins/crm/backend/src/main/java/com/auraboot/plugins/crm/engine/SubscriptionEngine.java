package com.auraboot.plugins.crm.engine;

import java.math.BigDecimal;

/**
 * Pure subscription metrics + lifecycle computation (CRM gap #15).
 *
 * <p>Derives MRR / ARR and a lifecycle status from a subscription's monthly
 * amount and how many days remain until it ends. Separated from the handler so
 * the rules are unit-testable without a Spring context or database.
 *
 * <p>Status:
 * <ul>
 *   <li>{@code pending} — not started yet;</li>
 *   <li>{@code expired} — end date already passed (daysUntilEnd &lt; 0);</li>
 *   <li>{@code renewal_due} — ends within {@link #RENEWAL_WINDOW_DAYS} days;</li>
 *   <li>{@code active} — otherwise.</li>
 * </ul>
 */
public final class SubscriptionEngine {

    private SubscriptionEngine() {
    }

    public static final int RENEWAL_WINDOW_DAYS = 30;
    private static final BigDecimal MONTHS_PER_YEAR = new BigDecimal("12");

    public record Input(BigDecimal monthlyAmount, int daysUntilEnd, boolean started) {
    }

    public record Result(BigDecimal mrr, BigDecimal arr, String status) {
    }

    public static Result compute(Input in) {
        BigDecimal mrr = in.monthlyAmount() == null ? BigDecimal.ZERO : in.monthlyAmount();
        if (mrr.signum() < 0) {
            mrr = BigDecimal.ZERO;
        }
        BigDecimal arr = mrr.multiply(MONTHS_PER_YEAR);
        String status;
        if (!in.started()) {
            status = "pending";
        } else if (in.daysUntilEnd() < 0) {
            status = "expired";
        } else if (in.daysUntilEnd() <= RENEWAL_WINDOW_DAYS) {
            status = "renewal_due";
        } else {
            status = "active";
        }
        return new Result(mrr, arr, status);
    }
}
