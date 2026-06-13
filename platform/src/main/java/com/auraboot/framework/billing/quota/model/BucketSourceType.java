package com.auraboot.framework.billing.quota.model;

/**
 * How a quota bucket was originally provisioned.
 *
 * <p>Values MUST match the {@code chk_billing_bucket_source_type} CHECK constraint
 * in {@code 2026-06-10-billing-quota.sql} exactly.
 *
 * <p>The {@link #consumptionOrder()} method returns the relative priority for quota
 * consumption: lower value = consumed first.  The canonical ordering is:
 * <pre>
 *   PROMOTION(1) &lt; MANUAL_GRANT(2) &lt; ADD_ON(3) &lt; BASE_PLAN(4) &lt; ROLLOVER(5) &lt; PREPAID_CREDIT(6)
 * </pre>
 * Rationale: free/promotional quota should be used before purchased quota, and
 * carry-over / prepaid blocks should be preserved as long as possible.
 */
public enum BucketSourceType {
    /** Included in the base subscription plan. */
    BASE_PLAN,
    /** Purchased as a paid add-on. */
    ADD_ON,
    /** Granted via a promotion or coupon. */
    PROMOTION,
    /** Carried over from a previous billing period. */
    ROLLOVER,
    /** Manually granted by an operator/admin. */
    MANUAL_GRANT,
    /** Pre-purchased credit block (e.g. commit-to-use). */
    PREPAID_CREDIT;

    /**
     * Returns the relative consumption priority for this source type.
     * Lower value = consumed first during a quota reservation.
     *
     * <p>Ordering: PROMOTION(1) &lt; MANUAL_GRANT(2) &lt; ADD_ON(3)
     *              &lt; BASE_PLAN(4) &lt; ROLLOVER(5) &lt; PREPAID_CREDIT(6)
     *
     * <p>This is the single source of truth for source-type ordering — do not
     * scatter magic numbers elsewhere.
     */
    public int consumptionOrder() {
        return switch (this) {
            case PROMOTION      -> 1;
            case MANUAL_GRANT   -> 2;
            case ADD_ON         -> 3;
            case BASE_PLAN      -> 4;
            case ROLLOVER       -> 5;
            case PREPAID_CREDIT -> 6;
        };
    }
}
