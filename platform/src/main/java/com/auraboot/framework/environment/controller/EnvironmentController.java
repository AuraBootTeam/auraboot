package com.auraboot.framework.environment.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.environment.dto.*;
import com.auraboot.framework.environment.service.EnvironmentService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for multi-environment management.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/environments")
public class EnvironmentController {

    @Autowired
    private EnvironmentService environmentService;

    /**
     * List all environments for the current tenant.
     */
    @GetMapping
    public ApiResponse<List<EnvironmentResponse>> list() {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<EnvironmentResponse> result = environmentService.listAll(tenantId);
        return ApiResponse.success(result);
    }

    /**
     * Get a single environment by PID.
     */
    @GetMapping("/{pid}")
    public ApiResponse<EnvironmentResponse> getByPid(@PathVariable String pid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        EnvironmentResponse result = environmentService.getByPid(pid, tenantId);
        return ApiResponse.success(result);
    }

    /**
     * Create a new environment.
     */
    @PostMapping
    public ApiResponse<EnvironmentResponse> create(@Valid @RequestBody EnvironmentRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        EnvironmentResponse result = environmentService.create(request, tenantId, userId);
        return ApiResponse.success(result);
    }

    /**
     * Update an existing environment.
     */
    @PutMapping("/{pid}")
    public ApiResponse<EnvironmentResponse> update(
            @PathVariable String pid,
            @Valid @RequestBody EnvironmentRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        EnvironmentResponse result = environmentService.update(pid, request, tenantId, userId);
        return ApiResponse.success(result);
    }

    /**
     * Delete an environment (soft delete).
     */
    @DeleteMapping("/{pid}")
    public ApiResponse<Void> delete(@PathVariable String pid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        environmentService.delete(pid, tenantId);
        return ApiResponse.success(null);
    }

    /**
     * Export the configuration of an environment.
     */
    @PostMapping("/{code}/export")
    public ApiResponse<EnvironmentExportData> exportConfig(@PathVariable String code) {
        Long tenantId = MetaContext.getCurrentTenantId();
        EnvironmentExportData result = environmentService.exportConfig(code, tenantId);
        return ApiResponse.success(result);
    }

    /**
     * Import configuration into an environment.
     */
    @PostMapping("/{code}/import")
    public ApiResponse<EnvironmentResponse> importConfig(
            @PathVariable String code,
            @RequestBody EnvironmentExportData data) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        EnvironmentResponse result = environmentService.importConfig(code, data, tenantId, userId);
        return ApiResponse.success(result);
    }

    /**
     * Compare configuration between two environments.
     */
    @GetMapping("/diff")
    public ApiResponse<EnvironmentDiffResponse> diff(
            @RequestParam String source,
            @RequestParam String target) {
        Long tenantId = MetaContext.getCurrentTenantId();
        EnvironmentDiffResponse result = environmentService.diff(source, target, tenantId);
        return ApiResponse.success(result);
    }

    /**
     * Lock an environment. Reason is required and recorded for audit.
     */
    @PostMapping("/{pid}/lock")
    public ApiResponse<EnvironmentResponse> lock(
            @PathVariable String pid,
            @Valid @RequestBody EnvironmentLockRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        EnvironmentResponse result = environmentService.lock(pid, tenantId, userId, request.getReason());
        return ApiResponse.success(result);
    }

    /**
     * Unlock a locked environment. Reason is required and recorded for audit.
     */
    @PostMapping("/{pid}/unlock")
    public ApiResponse<EnvironmentResponse> unlock(
            @PathVariable String pid,
            @Valid @RequestBody EnvironmentLockRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        EnvironmentResponse result = environmentService.unlock(pid, tenantId, userId, request.getReason());
        return ApiResponse.success(result);
    }
}
