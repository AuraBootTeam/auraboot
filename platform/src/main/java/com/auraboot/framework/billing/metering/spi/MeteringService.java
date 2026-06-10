package com.auraboot.framework.billing.metering.spi;

import com.auraboot.framework.billing.metering.model.UsageEvent;

import java.util.List;
import java.util.Optional;

/**
 * SPI for the Metering subsystem — idempotent usage event recording and query.
 *
 * <p>Enterprise or plugin modules may replace the default DB-backed implementation
 * ({@link com.auraboot.framework.billing.metering.service.MeteringServiceImpl}) with a
 * higher-fidelity version that adds real-time streaming, pre-aggregation, or
 * external ledger integration.
 *
 * <h3>Write path: record()</h3>
 * <p>The {@link #record} method is the single entry point for all usage ingestion.
 * It is intended to be called internally by platform services (e.g. ACP worker,
 * automation engine) — <em>not</em> exposed as a public HTTP write endpoint in the
 * OSS tier (P2-4).  The OSS HTTP layer only exposes read endpoints.
 *
 * <h3>Idempotency</h3>
 * <p>Idempotency is enforced by the DB UNIQUE constraint on
 * {@code (source_service, idempotency_key)}.  Three outcomes:
 * <ul>
 *   <li><b>ACCEPTED</b>    — first occurrence; event persisted.
 *   <li><b>DUPLICATE_IGNORED</b> — same key, matching payload; original event_code returned.
 *   <li><b>CONFLICT</b>    — same key, different payload; logged to conflict table; not charged.
 * </ul>
 *
 * <h3>Payload equality</h3>
 * <p>Two requests are payload-equal if {@code resourceCode}, {@code quantity},
 * and {@code occurredAt} are all equal.  Other optional fields (metadata, userId, etc.)
 * are tolerated to differ in re-submissions.
 *
 * <h3>Concurrency</h3>
 * <p>The OSS impl catches {@code DataIntegrityViolationException} on concurrent inserts
 * and re-routes to the DUPLICATE / CONFLICT path without double-writing.
 *
 * <h3>Validation</h3>
 * <p>The following fields are required: {@code idempotencyKey}, {@code accountId},
 * {@code resourceCode}, {@code quantity} (≥ 0), {@code unit}, {@code occurredAt},
 * {@code sourceService}.  Missing or invalid fields return {@link MeteringResultStatus#REJECTED}.
 * {@code resourceCode} must also be registered and ACTIVE in the resource catalog.
 */
public interface MeteringService {

    /**
     * Record a usage event idempotently.
     *
     * <p>Validates the request, performs dedup lookup, and either:
     * <ul>
     *   <li>Inserts a new {@code UsageEvent} (ACCEPTED), or
     *   <li>Returns the existing event code (DUPLICATE_IGNORED), or
     *   <li>Writes a conflict record and returns CONFLICT without charging, or
     *   <li>Returns REJECTED if validation fails.
     * </ul>
     *
     * @param request the usage event request; must not be null
     * @return a non-null {@link MeteringResult} describing the outcome
     */
    MeteringResult record(UsageEventRequest request);

    /**
     * Look up a usage event by its server-assigned event code.
     *
     * @param eventCode the stable external event code (e.g. {@code "UE-..."})
     * @return the event, or empty if not found
     */
    Optional<UsageEvent> findByCode(String eventCode);

    /**
     * List usage events for an account and resource type, ordered by
     * {@code occurred_at} descending (most recent first).
     *
     * <p>This is a read-only query with a hard limit to prevent large scans.
     * For production analytics, use the enterprise M3 reporting pipeline.
     *
     * @param accountId    account identifier
     * @param resourceCode resource type code
     * @param limit        maximum number of results to return (capped at 1000)
     * @return events ordered by occurred_at DESC; empty if none found
     */
    List<UsageEvent> listByAccount(Long accountId, String resourceCode, int limit);
}
