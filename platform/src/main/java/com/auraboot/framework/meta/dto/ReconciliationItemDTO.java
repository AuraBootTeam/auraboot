package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * DTO for reconciliation item responses.
 */
@Data
public class ReconciliationItemDTO {

    private Long id;
    private Long runId;
    private String matchStatus;

    // Source A
    private Long sourceARecordId;
    private String sourceARef;
    private BigDecimal sourceAAmount;
    private LocalDate sourceADate;

    // Source B
    private Long sourceBRecordId;
    private String sourceBRef;
    private BigDecimal sourceBAmount;
    private LocalDate sourceBDate;

    // Matching info
    private BigDecimal amountDifference;
    private Integer dateDifference;
    private BigDecimal matchScore;

    // Resolution
    private String resolution;
    private String resolutionNotes;
    private Long resolvedBy;
    private Instant resolvedAt;
}
