package com.auraboot.framework.billing.metering.model;

/**
 * Lifecycle status for a usage event in the rating pipeline.
 *
 * <p>Values MUST match the {@code chk_billing_ue_rating_status} CHECK constraint in
 * {@code 2026-06-10-billing-metering.sql} verbatim (case-sensitive).
 *
 * <p>Transitions:
 * <pre>
 *   PENDING → RATED → BILLED
 *          ↘ SKIPPED
 * </pre>
 *
 * <ul>
 *   <li>{@link #PENDING}  — received but not yet priced by the rating engine
 *   <li>{@link #RATED}    — priced; waiting for invoice cycle (enterprise M3)
 *   <li>{@link #BILLED}   — included in an invoice (enterprise M3)
 *   <li>{@link #SKIPPED}  — exempt or below threshold; will not be billed
 * </ul>
 */
public enum RatingStatus {
    /** Event received; rating not yet performed. */
    PENDING,
    /** Event priced by the rating engine (enterprise M3). */
    RATED,
    /** Event included in a finalized invoice (enterprise M3). */
    BILLED,
    /** Event is exempt / below threshold / not billable. */
    SKIPPED
}
