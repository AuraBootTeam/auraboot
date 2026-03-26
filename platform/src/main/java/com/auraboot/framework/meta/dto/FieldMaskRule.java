package com.auraboot.framework.meta.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Defines how a field should be masked for a specific user.
 *
 * @since 5.1.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FieldMaskRule {

    private String fieldCode;

    /**
     * HIDE - completely hide the field value (null).
     * PARTIAL - show first/last chars with mask (e.g. 138****1234).
     * HASH - replace with hash value.
     * CUSTOM - apply custom expression.
     */
    private String maskType;

    /**
     * Custom masking expression (SpEL).
     */
    private String maskExpression;
}
