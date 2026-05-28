package com.auraboot.framework.semantic.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class AccessPolicyDTO {

    @JsonProperty("access_grant")
    private String accessGrant;

    @JsonProperty("user_attribute")
    private String userAttribute;

    @JsonProperty("allowed_values")
    private List<String> allowedValues;

    @JsonProperty("target_dimensions")
    private List<String> targetDimensions;

    /**
     * SQL WHERE fragment with {@code {user.<attr>}} placeholders only.
     * SemanticYamlValidator enforces denylist for {@code ;}, {@code --}, {@code UNION}, {@code DROP}, etc.
     */
    @JsonProperty("sql_filter")
    private String sqlFilter;
}
