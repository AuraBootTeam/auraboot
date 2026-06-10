package com.auraboot.framework.eventpolicy.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Request body for enabling or disabling an EventPolicy definition.
 */
@Data
public class EventPolicyDefinitionEnabledRequest {

    @NotNull
    private Boolean enabled;
}
