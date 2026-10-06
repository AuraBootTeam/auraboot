package com.auraboot.framework.cloudconfig.controller;

import com.auraboot.framework.application.security.AdminRoleChecker;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.cloudconfig.dto.CloudConfigResponse;
import com.auraboot.framework.cloudconfig.dto.CloudConfigSaveRequest;
import com.auraboot.framework.cloudconfig.service.CloudConfigConnectionTester;
import com.auraboot.framework.cloudconfig.service.CloudConfigService;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.enums.RoleCodes;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** LLM-only administration using the existing model-service permission. */
@RestController
@RequestMapping("/api/llm-config")
@RequiredArgsConstructor
@RequirePermission("ai_center")
public class LlmConfigController {
    private final CloudConfigService cloudConfigService;
    private final CloudConfigConnectionTester connectionTester;
    private final AdminRoleChecker adminRoleChecker;

    @GetMapping
    public ApiResponse<List<CloudConfigResponse>> list(@RequestParam(defaultValue = "tenant") String level) {
        return ApiResponse.success(cloudConfigService.listConfigs(requireLevel(level)).stream()
                .filter(config -> "llm".equals(normalize(config.getServiceType()))).toList());
    }

    @GetMapping("/{pid}")
    public ApiResponse<CloudConfigResponse> getByPid(@PathVariable String pid) {
        return ApiResponse.success(requireConfig(pid));
    }

    @PostMapping
    public ApiResponse<Void> save(@Valid @RequestBody CloudConfigSaveRequest request) {
        if (!"llm".equals(normalize(request.getServiceType()))) {
            throw new AccessDeniedException("Only LLM configurations are allowed");
        }
        String level = requireLevel(request.getConfigLevel());
        if (request.getPid() != null && !request.getPid().isBlank()
                && !level.equals(normalize(requireConfig(request.getPid()).getConfigLevel()))) {
            throw new BusinessException(ResponseCode.BadParam, "Configuration level cannot be changed");
        }
        request.setServiceType("llm");
        request.setConfigLevel(level);
        cloudConfigService.saveConfig(request);
        return ApiResponse.success();
    }

    @PutMapping("/{pid}")
    public ApiResponse<Void> update(@PathVariable String pid, @Valid @RequestBody CloudConfigSaveRequest request) {
        request.setPid(pid);
        return save(request);
    }

    @DeleteMapping("/{pid}")
    public ApiResponse<Void> delete(@PathVariable String pid) {
        requireConfig(pid);
        cloudConfigService.deleteConfig(pid);
        return ApiResponse.success();
    }

    @PostMapping("/{pid}/test")
    public ApiResponse<Map<String, Object>> testConnection(@PathVariable String pid) {
        requireConfig(pid);
        return ApiResponse.success(connectionTester.testConnection(pid));
    }

    private CloudConfigResponse requireConfig(String pid) {
        CloudConfigResponse config = cloudConfigService.getConfigMasked(pid);
        if (config == null || !"llm".equals(normalize(config.getServiceType()))) {
            throw new AccessDeniedException("LLM configuration is not accessible");
        }
        String level = requireLevel(config.getConfigLevel());
        if ("tenant".equals(level) && (MetaContext.getCurrentTenantId() == null
                || !Objects.equals(config.getTenantId(), MetaContext.getCurrentTenantId()))) {
            throw new AccessDeniedException("LLM configuration is not accessible");
        }
        return config;
    }

    private String requireLevel(String level) {
        String normalized = normalize(level);
        if (!"tenant".equals(normalized) && !"platform".equals(normalized)) {
            throw new BusinessException(ResponseCode.BadParam, "Configuration level must be tenant or platform");
        }
        if ("platform".equals(normalized) && !adminRoleChecker.hasRole(
                MetaContext.getCurrentTenantId(), MetaContext.getCurrentUserId(), RoleCodes.PLATFORM_ADMIN)) {
            throw new AccessDeniedException("platform_admin required for platform LLM configurations");
        }
        return normalized;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
