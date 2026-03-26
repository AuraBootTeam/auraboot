package com.auraboot.framework.cloudconfig.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.cloudconfig.dto.CloudConfigResponse;
import com.auraboot.framework.cloudconfig.dto.CloudConfigSaveRequest;
import com.auraboot.framework.cloudconfig.entity.CloudConfig;
import com.auraboot.framework.cloudconfig.service.CloudConfigConnectionTester;
import com.auraboot.framework.cloudconfig.service.CloudConfigService;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for cloud vendor configuration management.
 * <p>
 * Provides CRUD operations and effective-config lookup for
 * PLATFORM/TENANT layered cloud configurations.
 *
 * @since 6.3.0
 */
@RestController
@RequestMapping("/api/admin/cloud-config")
@RequiredArgsConstructor
@RequirePermission(MetaPermission.CLOUD_CONFIG_MANAGE)
public class CloudConfigController {

    private final CloudConfigService cloudConfigService;
    private final CloudConfigConnectionTester connectionTester;

    /**
     * List all configs at a given level.
     * GET /api/admin/cloud-config?level=PLATFORM|TENANT
     */
    @GetMapping
    public ApiResponse<List<CloudConfigResponse>> list(
            @RequestParam(defaultValue = "tenant") String level) {
        return ApiResponse.success(cloudConfigService.listConfigs(level));
    }

    /**
     * Get a single config by PID (with sensitive fields masked).
     * GET /api/admin/cloud-config/{pid}
     */
    @GetMapping("/{pid}")
    public ApiResponse<CloudConfigResponse> getByPid(@PathVariable String pid) {
        CloudConfigResponse response = cloudConfigService.getConfigMasked(pid);
        if (response == null) {
            return ApiResponse.error("Cloud config not found: " + pid);
        }
        return ApiResponse.success(response);
    }

    /**
     * Create or update a cloud configuration.
     * POST /api/admin/cloud-config
     * <p>
     * If request.pid is provided, updates the existing config.
     * Otherwise, creates a new config.
     */
    @PostMapping
    public ApiResponse<Void> save(@Valid @RequestBody CloudConfigSaveRequest request) {
        cloudConfigService.saveConfig(request);
        return ApiResponse.success();
    }

    /**
     * Update an existing cloud configuration by PID.
     * PUT /api/admin/cloud-config/{pid}
     */
    @PutMapping("/{pid}")
    public ApiResponse<Void> update(@PathVariable String pid,
                                     @Valid @RequestBody CloudConfigSaveRequest request) {
        request.setPid(pid);
        cloudConfigService.saveConfig(request);
        return ApiResponse.success();
    }

    /**
     * Soft-delete a config by PID.
     * DELETE /api/admin/cloud-config/{pid}
     */
    @DeleteMapping("/{pid}")
    public ApiResponse<Void> delete(@PathVariable String pid) {
        cloudConfigService.deleteConfig(pid);
        return ApiResponse.success();
    }

    /**
     * Get the effective config for a service type and provider code.
     * Tenant-level config takes priority over platform-level.
     * GET /api/admin/cloud-config/effective?serviceType=SMS&providerCode=tencent_sms
     */
    @GetMapping("/effective")
    public ApiResponse<CloudConfigResponse> getEffective(
            @RequestParam String serviceType,
            @RequestParam String providerCode) {
        Long tenantId = MetaContext.getCurrentTenantId();
        CloudConfig config = cloudConfigService.getEffectiveConfig(tenantId, serviceType, providerCode);
        if (config == null) {
            return ApiResponse.error("No effective config found for " + serviceType + "/" + providerCode);
        }
        // Return masked response for the admin API
        CloudConfigResponse response = cloudConfigService.getConfigMasked(config.getPid());
        return ApiResponse.success(response);
    }

    /**
     * Test connection for a cloud config.
     * Performs a lightweight probe against the provider's API to validate credentials.
     * POST /api/admin/cloud-config/{pid}/test
     */
    @PostMapping("/{pid}/test")
    public ApiResponse<Map<String, Object>> testConnection(@PathVariable String pid) {
        return ApiResponse.success(connectionTester.testConnection(pid));
    }
}
