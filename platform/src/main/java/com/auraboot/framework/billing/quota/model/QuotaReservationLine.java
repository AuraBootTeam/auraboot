package com.auraboot.framework.billing.quota.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Per-bucket breakdown of a quota reservation (P1-8 multi-bucket fix).
 *
 * <p>Backed by {@code ab_billing_quota_reservation_line}.
 *
 * <p>When a reservation spans multiple buckets (FIFO by priority), one line is
 * written per bucket.  On commit or release, each line is processed independently
 * so bucket-level accounting remains accurate.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("ab_billing_quota_reservation_line")
public class QuotaReservationLine {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** Parent reservation. */
    private Long reservationId;

    /** Bucket from which this slice of the reservation is drawn. */
    private Long bucketId;

    /** Amount reserved from this specific bucket. */
    private BigDecimal amount;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
}
