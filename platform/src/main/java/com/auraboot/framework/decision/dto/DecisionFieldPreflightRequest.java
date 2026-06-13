package com.auraboot.framework.decision.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request for checking whether a model/schema field change would break indexed decisions.
 */
@Data
public class DecisionFieldPreflightRequest {

    @NotBlank
    private String fieldRef;

    @NotBlank
    private String action;

    private String currentDataType;

    private String nextDataType;

    private Boolean impactAcknowledged;

    private String note;
}
