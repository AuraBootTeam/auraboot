package com.auraboot.framework.meta.entity.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.Map;

/**
 * Layer 3 computed field override definition.
 * Applied on top of base fields (from entity binding or named query).
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ComputedFieldOverride {

    /**
     * Compute expression (SQL or SpEL)
     */
    private String expression;

    /**
     * Return type of the computed field
     */
    private String returnType;

    /**
     * Display label override
     */
    private String label;

    /**
     * Whether this is a virtual-only field (no physical column)
     */
    private Boolean virtual;

    /**
     * Field description override
     */
    private String description;

    /**
     * UI rendering hints
     */
    private Map<String, Object> uiHint;

    /**
     * Check if this is a virtual-only computed field
     */
    public boolean isVirtual() {
        return Boolean.TRUE.equals(virtual);
    }
}
