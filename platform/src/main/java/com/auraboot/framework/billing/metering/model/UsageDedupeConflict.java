package com.auraboot.framework.billing.metering.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Conflict log entry for a usage event with duplicate key but different payload.
 *
 * <p>Backed by {@code ab_billing_usage_dedupe_conflict}.
 *
 * <p>This table is append-only.  A row is written when a caller submits a usage
 * event with a {@code (source_service, idempotency_key)} that already exists in
 * {@code ab_billing_usage_event} but with a different payload (quantity, resource_code,
 * or occurred_at differ from the original).  The conflicting event is NOT charged —
 * it is preserved here for human investigation and potential dispute resolution.
 *
 * <p>P2-9: conflicting events must be queryable without polluting the billing ledger.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("ab_billing_usage_dedupe_conflict")
public class UsageDedupeConflict {

    /** Snowflake ID — primary key. */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * The source service whose event caused the conflict.
     * Matches {@link UsageEvent#getSourceService()}.
     */
    private String sourceService;

    /**
     * The idempotency key that already exists in {@code ab_billing_usage_event}.
     */
    private String idempotencyKey;

    /**
     * {@link UsageEvent#getEventCode()} of the original (already-accepted) event.
     * Used for cross-reference in investigations.
     */
    private String existingEventCode;

    /**
     * Full JSON-serialized {@code UsageEventRequest} payload of the rejected event.
     * Preserved verbatim for investigation without modification.
     */
    private String conflictingPayloadJson;

    /** When this conflict was detected (set by server). */
    private Instant detectedAt;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
}
