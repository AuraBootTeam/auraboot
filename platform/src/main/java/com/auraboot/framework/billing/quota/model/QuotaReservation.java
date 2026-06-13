package com.auraboot.framework.billing.quota.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Pre-authorization reservation — holds estimated quota until committed or released.
 *
 * <p>Backed by {@code ab_billing_quota_reservation}.
 *
 * <p>Idempotency: the DB UNIQUE constraint on {@code (account_id, idempotency_key)}
 * ensures that duplicate authorize calls with the same key return the existing
 * reservation without double-deducting.
 *
 * <p>Per-bucket detail is stored in {@link QuotaReservationLine} rows (P1-8).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("ab_billing_quota_reservation")
public class QuotaReservation {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** Stable code returned to the caller for commit/release. */
    private String reservationCode;

    /** Account that holds this reservation. */
    private Long accountId;

    /** Linked subscription. */
    private Long subscriptionId;

    /** Resource type being reserved. */
    private String resourceCode;

    /** Estimated quantity requested by the caller. */
    private BigDecimal estimatedAmount;

    /**
     * Actual quantity consumed — populated on commit.
     * NULL while the reservation is ACTIVE.
     */
    private BigDecimal actualAmount;

    /** Unit of measure (must match resource catalog). */
    private String unit;

    /** Lifecycle status. */
    private String status;

    /**
     * Caller-supplied idempotency key.
     * Duplicates with the same {@code (accountId, idempotencyKey)} return this row.
     */
    private String idempotencyKey;

    /** When this reservation auto-expires if not committed or released. */
    private Instant expiresAt;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
