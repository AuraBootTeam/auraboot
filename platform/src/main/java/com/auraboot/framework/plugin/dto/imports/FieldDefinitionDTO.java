package com.auraboot.framework.plugin.dto.imports;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * DTO for importing field definitions from plugin manifest.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FieldDefinitionDTO {

    /**
     * Field code (unique within tenant).
     * Required.
     */
    private String code;

    /**
     * Display name.
     */
    private String displayName;

    /**
     * Localized display names.
     */
    @JsonProperty("displayName:zh-CN")
    private String displayNameZhCN;

    @JsonProperty("displayName:en")
    private String displayNameEn;

    /**
     * Field description.
     */
    private String description;

    /**
     * Data type: STRING, INTEGER, DECIMAL, BOOLEAN, DATE, DATETIME,
     *            TEXT, JSON, REFERENCE, ENUM, etc.
     * Required.
     */
    private String dataType;

    /**
     * Reference target configuration (for REFERENCE type).
     * Contains: targetModel, targetField, relationship, etc.
     */
    private Map<String, Object> refTarget;

    /**
     * Legacy/simple reference model code used by many plugin field JSON files.
     * Imported into refTarget.targetEntity/modelCode when explicit refTarget is absent.
     */
    private String referenceModelCode;

    /**
     * Field features/flags.
     * Contains: searchable, sortable, indexable, etc.
     */
    private Map<String, Object> feature;

    /**
     * Constraints for the field.
     * Contains: required, maxLength, minLength, pattern, min, max, etc.
     */
    private FieldConstraints constraints;

    /**
     * Default value expression.
     */
    private String defaultValue;

    /**
     * Dictionary code for enum fields.
     */
    private String dictCode;

    /**
     * UI schema for rendering hints.
     */
    private Map<String, Object> uiSchema;

    /**
     * Query schema for search configuration.
     */
    private Map<String, Object> querySchema;

    /**
     * Validation rules schema.
     */
    private Map<String, Object> ruleSchema;

    /**
     * Extension properties.
     */
    private Map<String, Object> extension;

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
     * Validate field definition has required fields.
     */
    public boolean isValid() {
        return code != null && !code.isBlank()
                && dataType != null && !dataType.isBlank();
    }

    /**
     * Get effective display name.
     */
    public String getEffectiveDisplayName() {
        if (displayNameZhCN != null && !displayNameZhCN.isBlank()) {
            return displayNameZhCN;
        }
        if (displayNameEn != null && !displayNameEn.isBlank()) {
            return displayNameEn;
        }
        return displayName != null ? displayName : code;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FieldConstraints {
        private Boolean required;
        private Integer maxLength;
        private Integer minLength;
        private String pattern;
        private Number min;
        private Number max;
        private Integer precision;
        private Integer scale;
        private Boolean unique;
    }
}
