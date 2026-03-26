package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * DTO for reconciliation run report.
 */
@Data
public class ReconciliationReportDTO {

    private String runCode;
    private String profileCode;
    private String profileName;
    private String profileType;
    private String status;
    private LocalDate periodStart;
    private LocalDate periodEnd;

    // Summary statistics
    private Integer totalSourceA;
    private Integer totalSourceB;
    private Integer matchedCount;
    private Integer unmatchedACount;
    private Integer unmatchedBCount;
    private Integer discrepancyCount;
    private BigDecimal matchedAmount;
    private BigDecimal unmatchedAAmount;
    private BigDecimal unmatchedBAmount;
    private BigDecimal matchRate;

    // Resolution breakdown
    private Integer resolvedCount;
    private Integer pendingCount;
    private Integer approvedCount;
    private Integer adjustedCount;
    private Integer writtenOffCount;

    // Top discrepancies
    private List<ReconciliationItemDTO> topDiscrepancies;
}
