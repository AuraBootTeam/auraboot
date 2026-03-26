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
 * DTO for importing binding rules from plugin manifest.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BindingRuleDTO {

    /**
     * Command code this rule belongs to.
     * Can be null if embedded in CommandDefinitionDTO.
     */
    private String commandCode;

    /**
     * Rule type: FIELD_MAPPING, EXPRESSION, HANDLER, EVENT, VALIDATION, etc.
     * Required.
     */
    private String ruleType;

    /**
     * Expression for EXPRESSION rule type.
     */
    private String expression;

    /**
     * Target model for the binding.
     */
    private String targetModel;

    /**
     * Target field for field mapping.
     */
    private String targetField;

    /**
     * Source field for field mapping.
     */
    private String sourceField;

    /**
     * Handler class for HANDLER rule type.
     */
    private String handlerClass;

    /**
     * Event type for EVENT rule type.
     */
    private String eventType;

    /**
     * Rule configuration.
     */
    private Map<String, Object> config;

    /**
     * Execution sequence/order.
     */
    @Builder.Default
    private Integer sequence = 0;

    /**
     * Whether the rule is enabled.
     */
    @Builder.Default
    private Boolean enabled = true;

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
     * Validate binding rule has required fields.
     */
    public boolean isValid() {
        return ruleType != null && !ruleType.isBlank();
    }
}
