package com.auraboot.framework.billing.metering.spi;

/**
 * Outcome codes returned by {@link MeteringService#record}.
 *
 * <ul>
 *   <li>{@link #ACCEPTED}          — new event recorded; dedupe_status = UNIQUE
 *   <li>{@link #DUPLICATE_IGNORED} — same key + matching payload; idempotent success
 *   <li>{@link #CONFLICT}          — same key, different payload; logged, not charged
 *   <li>{@link #REJECTED}          — validation failed (e.g. unregistered resource_code)
 * </ul>
 */
public enum MeteringResultStatus {
    /** New event was accepted and persisted. */
    ACCEPTED,
    /** Duplicate submission with matching payload — treated as idempotent success. */
    DUPLICATE_IGNORED,
    /** Same idempotency key but different payload — logged to conflict table, not charged. */
    CONFLICT,
    /** Request failed validation (unregistered resource, missing required field, etc.). */
    REJECTED
}
