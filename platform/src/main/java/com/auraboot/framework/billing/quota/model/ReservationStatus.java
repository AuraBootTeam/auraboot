package com.auraboot.framework.billing.quota.model;

/**
 * Lifecycle status of a {@code ab_billing_quota_reservation} row.
 *
 * <p>Values MUST match the {@code chk_billing_reservation_status} CHECK constraint
 * in {@code 2026-06-10-billing-quota.sql} exactly.
 */
public enum ReservationStatus {
    /** Reservation is open — reserved amounts are held in buckets. */
    ACTIVE,
    /** Reservation has been committed (actual quantity recorded). */
    COMMITTED,
    /** Reservation was released without committing. */
    RELEASED,
    /** Reservation expired (TTL elapsed, auto-released). */
    EXPIRED
}
