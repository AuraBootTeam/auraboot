package com.auraboot.framework.meta.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Request DTO for creating/updating a reconciliation profile.
 */
@Data
public class ReconciliationProfileRequest {

    @NotBlank(message = "Profile code is required")
    private String profileCode;

    @NotBlank(message = "Profile name is required")
    private String profileName;

    @NotBlank(message = "Profile type is required")
    private String profileType;

    private String description;

    // Source A
    @NotBlank(message = "Source A model is required")
    private String sourceAModel;

    @NotBlank(message = "Source A amount field is required")
    private String sourceAAmountField;

    private String sourceADateField;
    private String sourceARefField;

    // Source B
    @NotBlank(message = "Source B model is required")
    private String sourceBModel;

    @NotBlank(message = "Source B amount field is required")
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
}
