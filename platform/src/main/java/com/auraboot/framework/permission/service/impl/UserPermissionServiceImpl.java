package com.auraboot.framework.permission.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.cache.MetaCacheKeyGenerator;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.entity.UserRole;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.auraboot.framework.rbac.mapper.UserRoleMapper;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * User Permission Service Implementation
 * 
 * <p>Implements L1 cache for user permissions with automatic eviction.
 * 
 * <p>Cache Hierarchy:
 * <pre>
 * L1: user-permissions:{userId} (TTL: 30min)
 *     ↓
 * L2: subject-evaluation:{subjectType}:{subjectId}:{userId} (TTL: 15min)
 *     ↓
 * L3: subject-declarations:{subjectType}:{subjectId} (TTL: 60min)
 * </pre>
 * 
 * <p>Eviction Strategy:
 * <ul>
 *   <li>Role-Permission changed → Evict all users of that role</li>
 *   <li>User-Role changed → Evict that user</li>
 *   <li>Release rollback → Clear all caches</li>
 * </ul>
 * 
 * @author AuraBoot Platform
 * @since V4
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserPermissionServiceImpl implements UserPermissionService {
    
    private final RolePermissionMapper rolePermissionMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final CacheManager cacheManager;
    private final com.auraboot.framework.permission.mapper.PermissionMapper permissionMapper;

    private static final String CACHE_NAME = "user-permissions";

    /**
     * L1 baseline role code (DDR-2026-06-30). Every tenant member implicitly inherits this role's
     * permissions during resolution — with NO {@code ab_user_role} row — so render-support reads are
     * guaranteed for all members and cannot be dropped by any provisioning path.
     */
    private static final String BASELINE_ROLE_CODE = "tenant_member";

    /**
     * Local cache for permissionCode → permissionId mapping.
     * Permission definitions rarely change, so a simple ConcurrentHashMap suffices.
     * Cleared when permissions are modified (e.g., plugin import, permission CRUD).
     */
    private final ConcurrentHashMap<String, Long> permissionCodeCache = new ConcurrentHashMap<>();
    
    /**
     * Get user's permission IDs (with cache)
     * 
     * <p>Cache Strategy:
     * <ul>
     *   <li>Cache Name: user-permissions</li>
     *   <li>Cache Key: userId</li>
     *   <li>TTL: 30 minutes (configured in application.yml)</li>
     * </ul>
     * 
     * <p>Query Logic:
     * <ol>
     *   <li>Query all roles assigned to the user</li>
     *   <li>Query all permissions bound to those roles</li>
     *   <li>Filter by GRANT type and effective date</li>
     *   <li>Return distinct permission IDs</li>
     * </ol>
     * 
     * @param userId User ID
     * @return Set of permission IDs
     */
    @Override
    @Cacheable(value = CACHE_NAME, key = "T(com.auraboot.framework.meta.cache.MetaCacheKeyGenerator).getTenantContextSuffix() + ':' + #userId",
        unless = "#result.isEmpty()")
    public Set<Long> getUserPermissionIds(Long userId) {
        log.debug("Loading user permissions from database: userId={}", userId);

        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null) {
            log.warn("Tenant context missing when loading user permissions: userId={}", userId);
            return Collections.emptySet();
        }

        // Phase 2: ab_user_role uses member_id. Get memberId from MetaContext.
        Long memberId = MetaContext.getCurrentMemberId();
        if (memberId == null) {
            log.warn("MemberId not available in MetaContext for userId={}, cannot resolve permissions", userId);
            return Collections.emptySet();
        }

        // 1. Permissions from the member's explicitly-assigned roles in this tenant.
        List<UserRole> userRoles = userRoleMapper.findByMemberIdAndTenantId(memberId, tenantId);
        List<Long> roleIds = userRoles.stream()
            .map(UserRole::getRoleId)
            .filter(Objects::nonNull)
            .distinct()
            .collect(Collectors.toList());

        Set<Long> permissionIds = new HashSet<>();
        if (!roleIds.isEmpty()) {
            permissionIds.addAll(rolePermissionMapper.findPermissionIdsByRoles(roleIds));
        }

        // 2. L1 implicit baseline (DDR-2026-06-30): every tenant member additionally inherits the
        // tenant_member baseline role's permissions — WITHOUT an ab_user_role row — so render-support
        // reads are guaranteed for every member and cannot be dropped by any provisioning path (the
        // original incident: a member provisioned WITH business roles but WITHOUT the baseline could
        // not render pages). The baseline role is resolved by (tenant, code); if it is not yet seeded
        // for this tenant it simply contributes nothing (safe degradation). NOTE: because the baseline
        // is applied here (no member rows), a change to its grants must clear the whole cache — see
        // evictRoleUsers, which no longer early-returns on an empty member set.
        Role baseline = roleMapper.findByTenantIdAndCode(tenantId, BASELINE_ROLE_CODE);
        if (baseline != null && baseline.getId() != null) {
            permissionIds.addAll(rolePermissionMapper.findPermissionIdsByRoles(List.of(baseline.getId())));
        }

        log.debug("Resolved {} permissions for memberId={}, userId={} ({} own-role ids, baseline={})",
            permissionIds.size(), memberId, userId, roleIds.size(), baseline != null);

        return permissionIds;
    }

    @Override
    public Set<String> getUserPermissionCodes(Long userId) {
        if (userId == null) {
            return Collections.emptySet();
        }
        Set<Long> permissionIds = getUserPermissionIds(userId);
        if (permissionIds == null || permissionIds.isEmpty()) {
            return Collections.emptySet();
        }
        return permissionMapper.findByIds(new ArrayList<>(permissionIds)).stream()
                .map(Permission::getCode)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
    
    /**
     * Evict user's permission cache
     * 
     * <p>Triggers:
     * <ul>
     *   <li>User-Role binding changed</li>
     *   <li>User deleted</li>
     * </ul>
     * 
     * @param userId User ID
     */
    @Override
    public void evictUserPermissions(Long userId) {
        Cache cache = cacheManager.getCache(CACHE_NAME);
        if (cache != null) {
            String cacheKey = MetaCacheKeyGenerator.getTenantContextSuffix() + ":" + userId;
            cache.evict(cacheKey);
            log.info("Evicted user permissions cache: userId={}, key={}", userId, cacheKey);
        } else {
            log.warn("Cache not found: {}", CACHE_NAME);
        }
        // Also clear permissionCode→ID cache since permission data may have changed
        clearPermissionCodeCache();
    }
    
    /**
     * Evict all users' permission cache for a specific role
     * 
     * <p>Triggers:
     * <ul>
     *   <li>Role-Permission binding changed</li>
     *   <li>Role deleted</li>
     * </ul>
     * 
     * <p>Implementation:
     * <ol>
     *   <li>Query all users assigned to the role</li>
     *   <li>Evict each user's permission cache</li>
     * </ol>
     * 
     * @param roleId Role ID
     */
    @Override
    public void evictRoleUsers(Long roleId) {
        log.info("Evicting permissions cache for all users of role: roleId={}", roleId);
        
        // 1. Query members assigned to the role (informational only).
        List<Long> memberIds = userRoleMapper.findMemberIdsByRoleId(roleId);

        // Do NOT early-return when memberIds is empty: the L1 tenant_member baseline role is applied
        // implicitly during resolution (no ab_user_role rows), so a baseline grant change has zero
        // member rows yet affects every member's cached permissions — we must still clear.
        log.info("Evicting permission caches for role change: roleId={}, directMemberRows={}",
            roleId, memberIds.size());

        // 2. Evict the permission cache
        // Note: cache key still uses userId for backward compatibility with hasPermission(userId, ...)
        // The cache eviction here is best-effort; a full cache clear may be needed
        Cache cache = cacheManager.getCache(CACHE_NAME);
        if (cache != null) {
            // Clear all entries since we can't easily map memberId back to userId for cache keys
            cache.clear();
            log.info("Cleared all permission caches for role change: roleId={}", roleId);
        } else {
            log.warn("Cache not found: {}", CACHE_NAME);
        }
    }
    
    /**
     * Batch get users' permission IDs
     * 
     * <p>Optimization for batch operations.
     * 
     * <p>Implementation:
     * <ul>
     *   <li>Check cache first for each user</li>
     *   <li>Load from database for cache misses</li>
     *   <li>Update cache for loaded users</li>
     * </ul>
     * 
     * @param userIds List of user IDs
     * @return Map of userId -> Set of permission IDs
     */
    @Override
    public Map<Long, Set<Long>> batchGetUserPermissionIds(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        
        log.debug("Batch loading user permissions: userCount={}", userIds.size());
        
        Map<Long, Set<Long>> result = new HashMap<>();
        
        // Load permissions for each user (cache will be used automatically)
        for (Long userId : userIds) {
            Set<Long> permissionIds = getUserPermissionIds(userId);
            result.put(userId, permissionIds);
        }
        
        log.debug("Batch loaded {} users' permissions", result.size());
        
        return result;
    }
    
    /**
     * Check if user has specific permission by permission code
     * 
     * <p>This method is used by {@link com.auraboot.framework.permission.interceptor.PermissionInterceptor}
     * to check if the current user has the required permission.
     * 
     * <p>Implementation:
     * <ol>
     *   <li>Query permission by code</li>
     *   <li>Check if user has the permission ID</li>
     * </ol>
     * 
     * @param userId User ID
     * @param permissionCode Permission code (e.g., "model.model.manage")
     * @return true if user has the permission
     */
    @Override
    @Observed(name = "permission.check", contextualName = "permission-check")
    public boolean hasPermission(Long userId, String permissionCode) {
        if (userId == null || permissionCode == null || permissionCode.isEmpty()) {
            log.warn("Invalid parameters: userId={}, permissionCode={}", userId, permissionCode);
            return false;
        }

        log.debug("Checking permission by code: userId={}, permissionCode={}",
            userId, permissionCode);

        Long permissionId = resolvePermissionId(permissionCode);
        if (permissionId == null) {
            log.debug("Permission check result: userId={}, permissionCode={}, hasPermission=false (unregistered code)",
                userId, permissionCode);
            return false;
        }

        boolean granted = hasPermission(userId, permissionId);
        log.debug("Permission check result: userId={}, permissionCode={}, hasPermission={}",
            userId, permissionCode, granted);
        return granted;
    }

    /**
     * Clear the permissionCode → permissionId cache.
     * Should be called when permission definitions change (plugin import, permission CRUD).
     */
    public void clearPermissionCodeCache() {
        int size = permissionCodeCache.size();
        permissionCodeCache.clear();
        log.info("Cleared permission code cache: {} entries removed", size);
    }

    private String buildPermissionCodeCacheKey(String permissionCode) {
        return MetaCacheKeyGenerator.getTenantContextSuffix() + ":" + permissionCode;
    }

    private Long resolvePermissionId(String permissionCode) {
        String permissionCacheKey = buildPermissionCodeCacheKey(permissionCode);
        Long permissionId = permissionCodeCache.get(permissionCacheKey);
        if (permissionId != null) {
            return permissionId;
        }

        com.auraboot.framework.permission.entity.Permission permission =
            permissionMapper.findByCode(permissionCode);
        if (permission == null) {
            log.debug("Permission definition not found for candidate code: {}", permissionCode);
            return null;
        }

        permissionId = permission.getId();
        permissionCodeCache.put(permissionCacheKey, permissionId);
        log.debug("Cached permission mapping: {}={}", permissionCacheKey, permissionId);
        return permissionId;
    }
    
    /**
     * Check if user has specific permission
     * 
     * <p>Convenience method for single permission check.
     * 
     * @param userId User ID
     * @param permissionId Permission ID
     * @return true if user has the permission
     */
    @Override
    public boolean hasPermission(Long userId, Long permissionId) {
        if (userId == null || permissionId == null) {
            return false;
        }
        
        Set<Long> userPermissions = getUserPermissionIds(userId);
        return userPermissions.contains(permissionId);
    }
    
    /**
     * Check if user has all specified permissions (AND logic)
     * 
     * @param userId User ID
     * @param permissionIds List of permission IDs
     * @return true if user has all permissions
     */
    @Override
    public boolean hasAllPermissions(Long userId, List<Long> permissionIds) {
        if (userId == null || permissionIds == null || permissionIds.isEmpty()) {
            return false;
        }
        
        Set<Long> userPermissions = getUserPermissionIds(userId);
        return userPermissions.containsAll(permissionIds);
    }
    
    /**
     * Check if user has any of specified permissions (OR logic)
     * 
     * @param userId User ID
     * @param permissionIds List of permission IDs
     * @return true if user has at least one permission
     */
    @Override
    public boolean hasAnyPermission(Long userId, List<Long> permissionIds) {
        if (userId == null || permissionIds == null || permissionIds.isEmpty()) {
            return false;
        }
        
        Set<Long> userPermissions = getUserPermissionIds(userId);
        
        for (Long permissionId : permissionIds) {
            if (userPermissions.contains(permissionId)) {
                return true;
            }
        }
        
        return false;
    }
}
