package com.auraboot.framework.billing.metering.model;

/**
 * Deduplication outcome for a usage event record.
 *
 * <p>Values MUST match the {@code chk_billing_ue_dedupe_status} CHECK constraint in
 * {@code 2026-06-10-billing-metering.sql} verbatim (case-sensitive).
 *
 * <p>Only {@link #UNIQUE} events are stored in {@code ab_billing_usage_event}.
 * {@link #DUPLICATE} and {@link #CONFLICT} are handled by {@link MeteringResultStatus}
 * at the SPI level (DUPLICATE is silently ignored; CONFLICT is written to
 * {@code ab_billing_usage_dedupe_conflict} for investigation).
 *
 * <ul>
 *   <li>{@link #UNIQUE}    — first occurrence; stored and eligible for rating
 *   <li>{@link #DUPLICATE} — same key + matching payload; not stored again (idempotent success)
 *   <li>{@link #CONFLICT}  — same key but different payload; logged to conflict table, not billed
 * </ul>
 */
public enum DedupeStatus {
    /** First occurrence; stored in usage_event and eligible for rating. */
    UNIQUE,
    /** Same (source_service, idempotency_key) with matching payload — safe to ignore. */
    DUPLICATE,
    /** Same (source_service, idempotency_key) but payload differs — conflict logged, not charged. */
    CONFLICT
}
