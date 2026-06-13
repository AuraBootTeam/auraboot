package com.auraboot.framework.billing.metering.spi;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of a {@link MeteringService#record} call.
 *
 * <p>Always non-null.  Check {@link #getStatus()} to determine the outcome:
 *
 * <ul>
 *   <li>{@link MeteringResultStatus#ACCEPTED}          — {@link #eventCode} is the new event's code
 *   <li>{@link MeteringResultStatus#DUPLICATE_IGNORED} — {@link #eventCode} is the existing event's code
 *   <li>{@link MeteringResultStatus#CONFLICT}          — {@link #eventCode} is the existing event's code;
 *       the conflicting payload was written to the conflict table
 *   <li>{@link MeteringResultStatus#REJECTED}          — {@link #eventCode} is null;
 *       {@link #reason} describes the validation failure
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MeteringResult {

    /** Outcome of the record attempt. Never null. */
    private MeteringResultStatus status;

    /**
     * Event code of the accepted (or already-existing) usage event.
     * Null only when {@link #status} is {@link MeteringResultStatus#REJECTED}.
     */
    private String eventCode;

    /**
     * Human-readable reason for a rejection or conflict.
     * Null when {@link #status} is {@link MeteringResultStatus#ACCEPTED}.
     */
    private String reason;

    // ── Factory helpers ───────────────────────────────────────────────────────

    public static MeteringResult accepted(String eventCode) {
        return MeteringResult.builder()
                .status(MeteringResultStatus.ACCEPTED)
                .eventCode(eventCode)
                .build();
    }

    public static MeteringResult duplicateIgnored(String existingEventCode) {
        return MeteringResult.builder()
                .status(MeteringResultStatus.DUPLICATE_IGNORED)
                .eventCode(existingEventCode)
                .reason("Duplicate submission — existing event returned")
                .build();
    }

    public static MeteringResult conflict(String existingEventCode, String reason) {
        return MeteringResult.builder()
                .status(MeteringResultStatus.CONFLICT)
                .eventCode(existingEventCode)
                .reason(reason)
                .build();
    }

    public static MeteringResult rejected(String reason) {
        return MeteringResult.builder()
                .status(MeteringResultStatus.REJECTED)
                .reason(reason)
                .build();
    }
}
