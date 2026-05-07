package com.auraboot.framework.meta.controller.config;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.dto.BatchFieldBindingRequest;
import com.auraboot.framework.meta.dto.FieldBindingRequest;
import com.auraboot.framework.meta.dto.MetaFieldDTO;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.dto.MetaModelFieldBindingDTO;
import com.auraboot.framework.meta.service.MetaFieldService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.ModelFieldBindingService;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.plugin.dto.imports.ResourceType;
import com.auraboot.framework.plugin.service.PluginResourceTracker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * Model-Field Binding Controller
 *
 * Provides REST API endpoints for managing model-field bindings.
 *
 * @author AuraBoot Team
 * @since 3.0.0
 */
@Slf4j
@RestController
@RequestMapping("/api/meta/models")
@RequiredArgsConstructor
public class ModelFieldBindingController {
    
    private final ModelFieldBindingService bindingService;
    private final MetaModelService metaModelService;
    private final MetaFieldService metaFieldService;
    private final PluginResourceTracker pluginResourceTracker;
    
    /**
     * Bind a field to a model
     * 
     * POST /api/meta/models/{modelPid}/fields/{fieldPid}
     */
    @PostMapping("/{modelPid}/fields/{fieldPid}")
    @RequirePermission(MetaPermission.MODEL_MANAGE)
    public ApiResponse<MetaModelFieldBindingDTO> bindField(
            @PathVariable String modelPid,
            @PathVariable String fieldPid,
            @RequestParam(required = false) Integer displayOrder,
            @RequestParam(required = false, defaultValue = "false") Boolean isRequired,
            @RequestParam(required = false, defaultValue = "false") Boolean isReadonly,
            @RequestParam(required = false, defaultValue = "true") Boolean isVisible) {
        
        log.info("Binding field {} to model {}", fieldPid, modelPid);
        
        MetaModelFieldBindingDTO binding = bindingService.bindFieldToModel(
            modelPid,
            fieldPid,
            displayOrder,
            isRequired,
            isReadonly,
            isVisible
        );
        
        return ApiResponse.success(binding);
    }
    
    /**
     * Unbind a field from a model
     * 
     * DELETE /api/meta/models/{modelPid}/fields/{fieldPid}
     */
    @DeleteMapping("/{modelPid}/fields/{fieldPid}")
    @RequirePermission(MetaPermission.MODEL_MANAGE)
    public ApiResponse<Boolean> unbindField(
            @PathVariable String modelPid,
            @PathVariable String fieldPid) {
        
        log.info("Unbinding field {} from model {}", fieldPid, modelPid);

        // Track unbind as user modification for OVERWRITE_SAFE protection
        MetaModelDTO model = metaModelService.findByPid(modelPid);
        MetaFieldDTO field = metaFieldService.findByPid(fieldPid);
        if (model != null && field != null) {
            String bindingCode = model.getCode() + "." + field.getCode();
            pluginResourceTracker.markAsUserModified(ResourceType.MODEL_FIELD_BINDING, bindingCode);
        }

        boolean result = bindingService.unbindFieldFromModel(modelPid, fieldPid);

        return ApiResponse.success(result);
    }
    
    /**
     * Get all fields bound to a model
     * 
     * GET /api/meta/models/{modelPid}/fields
     */
    @GetMapping("/{modelPid}/fields")
    @RequirePermission(MetaPermission.MODEL_READ)
    public ApiResponse<List<MetaFieldDTO>> getModelFields(@PathVariable String modelPid) {
        log.debug("Getting fields for model {}", modelPid);
        
        List<MetaFieldDTO> fields = bindingService.getModelFields(modelPid);
        
        return ApiResponse.success(fields);
    }
    
    /**
     * Get all bindings for a model
     * 
     * GET /api/meta/models/{modelPid}/bindings
     */
    @GetMapping("/{modelPid}/bindings")
    @RequirePermission(MetaPermission.MODEL_READ)
    public ApiResponse<List<MetaModelFieldBindingDTO>> getModelBindings(@PathVariable String modelPid) {
        log.debug("Getting bindings for model {}", modelPid);
        
        List<MetaModelFieldBindingDTO> bindings = bindingService.getModelBindings(modelPid);
        
        return ApiResponse.success(bindings);
    }
    
    /**
     * Reorder fields in a model
     * 
     * PUT /api/meta/models/{modelPid}/fields/reorder
     * 
     * Request body: Map<String, Integer> - field PID to new display order
     */
    @PutMapping("/{modelPid}/fields/reorder")
    @RequirePermission(MetaPermission.MODEL_MANAGE)
    public ApiResponse<Integer> reorderFields(
            @PathVariable String modelPid,
            @RequestBody Map<String, Integer> fieldOrders) {
        
        log.info("Reordering fields for model {}", modelPid);
        
        int updated = bindingService.reorderFields(modelPid, fieldOrders);
        
        return ApiResponse.success(updated);
    }
    
    /**
     * Update field binding configuration
     * 
     * PUT /api/meta/models/{modelPid}/fields/{fieldPid}/config
     */
    @PutMapping("/{modelPid}/fields/{fieldPid}/config")
    @RequirePermission(MetaPermission.MODEL_MANAGE)
    public ApiResponse<MetaModelFieldBindingDTO> updateFieldConfig(
            @PathVariable String modelPid,
            @PathVariable String fieldPid,
            @RequestParam(required = false) Boolean isRequired,
            @RequestParam(required = false) Boolean isReadonly,
            @RequestParam(required = false) Boolean isVisible) {
        
        log.info("Updating field config for model {} field {}", modelPid, fieldPid);
        
        MetaModelFieldBindingDTO binding = bindingService.updateFieldConfig(
            modelPid,
            fieldPid,
            isRequired,
            isReadonly,
            isVisible
        );
        
        return ApiResponse.success(binding);
    }
    
    /**
     * Batch bind fields to a model
     * 
     * POST /api/meta/models/{modelPid}/fields/batch
     * 
     * Request body: List<String> - field PIDs
     */
    @PostMapping("/{modelPid}/fields/batch")
    @RequirePermission(MetaPermission.MODEL_MANAGE)
    public ApiResponse<Integer> batchBindFields(
            @PathVariable String modelPid,
            @RequestBody List<String> fieldPids) {
        
        log.info("Batch binding {} fields to model {}", fieldPids.size(), modelPid);
        
        int bound = bindingService.batchBindFields(modelPid, fieldPids);
        
        return ApiResponse.success(bound);
    }
    
    /**
     * Bind a field to a model with full configuration
     * 
     * POST /api/meta/models/{modelPid}/fields/bind
     * 
     * This endpoint supports complete binding configuration including:
     * - Alias code
     * - Required, nullable, readonly, visible, editable flags
     * - Default value
     * - Dictionary override
     * - UI hint and validation override
     * - Display configuration
     * - Remarks
     */
    @PostMapping("/{modelPid}/fields/bind")
    @RequirePermission(MetaPermission.MODEL_MANAGE)
    public ApiResponse<MetaModelFieldBindingDTO> bindFieldWithConfig(
            @PathVariable String modelPid,
            @Valid @RequestBody FieldBindingRequest request) {
        
        log.info("Binding field {} to model {} with full configuration", 
                request.getFieldPid(), modelPid);
        
        MetaModelFieldBindingDTO binding = bindingService.bindFieldWithConfig(modelPid, request);
        
        log.info("Field binding created successfully: modelPid={}, fieldPid={}, bindingId={}", 
                modelPid, request.getFieldPid(), binding.getId());
        
        return ApiResponse.success("字段绑定成功", binding);
    }
    
    /**
     * Batch bind multiple fields to a model with common configuration
     * 
     * POST /api/meta/models/{modelPid}/fields/bind-batch
     * 
     * This endpoint allows binding multiple fields at once with common configuration
     * applied to all bindings.
     */
    @PostMapping("/{modelPid}/fields/bind-batch")
    @RequirePermission(MetaPermission.MODEL_MANAGE)
    public ApiResponse<List<MetaModelFieldBindingDTO>> batchBindFieldsWithConfig(
            @PathVariable String modelPid,
            @Valid @RequestBody BatchFieldBindingRequest request) {
        
        log.info("Batch binding {} fields to model {} with common configuration", 
                request.getFieldPids().size(), modelPid);
        
        List<MetaModelFieldBindingDTO> bindings = bindingService.batchBindFieldsWithConfig(
                modelPid, request);
        
        log.info("Batch field binding completed: modelPid={}, count={}", 
                modelPid, bindings.size());
        
        return ApiResponse.success("批量绑定成功", bindings);
    }
}
