package com.auraboot.framework.meta.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Binding configuration DTO
 * Represents complete field binding configuration in a model context
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BindingConfiguration {

    /**
     * Binding ID
     */
    private Long bindingId;

    /**
     * Model PID
     */
    private String modelPid;

    /**
     * Model code
     */
    private String modelCode;

    /**
     * Field PID
     */
    private String fieldPid;

    /**
     * Field code
     */
    private String fieldCode;

    /**
     * Alias code
     * Context-specific field alias
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
     * Validation rules (base rules from field)
     */
    private String validationRules;

    /**
     * Display configuration
     */
    private String displayConfig;

    /**
     * Field order
     */
    private Integer fieldOrder;

    /**
     * Remarks
     */
    private String remarks;

    /**
     * Created at
     */
    private LocalDateTime createdAt;

    /**
     * Updated at
     */
    private LocalDateTime updatedAt;
}
