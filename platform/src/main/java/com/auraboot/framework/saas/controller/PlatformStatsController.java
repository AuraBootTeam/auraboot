package com.auraboot.framework.saas.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.saas.executor.SystemTenantContextExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Platform-level statistics for the Platform Console Overview page.
 * All queries run in System Tenant context or against G1 tables (no tenant filter).
 */
@RestController
@SuppressWarnings("java/spring-disabled-csrf-protection")
@RequestMapping("/api/platform")
@RequiredArgsConstructor
public class PlatformStatsController {

    private final JdbcTemplate jdbcTemplate;

    // lgtm[java/spring-disabled-csrf-protection] Read-only JWT API; CSRF is disabled centrally for stateless bearer-token authentication.
    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> getStats() {
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
    public ApiResponse<?> listTenants() {
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
