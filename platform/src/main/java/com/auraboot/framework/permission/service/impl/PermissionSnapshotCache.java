package com.auraboot.framework.permission.service.impl;

import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.entity.UserRole;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.auraboot.framework.rbac.mapper.UserRoleMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Security-sensitive permission snapshots backed by the dedicated short-lived permission cache.
 *
 * <p>This component deliberately uses {@link Cache#get(Object, java.util.concurrent.Callable)}
 * instead of proxy-based {@code @Cacheable} methods. Permission checks frequently call one another
 * inside the same service and Spring self-invocation bypasses cache annotations. Atomic cache loads
 * also collapse concurrent misses for the same tenant and subject into a single database load.
 *
 * <p>The cache hierarchy is:
 *
 * <pre>
 * tenant permission catalog (code -&gt; definition, including negative lookups)
 * tenant user roles
 * tenant role permissions for the current date
 * tenant effective user permission snapshot for the current date
 * </pre>
 *
 * <p>Date-sensitive keys prevent grants with {@code effective_date}/{@code expiry_date} from being
 * reused across a date boundary even when their five-minute cache entry still exists.
 */
@Slf4j
@Component
public class PermissionSnapshotCache {

    public static final String PERMISSION_CATALOG_CACHE = "permission-catalog";
    public static final String USER_ROLE_CACHE = "user-role-snapshots";
    public static final String ROLE_PERMISSION_CACHE = "role-permission-snapshots";
    public static final String BASELINE_ROLE_CACHE = "baseline-role-snapshots";
    public static final String EFFECTIVE_PERMISSION_CACHE = "user-permissions";

    private static final String BASELINE_ROLE_CODE = "tenant_member";

    private final PermissionMapper permissionMapper;
    private final UserRoleMapper userRoleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final RoleMapper roleMapper;
    private final CacheManager cacheManager;

    public PermissionSnapshotCache(
            PermissionMapper permissionMapper,
            UserRoleMapper userRoleMapper,
            RolePermissionMapper rolePermissionMapper,
            RoleMapper roleMapper,
            @Qualifier("permissionCacheManager") CacheManager cacheManager) {
        this.permissionMapper = permissionMapper;
        this.userRoleMapper = userRoleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.roleMapper = roleMapper;
        this.cacheManager = cacheManager;
    }

    public Set<Long> getEffectivePermissionIds(Long tenantId, Long userId, Long memberId) {
        if (tenantId == null || userId == null || memberId == null) {
            return Collections.emptySet();
        }
        LocalDate today = LocalDate.now();
        EffectivePermissionKey key = new EffectivePermissionKey(tenantId, userId, today);
        return getOrLoad(EFFECTIVE_PERMISSION_CACHE, key,
                () -> loadEffectivePermissionIds(tenantId, userId, memberId, today));
    }

    public Long resolvePermissionId(Long tenantId, String permissionCode) {
        Permission permission = resolvePermissionDefinition(tenantId, permissionCode);
        return permission == null ? null : permission.getId();
    }

    /**
     * Resolve a permission definition from the tenant catalog.
     *
     * <p>The complete catalog is loaded atomically on the first lookup. This is intentional: policy
     * evaluation probes several backward-compatible permission-code candidates per request, most
     * of which do not exist. A per-code cache would still execute one SQL statement for every cold
     * negative lookup, while the catalog turns the whole candidate loop into a single tenant query.
     * Callers must treat the returned entity as read-only.
     */
    public Permission resolvePermissionDefinition(Long tenantId, String permissionCode) {
        if (tenantId == null || permissionCode == null || permissionCode.isBlank()) {
            return null;
        }
        return getPermissionCatalog(tenantId).definitionsByNormalizedCode()
                .get(normalizePermissionCode(permissionCode));
    }

    public Set<String> resolvePermissionCodes(Long tenantId, Set<Long> permissionIds) {
        if (tenantId == null || permissionIds == null || permissionIds.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> result = new LinkedHashSet<>();
        getPermissionCatalog(tenantId).codesById().forEach((id, code) -> {
            if (permissionIds.contains(id)) {
                result.add(code);
            }
        });
        return Collections.unmodifiableSet(result);
    }

    public void evictUser(Long tenantId, Long userId) {
        if (tenantId == null || userId == null) {
            return;
        }
        cache(USER_ROLE_CACHE).evict(new UserRoleKey(tenantId, userId));
        cache(EFFECTIVE_PERMISSION_CACHE)
                .evict(new EffectivePermissionKey(tenantId, userId, LocalDate.now()));
    }

    public void evictRole(Long tenantId, Long roleId) {
        if (tenantId != null && roleId != null) {
            cache(ROLE_PERMISSION_CACHE)
                    .evict(new RolePermissionKey(tenantId, roleId, LocalDate.now()));
        }
        // A role grant can affect many users, and the implicit baseline role has no membership rows.
        // Clearing this small, short-lived projection is safer than attempting an incomplete fan-out.
        cache(EFFECTIVE_PERMISSION_CACHE).clear();
    }

    public void evictPermissionCatalog(Long tenantId) {
        if (tenantId != null) {
            cache(PERMISSION_CATALOG_CACHE).evict(new PermissionCatalogKey(tenantId));
        }
    }

    public void clearPermissionCatalogs() {
        cache(PERMISSION_CATALOG_CACHE).clear();
    }

    public void clearAll() {
        cache(PERMISSION_CATALOG_CACHE).clear();
        cache(USER_ROLE_CACHE).clear();
        cache(ROLE_PERMISSION_CACHE).clear();
        cache(BASELINE_ROLE_CACHE).clear();
        cache(EFFECTIVE_PERMISSION_CACHE).clear();
    }

    private Set<Long> loadEffectivePermissionIds(
            Long tenantId, Long userId, Long memberId, LocalDate today) {
        List<Long> roleIds = getUserRoleIds(tenantId, userId, memberId);
        LinkedHashSet<Long> effective = new LinkedHashSet<>();
        for (Long roleId : roleIds) {
            effective.addAll(getRolePermissionIds(tenantId, roleId, today));
        }

        Long baselineRoleId = getBaselineRoleId(tenantId);
        if (baselineRoleId != null && !roleIds.contains(baselineRoleId)) {
            effective.addAll(getRolePermissionIds(tenantId, baselineRoleId, today));
        }

        log.debug("Resolved permission snapshot: tenantId={}, userId={}, memberId={}, roles={}, permissions={}",
                tenantId, userId, memberId, roleIds.size(), effective.size());
        return Collections.unmodifiableSet(effective);
    }

    /**
     * Resolve the current tenant member's role IDs from the shared authorization snapshot.
     *
     * <p>This method is also used by record-level policy evaluation. Without sharing this layer,
     * every backward-compatible permission-code candidate would re-query the same user-role rows.
     */
    public List<Long> getUserRoleIds(Long tenantId, Long userId, Long memberId) {
        if (tenantId == null || userId == null || memberId == null) {
            return List.of();
        }
        UserRoleKey key = new UserRoleKey(tenantId, userId);
        return getOrLoad(USER_ROLE_CACHE, key, () -> {
            List<UserRole> rows = userRoleMapper.findByMemberIdAndTenantId(memberId, tenantId);
            if (rows == null || rows.isEmpty()) {
                return List.of();
            }
            return rows.stream()
                    .map(UserRole::getRoleId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
        });
    }

    private Set<Long> getRolePermissionIds(Long tenantId, Long roleId, LocalDate today) {
        RolePermissionKey key = new RolePermissionKey(tenantId, roleId, today);
        return getOrLoad(ROLE_PERMISSION_CACHE, key, () -> {
            Set<Long> permissionIds = rolePermissionMapper.findPermissionIdsByRole(roleId);
            if (permissionIds == null || permissionIds.isEmpty()) {
                return Set.of();
            }
            return Collections.unmodifiableSet(new LinkedHashSet<>(permissionIds));
        });
    }

    private Long getBaselineRoleId(Long tenantId) {
        BaselineRoleLookup lookup = getOrLoad(
                BASELINE_ROLE_CACHE,
                new BaselineRoleKey(tenantId),
                () -> {
                    Role role = roleMapper.findByTenantIdAndCode(tenantId, BASELINE_ROLE_CODE);
                    return new BaselineRoleLookup(role == null ? null : role.getId());
                });
        return lookup.roleId();
    }

    private PermissionCatalog getPermissionCatalog(Long tenantId) {
        return getOrLoad(PERMISSION_CATALOG_CACHE, new PermissionCatalogKey(tenantId), () -> {
            List<Permission> permissions = permissionMapper.findResolvableDefinitions();
            if (permissions == null || permissions.isEmpty()) {
                return new PermissionCatalog(Map.of(), Map.of());
            }
            Map<String, Permission> definitionsByCode = new LinkedHashMap<>();
            Map<Long, String> codesById = new LinkedHashMap<>();
            for (Permission permission : permissions) {
                if (permission == null || permission.getId() == null || permission.getCode() == null) {
                    continue;
                }
                // Query order is newest first, matching PermissionMapper.findByCode semantics.
                definitionsByCode.putIfAbsent(normalizePermissionCode(permission.getCode()), permission);
                codesById.putIfAbsent(permission.getId(), permission.getCode());
            }
            return new PermissionCatalog(
                    Collections.unmodifiableMap(definitionsByCode),
                    Collections.unmodifiableMap(codesById));
        });
    }

    private String normalizePermissionCode(String permissionCode) {
        return permissionCode.toLowerCase(Locale.ROOT);
    }

    private Cache cache(String cacheName) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache == null) {
            throw new IllegalStateException("Required permission cache is not configured: " + cacheName);
        }
        return cache;
    }

    private <T> T getOrLoad(String cacheName, Object key, Supplier<T> loader) {
        return cache(cacheName).get(key, loader::get);
    }

    private record PermissionCatalogKey(Long tenantId) {
    }

    private record PermissionCatalog(
            Map<String, Permission> definitionsByNormalizedCode,
            Map<Long, String> codesById) {
    }

    private record UserRoleKey(Long tenantId, Long userId) {
    }

    private record RolePermissionKey(Long tenantId, Long roleId, LocalDate effectiveDate) {
    }

    private record BaselineRoleKey(Long tenantId) {
    }

    private record EffectivePermissionKey(Long tenantId, Long userId, LocalDate effectiveDate) {
    }

    private record BaselineRoleLookup(Long roleId) {
    }
}
