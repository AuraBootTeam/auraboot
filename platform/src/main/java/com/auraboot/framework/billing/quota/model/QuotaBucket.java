package com.auraboot.framework.billing.quota.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Individual quota bucket — tracks available, used, and reserved amounts.
 *
 * <p>Backed by {@code ab_billing_quota_bucket}.  All monetary/count fields use
 * {@link BigDecimal} with scale 6 to prevent floating-point drift.
 *
 * <p>Optimistic locking via {@code @Version} on {@link #version}: MyBatis-Plus
 * automatically appends {@code AND version = ?} on updates and increments the
 * version counter.  Concurrent updates receive an
 * {@code OptimisticLockerInterceptor} exception and should retry.
 *
 * <p>Available balance = {@code totalAmount - usedAmount - reservedAmount}.
 * DB CHECK constraints enforce all three fields are non-negative.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("ab_billing_quota_bucket")
public class QuotaBucket {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** Stable external identifier for this bucket. */
    private String bucketCode;

    /** Parent pool. */
    private Long poolId;

    /** Account that owns this bucket. */
    private Long accountId;

    /** Per-user scoping; NULL for account-level buckets. */
    private Long userId;

    /** Linked subscription. */
    private Long subscriptionId;

    /** Resource type — must be registered in {@code ab_billing_resource_catalog}. */
    private String resourceCode;

    /** Optional sub-classification of the resource (e.g. model variant). */
    private String resourceSubtype;

    /** Total quota provisioned in this bucket. */
    private BigDecimal totalAmount;

    /** Amount actually consumed (committed). */
    private BigDecimal usedAmount;

    /** Amount pre-authorized but not yet committed. */
    private BigDecimal reservedAmount;

    /** Unit of measure (must match resource catalog). */
    private String unit;

    /** Start of the valid period for this bucket. */
    private Instant periodStart;

    /** End of the valid period for this bucket (inclusive). */
    private Instant periodEnd;

    /** How this bucket was provisioned. */
    private String sourceType;

    /** Optional reference to the provisioning event (plan, add-on, etc.). */
    private Long sourceId;

    /**
     * FIFO consumption priority.  Lower value = consumed first.
     * Default 100; ROLLOVER buckets typically get priority 10.
     */
    private Integer priority;

    /** Policy enforced when this bucket is exhausted. */
    private String overagePolicy;

    /** Lifecycle status. */
    private String status;

    /**
     * Optimistic lock version — incremented manually on every CAS update.
     * Platform-wide {@code OptimisticLockerInnerInterceptor} is not registered,
     * so we use manual CAS via {@code QuotaBucketMapper.casUpdateById} with explicit
     * {@code WHERE id=? AND version=?}.
     */
    private Long version;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;

    // ─────────────────────────────────────────────────────────────────────────
    // Derived helpers (not persisted)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Available balance = total - used - reserved.
     * Not persisted — derived at runtime.
     */
    public BigDecimal availableAmount() {
        return totalAmount
                .subtract(usedAmount)
                .subtract(reservedAmount);
    }
}
