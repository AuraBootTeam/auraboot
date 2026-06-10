package com.auraboot.framework.billing.quota.model;

/**
 * Ledger operation types.
 *
 * <p>Values MUST match the {@code chk_billing_ledger_operation_type} CHECK constraint
 * in {@code 2026-06-10-billing-quota.sql} exactly (case-sensitive).
 */
public enum OperationType {
    /** Initial grant — adds quota to a bucket (e.g. plan provisioned). */
    GRANT,
    /** Optimistic pre-authorization — reserves estimated amount. */
    RESERVE,
    /** Finalise a reservation with actual consumed quantity. */
    COMMIT,
    /** Release a reservation without committing (cancellation / expiry). */
    RELEASE,
    /** Direct consumption without a prior reservation (real-time path). */
    CONSUME,
    /** Refund a previous consumption. */
    REFUND,
    /** Periodic expiry of unused quota. */
    EXPIRE,
    /** Manual admin adjustment. */
    ADJUST,
    /** Full reset of a bucket's counters (e.g. new billing period). */
    RESET
}
