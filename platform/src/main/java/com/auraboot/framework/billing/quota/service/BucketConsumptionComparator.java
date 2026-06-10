package com.auraboot.framework.billing.quota.service;

import com.auraboot.framework.billing.quota.model.BucketSourceType;
import com.auraboot.framework.billing.quota.model.QuotaBucket;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;

/**
 * Determines the order in which quota buckets are consumed during an
 * {@code authorize} call.  Lower rank = consumed first.
 *
 * <h3>Sort key (most significant → least significant)</h3>
 * <ol>
 *   <li><b>Expiry preemption</b>: buckets with {@code period_end - now ≤ expiryPreemptDays}
 *       sort before non-expiring buckets.  Within the expiring group, the
 *       soonest-expiring bucket is consumed first (period_end ascending).</li>
 *   <li><b>Explicit priority</b>: {@code bucket.priority} ascending (lower = first).</li>
 *   <li><b>Source-type business order</b>: {@link BucketSourceType#consumptionOrder()}
 *       ascending — PROMOTION first, PREPAID_CREDIT last.  Buckets with an
 *       unrecognised sourceType string fall back to {@link Integer#MAX_VALUE}.</li>
 *   <li><b>Period end ascending</b>: among equal-priority same-source buckets, the
 *       one expiring sooner is drained first.</li>
 *   <li><b>Bucket id ascending</b>: stable tie-breaker.</li>
 * </ol>
 *
 * <p>This comparator is stateless and thread-safe.  The caller is responsible for
 * passing the correct {@code now} and {@code expiryPreemptDays} values (typically
 * from {@link BillingQuotaProperties}).
 */
final class BucketConsumptionComparator implements Comparator<QuotaBucket> {

    private final Instant now;
    private final int expiryPreemptDays;

    BucketConsumptionComparator(Instant now, int expiryPreemptDays) {
        this.now = now;
        this.expiryPreemptDays = expiryPreemptDays;
    }

    @Override
    public int compare(QuotaBucket a, QuotaBucket b) {
        // 1. Expiry-preemption group: expiring-soon buckets first (0 < 1)
        boolean aSoon = isExpiringSoon(a);
        boolean bSoon = isExpiringSoon(b);
        if (aSoon != bSoon) {
            return aSoon ? -1 : 1;  // expiring bucket ranks earlier
        }

        // 2. Within expiring group: soonest period_end first
        if (aSoon) {
            int cmp = a.getPeriodEnd().compareTo(b.getPeriodEnd());
            if (cmp != 0) return cmp;
        }

        // 3. Explicit bucket priority (lower = first)
        int priA = a.getPriority() != null ? a.getPriority() : Integer.MAX_VALUE;
        int priB = b.getPriority() != null ? b.getPriority() : Integer.MAX_VALUE;
        int priCmp = Integer.compare(priA, priB);
        if (priCmp != 0) return priCmp;

        // 4. Source-type business order
        int srcA = sourceOrder(a.getSourceType());
        int srcB = sourceOrder(b.getSourceType());
        int srcCmp = Integer.compare(srcA, srcB);
        if (srcCmp != 0) return srcCmp;

        // 5. Period end ascending (earlier expiry first — also applies to non-expiring)
        int periodCmp = a.getPeriodEnd().compareTo(b.getPeriodEnd());
        if (periodCmp != 0) return periodCmp;

        // 6. Stable tie-breaker: bucket id ascending
        return Long.compare(
                a.getId() != null ? a.getId() : Long.MAX_VALUE,
                b.getId() != null ? b.getId() : Long.MAX_VALUE);
    }

    private boolean isExpiringSoon(QuotaBucket bucket) {
        if (bucket.getPeriodEnd() == null) return false;
        long daysUntilExpiry = ChronoUnit.DAYS.between(now, bucket.getPeriodEnd());
        return daysUntilExpiry <= expiryPreemptDays;
    }

    /**
     * Maps a source-type string to its consumption order integer.
     * Unrecognised values (null, empty, unknown) are treated as lowest priority
     * (consumed last) to avoid accidentally draining unexpected bucket types.
     */
    private static int sourceOrder(String sourceType) {
        if (sourceType == null || sourceType.isBlank()) {
            return Integer.MAX_VALUE;
        }
        try {
            return BucketSourceType.valueOf(sourceType).consumptionOrder();
        } catch (IllegalArgumentException e) {
            return Integer.MAX_VALUE;
        }
    }
}
