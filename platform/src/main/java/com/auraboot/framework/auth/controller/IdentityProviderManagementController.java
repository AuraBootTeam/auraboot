package com.auraboot.framework.auth.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dto.IdentityProviderSaveRequest;
import com.auraboot.framework.auth.dto.IdentityProviderSummary;
import com.auraboot.framework.auth.service.IdentityProviderManagementService;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Minimal safe IdP registry administration; provider secrets remain in CloudConfig. */
@RestController
@RequestMapping("/api/admin/identity-providers")
@RequiredArgsConstructor
@RequirePermission(MetaPermission.CLOUD_CONFIG_MANAGE)
public class IdentityProviderManagementController {

    private final IdentityProviderManagementService service;

    @GetMapping
    public ApiResponse<List<IdentityProviderSummary>> list(
            @RequestParam(defaultValue = "business-web") String application) {
        return ApiResponse.success(service.list(application, MetaContext.getCurrentTenantId()));
    }

    @PostMapping
    public ApiResponse<IdentityProviderSummary> save(
            @RequestBody IdentityProviderSaveRequest request) {
        return ApiResponse.success(service.save(request, MetaContext.getCurrentTenantId()));
    }

    @PutMapping("/{pid}/status")
    public ApiResponse<Void> setStatus(
            @PathVariable String pid,
            @RequestParam String status) {
        service.setStatus(pid, status, MetaContext.getCurrentTenantId());
        return ApiResponse.success();
    }
}
