package com.auraboot.framework.meta.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;

/**
 * Field binding request DTO
 * Used when binding an existing field to a model
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FieldBindingRequest {

    /**
     * Field PID to bind
     */
    @NotBlank(message = "Field PID is required")
    private String fieldPid;

    /**
     * Alias code for context-specific naming
     */
    private String aliasCode;

    /**
     * Required flag
     */
    private Boolean required;

    /**
     * Nullable flag
     */
    private Boolean nullable;

    /**
     * Readonly flag
     */
    private Boolean readonly;

    /**
     * Visible flag
     */
    private Boolean visible;

    /**
     * Editable flag
     */
    private Boolean editable;

    /**
     * Default value
     */
    private String defaultValue;

    /**
     * Dictionary override code
     */
    private String dictOverrideCode;

    /**
     * UI hint
     */
    private String uiHint;

    /**
     * Validation override (JSON format)
     */
    private String validationOverride;

    /**
     * Display configuration (JSON format)
     */
    private String displayConfig;

    /**
     * Remarks
     */
    private String remarks;
}
