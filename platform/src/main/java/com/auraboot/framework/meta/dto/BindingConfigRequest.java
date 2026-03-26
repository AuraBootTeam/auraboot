package com.auraboot.framework.meta.dto;

import lombok.Data;

/**
 * Binding configuration request DTO
 * Used to configure field binding context in a model
 */
@Data
public class BindingConfigRequest {

    /**
     * Alias code (optional)
     * Context-specific field alias (e.g., "order_status" instead of "status")
     */
    private String aliasCode;

    /**
     * Required flag (optional)
     * Override field's required setting in this context
     */
    private Boolean required;

    /**
     * Nullable flag (optional)
     * Override field's nullable setting in this context
     */
    private Boolean nullable;

    /**
     * Readonly flag (optional)
     * Override field's readonly setting in this context
     */
    private Boolean readonly;

    /**
     * Visible flag (optional)
     * Override field's visible setting in this context
     */
    private Boolean visible;

    /**
     * Editable flag (optional)
     * Override field's editable setting in this context
     */
    private Boolean editable;

    /**
     * Default value (optional)
     * Override field's default value in this context
     */
    private String defaultValue;

    /**
     * Dictionary override code (optional)
     * Override field's dictionary binding in this context
     */
    private String dictOverrideCode;

    /**
     * UI hint (optional)
     * Context-specific UI hint (e.g., "Use dropdown for status selection")
     */
    private String uiHint;

    /**
     * Validation override (optional)
     * Override field's validation rules in this context (JSON format)
     */
    private String validationOverride;

    /**
     * Field order (optional)
     * Override field's display order in this context
     */
    private Integer fieldOrder;
}
