package com.auraboot.framework.meta.entity.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.Map;

/**
 * Validation rule configuration for field-level validation.
 *
 * @author AuraBoot Team
 * @since 2.6.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ValidationRuleBean {

    /**
     * Rule type: REQUIRED, MIN_LENGTH, MAX_LENGTH, PATTERN, MIN_VALUE, MAX_VALUE, ENUM, CUSTOM, etc.
     */
    private String type;

    /**
     * Validator name: NOT_EMPTY, LENGTH, PHONE, EMAIL, PATTERN, etc.
     */
    private String validator;

    /**
     * Rule value (interpretation depends on type)
     */
    private Object value;

    /**
     * Error message when validation fails
     */
    private String message;

    /**
     * Severity: ERROR, WARN
     */
    private String severity;

    /**
     * Whether this rule is enabled
     */
    private Boolean enabled;

    /**
     * Additional configuration parameters
     */
    private Map<String, Object> params;
}
