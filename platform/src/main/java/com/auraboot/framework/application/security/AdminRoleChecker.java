package com.auraboot.framework.application.security;

import com.auraboot.framework.permission.enums.RoleCodes;
import com.auraboot.framework.rbac.constant.RoleConstants;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Shared helper that answers "does {@code userId} hold {@code roleCode} in {@code tenantId}?".
 *
 * <p>Consolidates the role-lookup SQL previously duplicated in
 * {@code UserSoulProfileAdminController.guardTenantAdmin()} so both
 * {@link AdminRoleInterceptor} and any future programmatic check share one
 * source of truth.
 *
 * <p>Join path follows the Phase-2 RBAC schema:
 * <pre>ab_tenant_member (user_id → id) → ab_user_role (member_id) → ab_role (code)</pre>
 *
 * <p>Privileged tenant/platform administration is checked against current database state on every
 * call. A cached positive result must never outlive a revocation, expiry or subject deactivation.
 * Non-privileged role lookups retain the bounded cache; cache metrics cover those lookups only.
 */
@Component
public class AdminRoleChecker {

    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;

    private final Cache<RoleCacheKey, Boolean> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60))
            .maximumSize(10_000)
            .recordStats()
            .build();

    public AdminRoleChecker(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void registerCacheMetrics() {
        meterRegistry.gauge("aura.admin.role_check.cache.hit",
                cache, c -> c.stats().hitCount());
        meterRegistry.gauge("aura.admin.role_check.cache.miss",
                cache, c -> c.stats().missCount());
        meterRegistry.gauge("aura.admin.role_check.cache.size",
                cache, Cache::estimatedSize);
    }

    /**
     * @return {@code true} when an active, non-deleted {@code ab_user_role} row
     * binds {@code userId} (via {@code ab_tenant_member}) to an active,
     * non-deleted role with {@code code = roleCode} in {@code tenantId}.
     * Privileged role results are never cached. Other results are cached for 60 seconds.
     */
    public boolean hasRole(Long tenantId, Long userId, String roleCode) {
        if (tenantId == null || userId == null || roleCode == null) {
            return false;
        }
        if (RoleCodes.TENANT_ADMIN.equals(roleCode) || RoleCodes.PLATFORM_ADMIN.equals(roleCode)) {
            return lookupFromDb(tenantId, userId, roleCode);
        }
        return cache.get(new RoleCacheKey(tenantId, userId, roleCode),
                k -> lookupFromDb(k.tenantId(), k.userId(), k.roleCode()));
    }

    // -------------------------------------------------------------------------
    // Cache management
    // -------------------------------------------------------------------------

    /**
     * Invalidates all cache entries.
     *
     * <p>Public so cross-package integration tests can drop stale entries between
     * cases (the 60 s TTL would otherwise hold a {@code false} from caseA over
     * into caseB when a role is granted between cases — see
     * {@code CrossTenantGrantControllerIntegrationTest}).
     */
    public void invalidateAll() {
        cache.invalidateAll();
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private boolean lookupFromDb(Long tenantId, Long userId, String roleCode) {
        // Platform-only roles (e.g. platform_admin) live in System Tenant with
        // scope_type=global and apply across the user's MetaContext regardless
        // of which business tenant the request is currently scoped to. Drop
        // the tenant_id filter for these so a System-Tenant grant satisfies a
        // request whose MetaContext is a business tenant.
        boolean platformOnly = RoleConstants.isPlatformOnly(roleCode);
        Long sourceTenant = platformOnly
                ? com.auraboot.framework.saas.executor.SystemTenantContextExecutor.SYSTEM_TENANT_ID : tenantId;
        String sql = "SELECT COUNT(*) FROM ab_user_role ur " +
                " JOIN ab_tenant_member tm ON ur.member_id = tm.id " +
                " JOIN ab_role r ON ur.role_id = r.id " +
                " JOIN ab_user u ON u.id = tm.user_id " +
                " WHERE tm.user_id = ? " +
                "   AND ur.tenant_id = ? " +
                "   AND r.code = ? " +
                (platformOnly ? " AND r.tenant_id = ur.tenant_id AND r.scope_type = 'global' " : "") +
                "   AND tm.tenant_id = ur.tenant_id " +
                "   AND (r.tenant_id = ur.tenant_id OR r.tenant_id IS NULL) " +
                "   AND tm.status = 'active' AND tm.deleted_flag = FALSE " +
                "   AND u.is_enabled = TRUE AND u.deleted_flag = FALSE " +
                "   AND (ur.effective_date IS NULL OR ur.effective_date <= CURRENT_DATE) " +
                "   AND (ur.expiry_date IS NULL OR ur.expiry_date >= CURRENT_DATE) " +
                "   AND (ur.deleted_flag = FALSE OR ur.deleted_flag IS NULL) " +
                "   AND ur.status = 'active' " +
                "   AND (r.deleted_flag = FALSE OR r.deleted_flag IS NULL) " +
                "   AND r.status = 'active'";
        Long count = jdbcTemplate.queryForObject(sql, Long.class, userId, sourceTenant, roleCode);
        return count != null && count > 0;
    }

    private record RoleCacheKey(Long tenantId, Long userId, String roleCode) {}
}
