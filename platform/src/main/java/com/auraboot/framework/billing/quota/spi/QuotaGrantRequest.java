package com.auraboot.framework.billing.quota.spi;

import com.auraboot.framework.billing.quota.model.BucketSourceType;
import com.auraboot.framework.billing.quota.model.OveragePolicy;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Input to {@link QuotaService#provision}.
 *
 * <p>Describes a single quota grant: the account/subscription context, the
 * resource being provisioned, the amount, and the valid period.  Idempotency
 * is controlled via {@link #idempotencyKey} — repeated calls with the same key
 * return the original bucket without creating duplicates.
 *
 * <h3>Required fields</h3>
 * <ul>
 *   <li>{@link #accountId} — the account receiving the quota
 *   <li>{@link #subscriptionId} — the subscription triggering provisioning
 *   <li>{@link #resourceCode} — must be registered in {@code ab_billing_resource_catalog}
 *   <li>{@link #amount} — total quota units to provision (positive)
 *   <li>{@link #unit} — unit of measure (e.g. "TOKEN", "API_CALL")
 *   <li>{@link #periodStart} / {@link #periodEnd} — validity window
 *   <li>{@link #sourceType} — how this quota was provisioned
 *   <li>{@link #idempotencyKey} — caller-controlled deduplication key (max 128 chars)
 * </ul>
 *
 * <h3>Optional fields</h3>
 * <ul>
 *   <li>{@link #workspaceId} — workspace scope (null = account-level)
 *   <li>{@link #userId} — user scope (null = account-level)
 *   <li>{@link #priority} — bucket consumption priority (default 100)
 *   <li>{@link #overagePolicy} — policy when exhausted (default HARD_LIMIT)
 * </ul>
 */
@Value
@Builder
public class QuotaGrantRequest {

    /** Account that receives the quota grant. */
    Long accountId;

    /** Linked subscription that triggered this grant. */
    Long subscriptionId;

    /** Optional workspace scope; null = account-level. */
    Long workspaceId;

    /** Optional user scope; null = account-level. */
    Long userId;

    /**
     * Resource type code.
     * Must be registered in {@code ab_billing_resource_catalog}.
     */
    String resourceCode;

    /** Total quota units to grant.  Must be positive. */
    BigDecimal amount;

    /** Unit of measure (must match the resource catalog entry). */
    String unit;

    /** Start of the valid period for this bucket (inclusive). */
    Instant periodStart;

    /** End of the valid period for this bucket (inclusive). */
    Instant periodEnd;

    /** How this quota was provisioned. */
    BucketSourceType sourceType;

    /**
     * Bucket consumption priority.
     * Lower value = consumed first.  Defaults to 100 when null.
     */
    Integer priority;

    /**
     * Policy applied when this bucket is exhausted.
     * Defaults to {@link com.auraboot.framework.billing.quota.model.OveragePolicy#HARD_LIMIT} when null.
     */
    OveragePolicy overagePolicy;

    /**
     * Caller-supplied idempotency key.
     * Repeated calls with the same key return the original {@link com.auraboot.framework.billing.quota.model.QuotaBucket}
     * without creating a new bucket or ledger entry.
     * Recommended format: {@code "provision:<subscription_id>:<resource_code>:<period_start_epoch>"}.
     * Max 128 chars.
     */
    String idempotencyKey;
}
