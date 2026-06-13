package com.auraboot.framework.billing.quota.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable double-entry ledger for quota operations.
 *
 * <p>Backed by {@code ab_billing_quota_ledger}.  Every state change to a bucket
 * (reserve, commit, release, grant, etc.) writes an append-only ledger entry.
 * Rows are never updated or deleted — they constitute the audit trail.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("ab_billing_quota_ledger")
public class QuotaLedger {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** Stable unique code for this ledger entry. */
    private String ledgerCode;

    /** Bucket affected by this operation. */
    private Long bucketId;

    /** Linked reservation (NULL for non-reservation operations). */
    private Long reservationId;

    /** Account that owns the bucket. */
    private Long accountId;

    /** Linked subscription. */
    private Long subscriptionId;

    /** The operation performed. */
    private String operationType;

    /**
     * Signed delta applied to the relevant bucket counter.
     * Positive = increase; negative = decrease.
     */
    private BigDecimal amount;

    /**
     * Available balance snapshot after this operation.
     * {@code total - used - reserved} as of the write moment.
     */
    private BigDecimal balanceAfter;

    /** Idempotency key propagated from the originating authorize/commit call. */
    private String idempotencyKey;

    /** Optional link to a usage event (metering side). */
    private Long relatedUsageEventId;

    /** Optional business reason code (e.g. "PERIOD_RESET", "PROMO_GRANT"). */
    private String reasonCode;

    /** User or system actor that triggered the operation. */
    private Long operatorId;

    /** Business timestamp of when the operation logically occurred. */
    private Instant occurredAt;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
}
