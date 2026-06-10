package com.auraboot.framework.billing.quota.spi;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

/**
 * Result of a quota authorization request ({@link QuotaService#authorize}).
 */
@Value
@Builder
public class QuotaDecision {

    /**
     * Whether the request is allowed.
     * ALLOW = reservation created; DENY = insufficient quota (hard limit).
     */
    Outcome outcome;

    /**
     * Reservation code to pass back to {@link QuotaService#commit} or
     * {@link QuotaService#release}.  Non-null only when {@code outcome == ALLOW}.
     */
    String reservationCode;

    /**
     * Remaining available balance across all ACTIVE buckets for this resource
     * after this reservation was created.  Non-null only when {@code outcome == ALLOW}.
     */
    BigDecimal remainingAfterReserve;

    /**
     * Human-readable denial reason (e.g. "INSUFFICIENT_QUOTA", "RESOURCE_NOT_REGISTERED").
     * Non-null only when {@code outcome == DENY}.
     */
    String denyReason;

    // ─────────────────────────────────────────────────────────────────────────

    public enum Outcome {
        ALLOW,
        DENY
    }

    // Factory helpers ─────────────────────────────────────────────────────────

    public static QuotaDecision allow(String reservationCode, BigDecimal remainingAfterReserve) {
        return QuotaDecision.builder()
                .outcome(Outcome.ALLOW)
                .reservationCode(reservationCode)
                .remainingAfterReserve(remainingAfterReserve)
                .build();
    }

    public static QuotaDecision deny(String reason) {
        return QuotaDecision.builder()
                .outcome(Outcome.DENY)
                .denyReason(reason)
                .build();
    }

    public boolean isAllowed() {
        return Outcome.ALLOW == outcome;
    }
}
