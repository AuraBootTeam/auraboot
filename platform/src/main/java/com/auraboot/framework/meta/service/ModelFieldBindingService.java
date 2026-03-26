package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.dto.BatchFieldBindingRequest;
import com.auraboot.framework.meta.dto.FieldBindingRequest;
import com.auraboot.framework.meta.dto.MetaFieldDTO;
import com.auraboot.framework.meta.dto.MetaModelFieldBindingDTO;

import java.util.List;

/**
 * Model-Field Binding Service
 * 
 * Manages the binding relationships between models and fields.
 * This service resolves the circular dependency between MetaFieldService and MetaModelService.
 * 
 * @author AuraBoot Team
 * @since 3.0.0
 */
public interface ModelFieldBindingService {
    
    /**
     * Bind a field to a model
     * 
     * @param modelPid Model PID
     * @param fieldPid Field PID
     * @param displayOrder Display order (optional)
     * @param isRequired Whether the field is required
     * @param isReadonly Whether the field is readonly
     * @param isVisible Whether the field is visible
     * @return Binding DTO
     */
    MetaModelFieldBindingDTO bindFieldToModel(
        String modelPid, 
        String fieldPid,
        Integer displayOrder,
        Boolean isRequired,
        Boolean isReadonly,
        Boolean isVisible
    );
    
    /**
     * Unbind a field from a model
     * 
     * @param modelPid Model PID
     * @param fieldPid Field PID
     * @return true if unbind successful, false if binding not found
     */
    boolean unbindFieldFromModel(String modelPid, String fieldPid);
    
    /**
     * Get all fields bound to a model
     * 
     * @param modelPid Model PID
     * @return List of field DTOs with binding information
     */
    List<MetaFieldDTO> getModelFields(String modelPid);
    
    /**
     * Get all bindings for a model
     * 
     * @param modelPid Model PID
     * @return List of binding DTOs
     */
    List<MetaModelFieldBindingDTO> getModelBindings(String modelPid);
    
    /**
     * Reorder fields in a model
     * 
     * @param modelPid Model PID
     * @param fieldOrders Map of field PID to new display order
     * @return Number of bindings updated
     */
    int reorderFields(String modelPid, java.util.Map<String, Integer> fieldOrders);
    
    /**
     * Update field binding configuration
     * 
     * @param modelPid Model PID
     * @param fieldPid Field PID
     * @param isRequired Whether the field is required
     * @param isReadonly Whether the field is readonly
     * @param isVisible Whether the field is visible
     * @return Updated binding DTO
     */
    MetaModelFieldBindingDTO updateFieldConfig(
        String modelPid,
        String fieldPid,
        Boolean isRequired,
        Boolean isReadonly,
        Boolean isVisible
    );
    
    /**
     * Batch bind fields to a model
     * 
     * @param modelPid Model PID
     * @param fieldPids List of field PIDs
     * @return Number of fields bound
     */
    int batchBindFields(String modelPid, List<String> fieldPids);
    
    /**
     * Bind a field to a model with full configuration
     * 
     * This method supports complete binding configuration including:
     * - Alias code for context-specific naming
     * - Required, nullable, readonly, visible, editable flags
     * - Default value
     * - Dictionary override
     * - UI hint and validation override
     * - Display configuration
     * - Remarks
     * 
     * @param modelPid Model PID
     * @param request Field binding request with full configuration
     * @return Created binding DTO
     */
    MetaModelFieldBindingDTO bindFieldWithConfig(String modelPid, FieldBindingRequest request);
    
    /**
     * Batch bind multiple fields to a model with common configuration
     * 
     * This method allows binding multiple fields at once with common configuration
     * applied to all bindings.
     * 
     * @param modelPid Model PID
     * @param request Batch binding request with field PIDs and common configuration
     * @return List of created binding DTOs
     */
    List<MetaModelFieldBindingDTO> batchBindFieldsWithConfig(
            String modelPid, 
            BatchFieldBindingRequest request);
}
