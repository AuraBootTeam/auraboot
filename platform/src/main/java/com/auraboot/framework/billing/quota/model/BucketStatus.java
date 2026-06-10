package com.auraboot.framework.billing.quota.model;

/**
 * Lifecycle status shared by both quota buckets and pools.
 *
 * <p>Values MUST match the {@code chk_billing_bucket_status} and
 * {@code chk_billing_pool_status} CHECK constraints in
 * {@code 2026-06-10-billing-quota.sql} exactly.
 */
public enum BucketStatus {
    /** Normal operating state; quota can be reserved and consumed. */
    ACTIVE,
    /** Period has ended; no new reservations permitted. */
    EXPIRED,
    /** Temporarily suspended; no new reservations (admin action). */
    FROZEN,
    /** All quota consumed; treated as zero-balance. */
    DEPLETED
}
