package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.dto.BindingConfigRequest;
import com.auraboot.framework.meta.dto.BindingConfiguration;
import com.auraboot.framework.meta.dto.MetaFieldDTO;

import java.util.Optional;

/**
 * Field binding context service interface
 * Manages enhanced binding context configuration for field-model bindings
 * 
 * @author AuraBoot Team
 * @since 2.2.0
 */
public interface FieldBindingContextService {

    /**
     * Configure field binding context with enhanced properties
     * Creates or updates binding with context-specific configuration
     * 
     * @param modelPid Model PID
     * @param fieldPid Field PID
     * @param request Binding configuration request
     * @return Configured binding
     */
    BindingConfiguration configureBinding(String modelPid, String fieldPid, BindingConfigRequest request);

    /**
     * Get binding configuration with context
     * Returns complete binding configuration including context properties
     * 
     * @param modelPid Model PID
     * @param fieldPid Field PID
     * @return Binding configuration
     */
    Optional<BindingConfiguration> getBindingConfiguration(String modelPid, String fieldPid);

    /**
     * Update binding configuration (context properties only)
     * Updates only the context-specific properties without affecting base binding
     * 
     * @param bindingId Binding ID
     * @param request Binding configuration request
     * @return Updated binding configuration
     */
    BindingConfiguration updateBindingConfiguration(Long bindingId, BindingConfigRequest request);

    /**
     * Validate binding configuration
     * Ensures configuration is valid and overrides are more restrictive
     * 
     * @param request Binding configuration request
     * @param field Field definition
     * @return Validation result
     */
    FieldValidationService.ValidationResult validateBindingConfiguration(
        BindingConfigRequest request, MetaFieldDTO field);

    /**
     * Get default binding configuration
     * Returns sensible defaults for a field binding
     * 
     * @param field Field definition
     * @return Default binding configuration
     */
    BindingConfiguration getDefaultBindingConfiguration(MetaFieldDTO field);
}
