package com.auraboot.framework.meta.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * DTO for reconciliation profile responses.
 */
@Data
public class ReconciliationProfileDTO {

    private Long id;
    private String profileCode;
    private String profileName;
    private String profileType;
    private String description;

    // Source A
    private String sourceAModel;
    private String sourceAAmountField;
    private String sourceADateField;
    private String sourceARefField;

    // Source B
    private String sourceBModel;
    private String sourceBAmountField;
    private String sourceBDateField;
    private String sourceBRefField;

    // Matching rules
    private BigDecimal amountTolerance;
    private Integer dateToleranceDays;
    private Boolean matchByReference;
    private Boolean matchByAmount;
    private Boolean matchByDate;

    private Boolean enabled;
    private Instant createdAt;
    private Instant updatedAt;
}
