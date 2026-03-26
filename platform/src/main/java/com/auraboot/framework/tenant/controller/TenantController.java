package com.auraboot.framework.tenant.controller;

import io.swagger.v3.oas.annotations.tags.Tag;

import com.auraboot.framework.application.annotation.CurrentUserId;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.tenant.dto.TenantRequest;
import com.auraboot.framework.tenant.dto.TenantResponse;
import com.auraboot.framework.tenant.service.TenantApplicationService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;


@Slf4j
@RestController
@RequestMapping("/api/tenant")
//@RequirePermission("testPermission")
@Tag(name = "Tenants", description = "Tenant management")
public class TenantController {
    
    @Autowired
    private TenantApplicationService tenantApplicationService;
    
    /**
     * 获取当前租户信息
     */
    @GetMapping("/info")
    @ResponseBody
    @RequirePermission(MetaPermission.TENANT_READ)
    public ApiResponse<TenantResponse> getCurrentTenantInfo(@CurrentUserId Long userId) {

            TenantResponse response = tenantApplicationService.getCurrentTenantInfo(userId);
            return ApiResponse.success(response);

    }
    
    /**
     * 根据PID获取租户信息
     */
    @GetMapping("/{tenantPid}")
    @ResponseBody
    @RequirePermission(MetaPermission.TENANT_READ)
    public ApiResponse<TenantResponse> getTenant(
            @PathVariable String tenantPid,
            @CurrentUserId Long userId) {

            TenantResponse response = tenantApplicationService.getTenantByPid(tenantPid, userId);
            return ApiResponse.success(response);

    }
    
    /**
     * 更新租户信息
     */
    @PutMapping("/{tenantPid}")
    @ResponseBody
    @RequirePermission(MetaPermission.TENANT_MANAGE)
    public ApiResponse<TenantResponse> updateTenant(
            @PathVariable String tenantPid,
            @Valid @RequestBody TenantRequest request,
            @CurrentUserId Long userId) {

            TenantResponse response = tenantApplicationService.updateTenant(tenantPid, request, userId);
            return ApiResponse.success(response);

    }
}