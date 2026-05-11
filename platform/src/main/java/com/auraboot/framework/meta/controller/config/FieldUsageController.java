package com.auraboot.framework.meta.controller.config;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.dto.BindingConfiguration;
import com.auraboot.framework.meta.service.FieldUsageService;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Field usage controller
 * Provides REST API for field usage tracking and statistics
 * 
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Slf4j
@RestController
@RequestMapping("/api/meta/fields/{fieldPid}/usage")
@RequiredArgsConstructor
public class FieldUsageController {

    private final FieldUsageService fieldUsageService;

    /**
     * Get field usage information
     * GET /api/meta/fields/{fieldPid}/usage
     * 
     * @param fieldPid Field PID
     * @return Field usage information
     */
    @GetMapping
    @RequirePermission(MetaPermission.FIELD_READ)
    @SuppressWarnings("java/csrf-unprotected-request-type")
    public ApiResponse<FieldUsageService.FieldUsageInfo> getFieldUsage(@PathVariable String fieldPid) {
        log.info("Getting field usage: fieldPid={}", fieldPid);
        
        FieldUsageService.FieldUsageInfo usageInfo = fieldUsageService.getFieldUsage(fieldPid);
        
        return ApiResponse.success(usageInfo);
    }

    /**
     * Get models using this field
     * GET /api/meta/fields/{fieldPid}/usage/models
     * 
     * @param fieldPid Field PID
     * @return List of models using this field
     */
    @GetMapping("/models")
    @RequirePermission(MetaPermission.FIELD_READ)
    @SuppressWarnings("java/csrf-unprotected-request-type")
    public ApiResponse<List<FieldUsageService.ModelReference>> getModelsUsingField(@PathVariable String fieldPid) {
        log.info("Getting models using field: fieldPid={}", fieldPid);
        
        List<FieldUsageService.ModelReference> models = fieldUsageService.getModelsUsingField(fieldPid);
        
        return ApiResponse.success(models);
    }

    /**
     * Get binding configurations for this field
     * GET /api/meta/fields/{fieldPid}/usage/bindings
     * 
     * @param fieldPid Field PID
     * @return List of binding configurations
     */
    @GetMapping("/bindings")
    @RequirePermission(MetaPermission.FIELD_READ)
    @SuppressWarnings("java/csrf-unprotected-request-type")
    public ApiResponse<List<BindingConfiguration>> getBindingConfigurations(@PathVariable String fieldPid) {
        log.info("Getting binding configurations: fieldPid={}", fieldPid);
        
        List<BindingConfiguration> bindings = fieldUsageService.getBindingConfigurations(fieldPid);
        
        return ApiResponse.success(bindings);
    }

    /**
     * Export field usage report
     * GET /api/meta/fields/{fieldPid}/usage/report
     * 
     * @param fieldPid Field PID
     * @return Field usage report
     */
    @GetMapping("/report")
    @RequirePermission(MetaPermission.FIELD_READ)
    @SuppressWarnings("java/csrf-unprotected-request-type")
    public ApiResponse<FieldUsageService.FieldUsageReport> exportUsageReport(@PathVariable String fieldPid) {
        log.info("Exporting usage report: fieldPid={}", fieldPid);
        
        FieldUsageService.FieldUsageReport report = fieldUsageService.exportUsageReport(fieldPid);
        
        return ApiResponse.success(report);
    }

    /**
     * Get field usage statistics
     * GET /api/meta/fields/{fieldPid}/usage/statistics
     * 
     * @param fieldPid Field PID
     * @return Field usage statistics
     */
    @GetMapping("/statistics")
    @RequirePermission(MetaPermission.FIELD_READ)
    @SuppressWarnings("java/csrf-unprotected-request-type")
    public ApiResponse<FieldUsageService.FieldUsageStatistics> getUsageStatistics(@PathVariable String fieldPid) {
        log.info("Getting usage statistics: fieldPid={}", fieldPid);
        
        FieldUsageService.FieldUsageStatistics statistics = fieldUsageService.calculateUsageStatistics(fieldPid);
        
        return ApiResponse.success(statistics);
    }
}
