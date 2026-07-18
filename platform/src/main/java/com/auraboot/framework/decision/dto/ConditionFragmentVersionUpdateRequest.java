package com.auraboot.framework.decision.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request body for updating an editable condition-fragment draft version.
 */
@Data
public class ConditionFragmentVersionUpdateRequest {

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
