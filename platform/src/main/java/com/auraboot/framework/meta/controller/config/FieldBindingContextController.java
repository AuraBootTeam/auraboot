package com.auraboot.framework.meta.controller.config;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.dto.BindingConfigRequest;
import com.auraboot.framework.meta.dto.BindingConfiguration;
import com.auraboot.framework.meta.dto.MetaFieldDTO;
import com.auraboot.framework.meta.service.FieldBindingContextService;
import com.auraboot.framework.meta.service.MetaFieldService;
import com.auraboot.framework.permission.annotation.RequirePermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * Field binding context controller
 * Provides REST API for field binding context configuration
 * 
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Slf4j
@RestController
@RequestMapping("/api/meta/models/{modelPid}/field-bindings")
@RequiredArgsConstructor
public class FieldBindingContextController {

    private final FieldBindingContextService bindingContextService;
    private final MetaFieldService metaFieldService;

    /**
     * Configure field binding context
     * POST /api/meta/models/{modelPid}/field-bindings/{fieldPid}/configure
     * 
     * @param modelPid Model PID
     * @param fieldPid Field PID
     * @param request Binding configuration request
     * @return Configured binding
     */
    @PostMapping("/{fieldPid}/configure")
    @RequirePermission("model.update")
    public ApiResponse<BindingConfiguration> configureBinding(
            @PathVariable String modelPid,
            @PathVariable String fieldPid,
            @RequestBody BindingConfigRequest request) {
        
        log.info("Configuring field binding: modelPid={}, fieldPid={}", modelPid, fieldPid);
        
        BindingConfiguration binding = bindingContextService.configureBinding(modelPid, fieldPid, request);
        
        return ApiResponse.success(binding);
    }

    /**
     * Get binding configuration
     * GET /api/meta/models/{modelPid}/field-bindings/{fieldPid}
     * 
     * @param modelPid Model PID
     * @param fieldPid Field PID
     * @return Binding configuration
     */
    @GetMapping("/{fieldPid}")
    @RequirePermission("model.read")
    public ApiResponse<BindingConfiguration> getBindingConfiguration(
            @PathVariable String modelPid,
            @PathVariable String fieldPid) {
        
        log.info("Getting binding configuration: modelPid={}, fieldPid={}", modelPid, fieldPid);
        
        return bindingContextService.getBindingConfiguration(modelPid, fieldPid)
            .map(ApiResponse::success)
            .orElse(ApiResponse.success(null));
    }

    /**
     * Update binding configuration
     * PUT /api/meta/models/{modelPid}/field-bindings/{bindingId}
     * 
     * @param modelPid Model PID
     * @param bindingId Binding ID
     * @param request Binding configuration request
     * @return Updated binding configuration
     */
    @PutMapping("/{bindingId}")
    @RequirePermission("model.update")
    public ApiResponse<BindingConfiguration> updateBindingConfiguration(
            @PathVariable String modelPid,
            @PathVariable Long bindingId,
            @RequestBody BindingConfigRequest request) {
        
        log.info("Updating binding configuration: modelPid={}, bindingId={}", modelPid, bindingId);
        
        BindingConfiguration binding = bindingContextService.updateBindingConfiguration(bindingId, request);
        
        return ApiResponse.success(binding);
    }

    /**
     * Get default binding configuration
     * GET /api/meta/models/{modelPid}/field-bindings/{fieldPid}/default
     * 
     * @param modelPid Model PID
     * @param fieldPid Field PID
     * @return Default binding configuration
     */
    @GetMapping("/{fieldPid}/default")
    @RequirePermission("model.read")
    public ApiResponse<BindingConfiguration> getDefaultBindingConfiguration(
            @PathVariable String modelPid,
            @PathVariable String fieldPid) {
        
        log.info("Getting default binding configuration: modelPid={}, fieldPid={}", modelPid, fieldPid);
        
        MetaFieldDTO field = metaFieldService.findByPid(fieldPid);
        if (field == null) {
            return ApiResponse.error("Field not found: " + fieldPid);
        }
        
        BindingConfiguration defaultConfig = bindingContextService.getDefaultBindingConfiguration(field);
        
        return ApiResponse.success(defaultConfig);
    }
}
