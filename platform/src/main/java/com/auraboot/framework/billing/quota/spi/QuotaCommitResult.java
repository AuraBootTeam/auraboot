package com.auraboot.framework.billing.quota.spi;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

/**
 * Result of {@link QuotaService#commit}.
 */
@Value
@Builder
public class QuotaCommitResult {

    /** The reservation code that was committed. */
    String reservationCode;

    /**
     * Actual quantity committed (equals the {@code actualQuantity} passed in,
     * capped to the original estimated amount on OSS base impl).
     */
    BigDecimal actualAmount;

    /**
     * Delta returned to buckets because {@code actualQuantity < estimatedQuantity}.
     * Zero when actual == estimated.
     */
    BigDecimal releasedDelta;

    /** Remaining available balance across ACTIVE buckets after commit. */
    BigDecimal remainingAfterCommit;
}
