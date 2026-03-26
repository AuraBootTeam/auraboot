package com.auraboot.framework.plugin.dto.imports;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * DTO for binding fields to models in plugin manifest.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelFieldBindingDTO {

    /**
     * Model code to bind the field to.
     * Required.
     */
    private String modelCode;

    /**
     * Field code to bind.
     * Required.
     */
    private String fieldCode;

    /**
     * Display sequence/order.
     */
    @Builder.Default
    private Integer sequence = 0;

    /**
     * Whether the field is required in this model context.
     */
    @Builder.Default
    private Boolean required = false;

    /**
     * Whether the field is visible in UI.
     */
    @Builder.Default
    private Boolean visible = true;

    /**
     * Whether the field is editable.
     */
    @Builder.Default
    private Boolean editable = true;

    /**
     * Default value override for this binding.
     */
    private String defaultValue;

    /**
     * Alias code for this field in the model context.
     */
    private String aliasCode;

    /**
     * Dictionary override code.
     */
    private String dictOverrideCode;

    /**
     * UI hint override.
     */
    private String uiHint;

    /**
     * Validation rules override.
     */
    private String validationOverride;

    /**
     * Whether this is a system binding (cannot be unbound).
     */
    @Builder.Default
    private Boolean isSystemBinding = false;

    /**
     * Display configuration override.
     */
    private Map<String, Object> displayConfig;

    /**
     * Remarks/notes for this binding.
     */
    private String remarks;

    @JsonIgnore
    private Map<String, Object> unknownFields;

    @JsonAnySetter
    public void setUnknownField(String key, Object value) {
        if (unknownFields == null) {
            unknownFields = new HashMap<>();
        }
        unknownFields.put(key, value);
    }

    /**
     * Validate binding has required fields.
     */
    public boolean isValid() {
        return modelCode != null && !modelCode.isBlank()
                && fieldCode != null && !fieldCode.isBlank();
    }
}
