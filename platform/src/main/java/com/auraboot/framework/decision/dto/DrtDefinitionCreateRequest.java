package com.auraboot.framework.decision.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request body for creating a new decision definition.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Data
public class DrtDefinitionCreateRequest {

    @NotBlank
    @Size(max = 100)
    private String decisionCode;

    @NotBlank
    @Size(max = 200)
    private String decisionName;

    private String description;
    private String scopeType;
    private String scopeRef;
    private String ownerModule;
    private Boolean enabled;
}
