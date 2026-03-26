package com.auraboot.framework.consistency.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request DTO for creating/updating a consistency rule.
 */
@Data
public class ConsistencyRuleRequest {

    @NotBlank(message = "Rule code is required")
    @Size(max = 128, message = "Rule code must not exceed 128 characters")
    private String code;

    @Size(max = 256, message = "Rule name must not exceed 256 characters")
    private String name;

    private String ruleType = "cross_document";

    private String severity = "error";

    @NotBlank(message = "Source model is required")
    private String sourceModel;

    @NotBlank(message = "Source field is required")
    private String sourceField;

    @NotBlank(message = "Target model is required")
    private String targetModel;

    @NotBlank(message = "Target field is required")
    private String targetField;

    @NotBlank(message = "Link field is required")
    private String linkField;

    private String aggregation = "sum";

    private String operator = "LE";

    private String messageTemplate;

    private Boolean enabled = true;
}
