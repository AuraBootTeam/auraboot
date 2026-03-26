package com.auraboot.framework.meta.entity.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * ViewModel configuration payload.
 * Parsed from Model.extension.viewModel JSON.
 *
 * Supports three modes:
 * - inherit: extends a base entity's fields
 * - compose: builds from a named query's field set
 * - free: fully custom field composition via named query
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ViewModelConfig {

    /**
     * Resolution mode: inherit | compose | free
     */
    private String mode;

    /**
     * Base entity code (used in inherit mode)
     */
    private String baseEntityCode;

    /**
     * Fields to exclude from base entity (inherit mode)
     */
    private List<String> excludeFields;

    /**
     * Named query code (used in compose/free mode)
     */
    private String namedQueryCode;

    /**
     * Layer 3 computed field overrides keyed by field code
     */
    private Map<String, ComputedFieldOverride> computedFields;

    /**
     * Check if mode is inherit
     */
    public boolean isInheritMode() {
        return "inherit".equalsIgnoreCase(mode);
    }

    /**
     * Check if mode is compose
     */
    public boolean isComposeMode() {
        return "compose".equalsIgnoreCase(mode);
    }

    /**
     * Check if mode is free
     */
    public boolean isFreeMode() {
        return "free".equalsIgnoreCase(mode);
    }

    /**
     * Check if configuration is valid
     */
    public boolean isValid() {
        if (mode == null) return false;
        if (isInheritMode()) return baseEntityCode != null && !baseEntityCode.isEmpty();
        if (isComposeMode() || isFreeMode()) return namedQueryCode != null && !namedQueryCode.isEmpty();
        return false;
    }
}
