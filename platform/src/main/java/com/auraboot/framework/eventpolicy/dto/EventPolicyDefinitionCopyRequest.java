package com.auraboot.framework.eventpolicy.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request body for copying an EventPolicy definition.
 */
@Data
public class EventPolicyDefinitionCopyRequest {

    @NotBlank
    private String policyCode;

    private String policyName;
}
