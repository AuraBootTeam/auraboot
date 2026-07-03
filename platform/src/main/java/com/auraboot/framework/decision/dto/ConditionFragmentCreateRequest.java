package com.auraboot.framework.decision.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request body for creating a reusable condition fragment.
 */
@Data
public class ConditionFragmentCreateRequest {

    @NotBlank
    @Size(max = 100)
    private String fragmentCode;

    @NotBlank
    @Size(max = 200)
    private String fragmentName;

    private String description;
    private String scopeType;
    private String scopeRef;
    private String ownerModule;
    private Boolean enabled;

    @NotNull
    private JsonNode conditionSpec;
}
