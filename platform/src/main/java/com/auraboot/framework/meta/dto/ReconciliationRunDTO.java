package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * DTO for reconciliation run responses.
 */
@Data
public class ReconciliationRunDTO {

    private Long id;
    private String runCode;
    private Long profileId;
    private String profileCode;
    private String profileName;
    private String status;
    private LocalDate periodStart;
    private LocalDate periodEnd;

    // Statistics
    private Integer totalSourceA;
    private Integer totalSourceB;
    private Integer matchedCount;
    private Integer unmatchedACount;
    private Integer unmatchedBCount;
    private Integer discrepancyCount;
    private BigDecimal matchedAmount;
    private BigDecimal unmatchedAAmount;
    private BigDecimal unmatchedBAmount;

    private String errorMessage;
    private Instant startedAt;
    private Instant completedAt;
    private Long createdBy;
    private Instant createdAt;

    /** Derived: match rate = matchedCount / max(totalSourceA, totalSourceB) */
    public BigDecimal getMatchRate() {
        int total = Math.max(
                totalSourceA != null ? totalSourceA : 0,
                totalSourceB != null ? totalSourceB : 0);
        if (total == 0) return BigDecimal.ZERO;
        int matched = matchedCount != null ? matchedCount : 0;
        return BigDecimal.valueOf(matched * 100.0 / total).setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
