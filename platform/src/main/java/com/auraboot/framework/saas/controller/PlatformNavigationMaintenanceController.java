package com.auraboot.framework.saas.controller;

import com.auraboot.framework.application.security.AdminRoleChecker;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.audit.entity.AdminEventLog;
import com.auraboot.framework.audit.service.AdminEventLogService;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.DisallowImpersonation;
import com.auraboot.framework.permission.annotation.RequirePlatformAdmin;
import com.auraboot.framework.permission.enums.RoleCodes;
import com.auraboot.framework.saas.bootstrap.PlatformConsoleMenuBootstrap;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static com.auraboot.framework.saas.executor.SystemTenantContextExecutor.SYSTEM_TENANT_ID;

/** Explicit distribution-navigation upgrade; ordinary startup does not write menu data. */
@RestController
@RequirePlatformAdmin
@DisallowImpersonation
@RequiredArgsConstructor
@RequestMapping("/api/admin/bootstrap/platform-navigation")
public class PlatformNavigationMaintenanceController {
    private final PlatformConsoleMenuBootstrap menus;
    private final AdminRoleChecker roles;
    private final AdminEventLogService audit;

    @PostMapping("/reconcile")
    public ApiResponse<Map<String, Boolean>> reconcile() {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        if (!SYSTEM_TENANT_ID.equals(tenantId) || userId == null || MetaContext.isImpersonating()
                || !"platform".equals(MetaContext.getCurrentExecutionScope())
                || !roles.hasRole(tenantId, userId, RoleCodes.PLATFORM_ADMIN)) {
            return ApiResponse.error(403, "System-space platform administrator required");
        }
        menus.reconcile(tenantId, userId);
        audit.record(AdminEventLog.builder().tenantId(tenantId).actorUserId(userId)
                .actionType("platform.navigation.reconcile").resourceType("navigation")
                .success(true).build());
        return ApiResponse.success(Map.of("reconciled", true));
    }
}
