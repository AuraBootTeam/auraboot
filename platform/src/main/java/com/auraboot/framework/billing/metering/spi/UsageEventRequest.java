package com.auraboot.framework.billing.metering.spi;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Request object for recording a single usage event via {@link MeteringService#record}.
 *
 * <h3>Required fields</h3>
 * <ul>
 *   <li>{@link #idempotencyKey} — caller-supplied dedup key (unique per source_service)
 *   <li>{@link #accountId}      — owning account
 *   <li>{@link #resourceCode}   — resource type (must be registered in the catalog)
 *   <li>{@link #quantity}       — usage quantity (must be {@literal ≥ 0})
 *   <li>{@link #unit}           — unit of measure
 *   <li>{@link #occurredAt}     — when the usage actually occurred
 *   <li>{@link #sourceService}  — name of the service submitting this event
 * </ul>
 *
 * <h3>Optional fields</h3>
 * <ul>
 *   <li>{@link #workspaceId}    — workspace scoping (null = account-level)
 *   <li>{@link #userId}         — user who triggered the event
 *   <li>{@link #subscriptionId} — linked subscription
 *   <li>{@link #resourceSubtype} — sub-classification (e.g. model variant)
 *   <li>{@link #sourceRef}      — reference into the producing service
 *   <li>{@link #metadataJson}   — arbitrary extra metadata as JSON string
 * </ul>
 *
 * <h3>Payload equality for dedup</h3>
 * <p>Two requests with the same {@code (sourceService, idempotencyKey)} are considered
 * payload-equal if {@link #resourceCode}, {@link #quantity}, and {@link #occurredAt}
 * are all equal.  Any other field difference is tolerated in DUPLICATE re-submissions.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UsageEventRequest {

    // ── Required ──────────────────────────────────────────────────────────────

    /** Caller-supplied dedup key; unique per {@link #sourceService}. */
    private String idempotencyKey;

    /** Owning account. */
    private Long accountId;

    /**
     * Resource type code — must be registered and ACTIVE in the resource catalog.
     * Example: {@code "AI_TOKEN"}, {@code "WORKFLOW_EXECUTION"}.
     */
    private String resourceCode;

    /**
     * Usage quantity in the resource's native unit.
     * Must be {@literal ≥ 0}.
     */
    private BigDecimal quantity;

    /** Unit of measure. Must match the resource catalog entry's unit. */
    private String unit;

    /** When the usage actually occurred (caller-supplied timestamp). */
    private Instant occurredAt;

    /**
     * Name of the service submitting this event.
     * Example: {@code "acp-worker"}, {@code "automation-engine"}.
     */
    private String sourceService;

    // ── Optional ──────────────────────────────────────────────────────────────

    /** Optional workspace scoping (null = account-level event). */
    private Long workspaceId;

    /** Optional user who triggered the event (null if system-generated). */
    private Long userId;

    /** Optional linked subscription. */
    private Long subscriptionId;

    /** Optional sub-classification of the resource (e.g. model variant for AI_TOKEN). */
    private String resourceSubtype;

    /** Optional reference into the producing service (e.g. an agent_run_id). */
    private String sourceRef;

    /** Arbitrary extra metadata as a JSON string (may be null). */
    private String metadataJson;
}
