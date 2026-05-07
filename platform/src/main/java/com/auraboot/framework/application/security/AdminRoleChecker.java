package com.auraboot.framework.application.security;

import com.auraboot.framework.permission.enums.RoleCodes;
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
 * <p>Caching strategy: per-instance Caffeine cache with 60 s TTL after write and
 * a maximum of 10 000 entries. This reduces JDBC pressure on the admin role-check
 * path while bounding staleness to one minute — acceptable given that role
 * assignments change rarely. Stats are recorded and exposed via Micrometer gauges
 * ({@code aura.admin.role_check.cache.hit}, {@code .miss}, {@code .size}) for
 * production observability.
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
     * Result is cached for 60 s per {@code (tenantId, userId, roleCode)} triple.
     */
    public boolean hasRole(Long tenantId, Long userId, String roleCode) {
        if (tenantId == null || userId == null || roleCode == null) {
            return false;
        }
        return cache.get(new RoleCacheKey(tenantId, userId, roleCode),
                k -> lookupFromDb(k.tenantId(), k.userId(), k.roleCode()));
    }

    // -------------------------------------------------------------------------
    // Cache management (package-visible for test cleanup)
    // -------------------------------------------------------------------------

    /** Invalidates all cache entries. Intended for test teardown only. */
    void invalidateAll() {
        cache.invalidateAll();
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private boolean lookupFromDb(Long tenantId, Long userId, String roleCode) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_user_role ur " +
                        " JOIN ab_tenant_member tm ON ur.member_id = tm.id " +
                        " JOIN ab_role r ON ur.role_id = r.id " +
                        " WHERE tm.user_id = ? " +
                        "   AND ur.tenant_id = ? " +
                        "   AND r.code = ? " +
                        "   AND (ur.deleted_flag = FALSE OR ur.deleted_flag IS NULL) " +
                        "   AND ur.status = 'active' " +
                        "   AND (r.deleted_flag = FALSE OR r.deleted_flag IS NULL) " +
                        "   AND r.status = 'active'",
                Long.class, userId, tenantId, roleCode);
        return count != null && count > 0;
    }

    private record RoleCacheKey(Long tenantId, Long userId, String roleCode) {}
}
