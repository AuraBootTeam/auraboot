package com.auraboot.framework.saas.controller;

import com.auraboot.framework.application.security.AdminRoleChecker;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.enums.RoleCodes;
import com.auraboot.framework.saas.executor.SystemTenantContextExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Platform-level statistics for the Platform Console Overview page.
 * All queries run in System Tenant context or against G1 tables (no tenant filter),
 * so every endpoint enumerates data ACROSS all tenants. This controller is mounted
 * under {@code /api/platform} (NOT {@code /api/admin/**}), so it is not covered by
 * {@link com.auraboot.framework.application.security.AdminRoleInterceptor}; and it
 * declares no {@code @RequirePermission}, so the PermissionInterceptor's default
 * {@code shadow} mode would otherwise permit ANY authenticated tenant member.
 *
 * <p>RBAC gap REG-2 (DDR-2026-06-30-quote-bom-rbac-capability-model-endstate): a
 * zero-privilege member was able to enumerate the full tenant registry + platform
 * stats. We therefore gate every method with an explicit {@code platform_admin}
 * check, mirroring {@code CrossTenantGrantController#guardPlatformAdmin}. Returns
 * HTTP 200 with {@code ApiResponse{code:403,...}} per the uniform envelope contract.
 */
@Slf4j
@RestController
@SuppressWarnings("java/spring-disabled-csrf-protection")
@RequestMapping("/api/platform")
@RequiredArgsConstructor
public class PlatformStatsController {

    private final JdbcTemplate jdbcTemplate;
    private final AdminRoleChecker adminRoleChecker;

    /**
     * Platform-admin guard. Returns {@code null} when the caller passes; otherwise
     * returns a non-null 403 envelope the caller must propagate verbatim. Cross-tenant
     * platform stats are visible only to {@code platform_admin} (system-tenant members).
     */
    private <T> ApiResponse<T> guardPlatformAdmin() {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        if (tenantId == null || userId == null) {
            return ApiResponse.error(403, "platform_admin required", null);
        }
        if (!adminRoleChecker.hasRole(tenantId, userId, RoleCodes.PLATFORM_ADMIN)) {
            log.warn("PlatformStatsController: rejected non-platform-admin (tenantId={}, userId={})",
                    tenantId, userId);
            return ApiResponse.error(403, "platform_admin required", null);
        }
        return null;
    }

    // codeql[java/csrf-unprotected-request-type] Read-only JWT API; CSRF is disabled centrally for stateless bearer-token authentication.
    @GetMapping("/stats")
    @SuppressWarnings("java/csrf-unprotected-request-type")
    public ApiResponse<Map<String, Object>> getStats() {
        ApiResponse<Map<String, Object>> denied = guardPlatformAdmin();
        if (denied != null) {
            return denied;
        }

        Long tenantCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_tenant WHERE deleted_flag = FALSE AND name != 'System'", Long.class);

        Long userCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_user WHERE deleted_flag = FALSE", Long.class);

        Long pluginCount = SystemTenantContextExecutor.executeAsSystem(() ->
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ab_marketplace_plugin WHERE deleted_flag = FALSE", Long.class));

        return ApiResponse.success(Map.of(
                "tenantCount", tenantCount != null ? tenantCount : 0,
                "userCount", userCount != null ? userCount : 0,
                "pluginCount", pluginCount != null ? pluginCount : 0
        ));
    }

    @GetMapping("/tenants")
    @SuppressWarnings("java/csrf-unprotected-request-type")
    public ApiResponse<?> listTenants() {
        ApiResponse<?> denied = guardPlatformAdmin();
        if (denied != null) {
            return denied;
        }

        var tenants = jdbcTemplate.queryForList(
                """
                SELECT t.id, t.pid, t.name, t.display_name, t.status, t.created_at,
                       (SELECT COUNT(*) FROM ab_tenant_member tm
                        WHERE tm.tenant_id = t.id AND tm.status = 'active' AND tm.deleted_flag = FALSE) as user_count
                FROM ab_tenant t
                WHERE t.deleted_flag = FALSE AND t.name != 'System'
                ORDER BY t.created_at DESC
                """);
        return ApiResponse.success(tenants);
    }
}
