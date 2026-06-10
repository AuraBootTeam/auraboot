package com.auraboot.framework.billing.quota.controller;

import com.auraboot.framework.billing.quota.model.QuotaBucket;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Read-only balance summary for a single quota bucket — returned by
 * {@code GET /api/billing/quota/buckets}.
 *
 * <p>Exposes only the fields needed by monitoring/billing UIs.  The raw entity
 * ({@link QuotaBucket}) is not exposed to avoid leaking internal DB identifiers.
 */
@Value
@Builder
public class QuotaBucketBalanceDto {

    Long   bucketId;
    String bucketCode;
    String resourceCode;
    String unit;
    BigDecimal totalAmount;
    BigDecimal usedAmount;
    BigDecimal reservedAmount;
    BigDecimal availableAmount;
    String sourceType;
    Integer priority;
    String overagePolicy;
    String status;
    Instant periodStart;
    Instant periodEnd;

    public static QuotaBucketBalanceDto from(QuotaBucket b) {
        return QuotaBucketBalanceDto.builder()
                .bucketId(b.getId())
                .bucketCode(b.getBucketCode())
                .resourceCode(b.getResourceCode())
                .unit(b.getUnit())
                .totalAmount(b.getTotalAmount())
                .usedAmount(b.getUsedAmount())
                .reservedAmount(b.getReservedAmount())
                .availableAmount(b.availableAmount())
                .sourceType(b.getSourceType())
                .priority(b.getPriority())
                .overagePolicy(b.getOveragePolicy())
                .status(b.getStatus())
                .periodStart(b.getPeriodStart())
                .periodEnd(b.getPeriodEnd())
                .build();
    }
}
