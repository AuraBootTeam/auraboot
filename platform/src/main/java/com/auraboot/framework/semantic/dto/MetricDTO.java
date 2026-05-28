package com.auraboot.framework.semantic.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Metric DTO covering all 5 metric types. {@code typeParams} shape varies by
 * {@code type}; structural validation is enforced by JSON Schema, and additional
 * cross-field rules are enforced by {@code SemanticValidator}.
 */
@Data
@NoArgsConstructor
public class MetricDTO {
    private String code;
    private Map<String, String> label;
    private String description;

    /** simple / ratio / cumulative / derived / conversion */
    private String type;

    @JsonProperty("type_params")
    private Map<String, Object> typeParams;

    private String filter;

    @JsonProperty("required_permissions")
    private List<String> requiredPermissions;
}
