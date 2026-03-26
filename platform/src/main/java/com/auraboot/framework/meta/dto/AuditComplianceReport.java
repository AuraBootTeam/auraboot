package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

/**
 * Compliance report summarizing audit trail activity within a time window.
 *
 * @since 6.1.0
 */
@Data
@Builder
public class AuditComplianceReport {

    private Long tenantId;
    private Instant startTime;
    private Instant endTime;

    /** Total audit records in the period */
    private long totalRecords;

    /** Breakdown by event type (e.g., COMMAND_EXECUTED -> 150) */
    private Map<String, Long> recordsByEventType;

    /** Breakdown by operation type (e.g., CREATE -> 80) */
    private Map<String, Long> recordsByOperationType;

    /** Number of unique actors */
    private long uniqueActors;

    /** Number of unique entities modified */
    private long uniqueEntities;

    /** Chain integrity status for the period */
    private AuditChainVerificationResult chainVerification;

    /** Report generation timestamp */
    private Instant generatedAt;
}
