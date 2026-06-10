package com.auraboot.framework.billing.quota.model;

/**
 * How a quota bucket was originally provisioned.
 *
 * <p>Values MUST match the {@code chk_billing_bucket_source_type} CHECK constraint
 * in {@code 2026-06-10-billing-quota.sql} exactly.
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
    PREPAID_CREDIT
}
