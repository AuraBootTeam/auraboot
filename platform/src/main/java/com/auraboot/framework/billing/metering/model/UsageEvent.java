package com.auraboot.framework.billing.metering.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable usage event record — the fundamental unit of metering.
 *
 * <p>Backed by {@code ab_billing_usage_event}.  Once a row is inserted with
 * {@code dedupe_status = UNIQUE} it is never updated except for {@code rating_status}
 * transitions (PENDING → RATED → BILLED | SKIPPED), which are performed by the
 * enterprise M3 rating engine.
 *
 * <p>Deduplication is enforced by the DB UNIQUE constraint on
 * {@code (source_service, idempotency_key)}.  The OSS insert path catches
 * {@code DataIntegrityViolationException} and re-queries to determine whether
 * the conflict is a true DUPLICATE (matching payload) or a CONFLICT (different payload).
 *
 * <p>Convention: {@code id BIGINT} → {@link IdType#ASSIGN_ID} (snowflake).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("ab_billing_usage_event")
public class UsageEvent {

    /** Snowflake ID — primary key. */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * Server-assigned stable external identifier for this event.
     * Format: {@code UE-<RANDOM>}.
     */
    private String eventCode;

    /**
     * Caller-supplied idempotency key.
     * Combined with {@link #sourceService} forms the unique dedup key.
     */
    private String idempotencyKey;

    /** Owning account. */
    private Long accountId;

    /** Optional workspace scoping (NULL = account-level). */
    private Long workspaceId;

    /** Optional user who triggered the event (NULL if system-generated). */
    private Long userId;

    /** Optional linked subscription (NULL if not subscription-scoped). */
    private Long subscriptionId;

    /**
     * Resource type — must be registered in {@code ab_billing_resource_catalog}.
     * Example: {@code AI_TOKEN}, {@code WORKFLOW_EXECUTION}.
     */
    private String resourceCode;

    /** Optional sub-classification of the resource (e.g. model variant for AI_TOKEN). */
    private String resourceSubtype;

    /**
     * Usage quantity in the resource's native unit.
     * Scale 6 matches the DB {@code DECIMAL(24,6)}.
     */
    private BigDecimal quantity;

    /** Unit of measure — must match the resource catalog entry. */
    private String unit;

    /** When the usage actually occurred (caller-supplied, may differ from received_at). */
    private Instant occurredAt;

    /** When this event was received by the billing system (set by server). */
    private Instant receivedAt;

    /**
     * Service that produced this event.
     * Example: {@code "acp-worker"}, {@code "automation-engine"}.
     */
    private String sourceService;

    /** Optional reference into the producing service (e.g. agent_run_id). */
    private String sourceRef;

    /**
     * Rating lifecycle status.
     * Stored as VARCHAR; matched to {@link RatingStatus}.
     * New events start as {@code PENDING}.
     */
    private String ratingStatus;

    /**
     * Deduplication outcome.
     * Stored as VARCHAR; matched to {@link DedupeStatus}.
     * All persisted rows have {@code UNIQUE} (duplicates are not stored).
     */
    private String dedupeStatus;

    /** Arbitrary extra metadata as JSON text (caller-supplied, may be null). */
    private String metadataJson;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
}
