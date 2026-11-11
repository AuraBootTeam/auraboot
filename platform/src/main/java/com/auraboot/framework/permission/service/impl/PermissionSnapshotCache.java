package com.auraboot.framework.permission.service.impl;

import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.entity.UserRole;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.auraboot.framework.rbac.mapper.UserRoleMapper;
import com.auraboot.framework.rbac.service.MemberRoleContributor;
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

    private final ThreadLocal<Map<FreshLookupKey, Object>> freshReadValues = new ThreadLocal<>();

    /**
     * Host-only, thread-bound fresh reads through the same loaders. No shared cache is read or
     * populated in this scope. Loader results are reused only within this callback; an explicit
     * nested callback starts fresh and restores its parent on exit. This does not change
     * transaction visibility or contributor caches.
     */
    public <T> T withFreshReads(Supplier<T> read) {
        Objects.requireNonNull(read, "read");
        Map<FreshLookupKey, Object> previous = freshReadValues.get();
        freshReadValues.set(new LinkedHashMap<>());
        try { return read.get(); }
        finally {
            if (previous == null) freshReadValues.remove();
            else freshReadValues.set(previous);
        }
    }

    private final PermissionMapper permissionMapper;
    private final UserRoleMapper userRoleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final RoleMapper roleMapper;
    private final List<MemberRoleContributor> memberRoleContributors;
    private final CacheManager cacheManager;

    public PermissionSnapshotCache(
            PermissionMapper permissionMapper,
            UserRoleMapper userRoleMapper,
            RolePermissionMapper rolePermissionMapper,
            RoleMapper roleMapper,
            List<MemberRoleContributor> memberRoleContributors,
            @Qualifier("permissionCacheManager") CacheManager cacheManager) {
        this.permissionMapper = permissionMapper;
        this.userRoleMapper = userRoleMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.roleMapper = roleMapper;
        this.memberRoleContributors = memberRoleContributors == null ? List.of() : List.copyOf(memberRoleContributors);
        this.cacheManager = cacheManager;
    }

    public Set<Long> getEffectivePermissionIds(Long tenantId, Long userId, Long memberId) {
        if (tenantId == null || userId == null || memberId == null) {
            return Collections.emptySet();
        }
        LocalDate today = LocalDate.now();
        EffectivePermissionKey key = new EffectivePermissionKey(
                tenantId, userId, today, contributorDiscriminator(memberId, tenantId));
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
        String normalized = normalizePermissionCode(permissionCode);
        if (freshReadValues.get() != null) {
            return getOrLoad(PERMISSION_CATALOG_CACHE, new PermissionDefinitionKey(tenantId, normalized),
                    () -> permissionMapper.findByTenantIdAndCode(tenantId, normalized));
        }
        return getPermissionCatalog(tenantId).definitionsByNormalizedCode().get(normalized);
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

    /**
     * Resolve the effective permission IDs for one role, including the implicit tenant-member
     * baseline used by normal runtime authorization.
     *
     * <p>This projection is intentionally role-only: it does not create a synthetic user or alter
     * the request identity. Callers must tenant-validate the role before invoking it.
     */
    public Set<Long> getEffectiveRolePermissionIds(Long tenantId, Long roleId) {
        if (tenantId == null || roleId == null) {
            return Collections.emptySet();
        }
        LocalDate today = LocalDate.now();
        LinkedHashSet<Long> effective = new LinkedHashSet<>(
                getRolePermissionIds(tenantId, roleId, today));
        Long baselineRoleId = getBaselineRoleId(tenantId);
        if (baselineRoleId != null && !baselineRoleId.equals(roleId)) {
            effective.addAll(getRolePermissionIds(tenantId, baselineRoleId, today));
        }
        return Collections.unmodifiableSet(effective);
    }

    public void evictUser(Long tenantId, Long userId) {
        if (tenantId == null || userId == null) {
            return;
        }
        cache(USER_ROLE_CACHE).evict(new UserRoleKey(tenantId, userId, LocalDate.now()));
        // Contributor discriminators are member-source dependent and cannot be reconstructed from
        // a user id alone. This cache is intentionally small and short-lived.
        cache(EFFECTIVE_PERMISSION_CACHE).clear();
    }

    public void evictRole(Long tenantId, Long roleId) {
        if (tenantId != null && roleId != null) {
            cache(ROLE_PERMISSION_CACHE)
                    .evict(new RolePermissionKey(tenantId, roleId, LocalDate.now()));
        }
        if (tenantId != null) {
            // Role creation/deletion can change the implicit tenant_member
            // baseline. Cached negative lookups must not survive that change,
            // otherwise a tenant initialized after its first permission check
            // remains permanently role-less until the cache TTL expires.
            cache(BASELINE_ROLE_CACHE).evict(new BaselineRoleKey(tenantId));
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
        LocalDate today = LocalDate.now();
        UserRoleKey key = new UserRoleKey(tenantId, userId, today);
        List<Long> directRoleIds = getOrLoad(USER_ROLE_CACHE, key, () -> {
            List<UserRole> rows = userRoleMapper.findEffectiveByMemberIdAndTenantId(memberId, tenantId, today);
            if (rows == null || rows.isEmpty()) {
                return List.of();
            }
            return rows.stream()
                        .map(UserRole::getRoleId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList();
        });
        LinkedHashSet<Long> roleIds = new LinkedHashSet<>(directRoleIds);
        for (MemberRoleContributor contributor : memberRoleContributors) {
            List<Long> contributed = contributor.findRoleIds(memberId, tenantId);
            if (contributed != null) {
                contributed.stream().filter(Objects::nonNull).forEach(roleIds::add);
            }
        }
        return roleIds.isEmpty() ? List.of() : List.copyOf(roleIds);
    }

    private String contributorDiscriminator(Long memberId, Long tenantId) {
        if (memberRoleContributors.isEmpty()) {
            return "";
        }
        return memberRoleContributors.stream()
                .map(contributor -> Objects.toString(contributor.cacheDiscriminator(memberId, tenantId), ""))
                .reduce((left, right) -> left + "|" + right)
                .orElse("");
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

    /**
     * The catalog is tenant-scoped: permission definitions are owned by the tenant whose
     * import/publish created them, and resolving a code to another tenant's newest row
     * silently invalidates that tenant's role bindings.
     */
    private PermissionCatalog getPermissionCatalog(Long tenantId) {
        return getOrLoad(PERMISSION_CATALOG_CACHE, new PermissionCatalogKey(tenantId),
                () -> catalog(permissionMapper.findResolvableDefinitions(tenantId)));
    }

    private PermissionCatalog getDomainPermissionCatalog(Long tenantId) {
        if (freshReadValues.get() == null) return getPermissionCatalog(tenantId);
        return getOrLoad(PERMISSION_CATALOG_CACHE, new DomainCatalogKey(tenantId),
                () -> catalog(permissionMapper.findResolvableDomainDefinitions(tenantId)));
    }

    private static PermissionCatalog catalog(List<Permission> permissions) {
        if (permissions == null || permissions.isEmpty()) return new PermissionCatalog(Map.of(), Map.of());
        Map<String, Permission> definitionsByCode = new LinkedHashMap<>();
        Map<Long, String> codesById = new LinkedHashMap<>();
        for (Permission permission : permissions) {
            if (permission == null || permission.getId() == null || permission.getCode() == null) continue;
            // Both queries preserve the original newest-first resolution, including archived rows.
            definitionsByCode.putIfAbsent(permission.getCode().toLowerCase(Locale.ROOT), permission);
            codesById.putIfAbsent(permission.getId(), permission.getCode());
        }
        return new PermissionCatalog(Collections.unmodifiableMap(definitionsByCode), Collections.unmodifiableMap(codesById));
    }

    public List<String> domainPermissionAlternatives(Long tenantId, String code) {
        var required = resolvePermissionDefinition(tenantId, code);
        if (com.auraboot.framework.permission.service.DomainPermissionPolicy.models(required).isEmpty()) return List.of(code);
        var levels = List.of("read", "write", "manage");
        return getDomainPermissionCatalog(tenantId).definitionsByNormalizedCode().values().stream()
                .filter(permission -> !com.auraboot.framework.permission.service.DomainPermissionPolicy.models(permission).isEmpty()
                    && java.util.Objects.equals(required.getResourceCode(), permission.getResourceCode())
                    && levels.indexOf(permission.getAction()) >= levels.indexOf(required.getAction()))
                .map(Permission::getCode).distinct().sorted().toList();
    }

    public boolean hasDomainModel(Long tenantId, String model) {
        return getDomainPermissionCatalog(tenantId).definitionsByNormalizedCode().values().stream()
                .anyMatch(permission -> com.auraboot.framework.permission.service.DomainPermissionPolicy.owns(permission, model));
    }

    /** Published domain policies replace model-operation grants only for explicitly owned resources. */
    public List<String> resourcePermissionCodes(Long tenantId, String model, String action) {
        var policies = getDomainPermissionCatalog(tenantId).definitionsByNormalizedCode().values().stream()
                .filter(permission -> com.auraboot.framework.permission.service.DomainPermissionPolicy.owns(permission, model)).toList();
        if (policies.isEmpty() || "read".equals(action) && policies.stream().anyMatch(permission ->
                permission.getExtension() instanceof Map<?,?> extension
                && extension.get("dataControlledReads") instanceof List<?> protectedModels && protectedModels.contains(model)))
            return com.auraboot.framework.permission.engine.evaluator.PermissionCodeCandidates.forResourceAction(model, action);
        var effects = policies.stream().filter(permission ->
                com.auraboot.framework.permission.service.DomainPermissionPolicy.isCurrentCommandMutation(permission, model, action))
                .map(Permission::getCode).distinct().toList();
        if (!effects.isEmpty()) return effects;
        var allowed = policies.stream().filter(permission -> com.auraboot.framework.permission.service.DomainPermissionPolicy.permits(permission, model, action)).toList();
        var levels = List.of("read", "write", "manage");
        Map<String,Integer> minimum = new java.util.HashMap<>();
        allowed.forEach(permission -> minimum.merge(permission.getResourceCode(), levels.indexOf(permission.getAction()), Math::min));
        // Resolve inheritance from one required level, so a higher level cannot bypass an explicit
        // deny of the required operation by being evaluated as an independent alternative.
        return allowed.stream().filter(permission -> minimum.get(permission.getResourceCode()) == levels.indexOf(permission.getAction()))
                .map(Permission::getCode).distinct().sorted().toList();
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

    @SuppressWarnings("unchecked")
    private <T> T getOrLoad(String cacheName, Object key, Supplier<T> loader) {
        Map<FreshLookupKey, Object> values = freshReadValues.get();
        if (values != null) {
            FreshLookupKey lookup = new FreshLookupKey(cacheName, key);
            if (values.containsKey(lookup)) return (T) values.get(lookup);
            T loaded = loader.get();
            values.put(lookup, loaded);
            return loaded;
        }
        return cache(cacheName).get(key, loader::get);
    }

    private record FreshLookupKey(String cacheName, Object key) {
    }

    private record PermissionDefinitionKey(Long tenantId, String normalizedCode) {
    }

    private record DomainCatalogKey(Long tenantId) {
    }

    private record PermissionCatalogKey(Long tenantId) {
    }

    private record PermissionCatalog(
            Map<String, Permission> definitionsByNormalizedCode,
            Map<Long, String> codesById) {
    }

    private record UserRoleKey(Long tenantId, Long userId, LocalDate effectiveDate) {
    }

    private record RolePermissionKey(Long tenantId, Long roleId, LocalDate effectiveDate) {
    }

    private record BaselineRoleKey(Long tenantId) {
    }

    private record EffectivePermissionKey(
            Long tenantId, Long userId, LocalDate effectiveDate, String contributorDiscriminator) {
    }

    private record BaselineRoleLookup(Long roleId) {
    }
}
