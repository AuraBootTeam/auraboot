package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.LogSanitizer;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.permission.dto.PermissionDTO;
import com.auraboot.framework.permission.service.PermissionService;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.entity.RolePermission;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.auraboot.framework.common.constant.StatusConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Materializes plugin-imported permission codes onto the roles holding the tenant bootstrap
 * "*" wildcard grant. List/join consumers (sidebar menus, permission matrices) resolve
 * concrete role_permission rows only — they cannot resolve the wildcard — so every code a
 * plugin registers after the bootstrap must be materialized onto the wildcard roles at
 * import time, or the fresh stack's administrator runs on an empty effective set.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImportedPermissionWildcardBinder {

    private final RolePermissionMapper rolePermissionMapper;
    private final PermissionService permissionService;
    private final UserPermissionService userPermissionService;

    public void bind(Collection<String> codes, String pluginId) {
        if (codes == null || codes.isEmpty()) return;
        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null) {
            log.warn("No tenant context, skip wildcard permission binding: plugin={}", LogSanitizer.safe(pluginId));
            return;
        }
        List<Role> wildcardRoles = rolePermissionMapper.findWildcardRoles(tenantId);
        if (wildcardRoles.isEmpty()) {
            log.debug("No wildcard roles in tenant {}, skip wildcard permission binding: plugin={}", tenantId, LogSanitizer.safe(pluginId));
            return;
        }

        int bound = 0;
        for (String code : codes) {
            if (code == null || code.isEmpty()) continue;
            PermissionDTO permissionDTO = permissionService.findByCode(code);
            if (permissionDTO == null) {
                // Owned by a plugin imported later; that import materializes it.
                log.debug("Skip wildcard binding, code not registered yet: code={}, plugin={}",
                        LogSanitizer.safe(code), LogSanitizer.safe(pluginId));
                continue;
            }
            for (Role role : wildcardRoles) {
                Set<Long> boundIds = permissionService.findRolePermissions(role.getId()).stream()
                        .map(PermissionDTO::getId)
                        .filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.toSet());
                if (!boundIds.add(permissionDTO.getId())) {
                    continue;
                }
                RolePermission binding = new RolePermission();
                binding.setPid(UniqueIdGenerator.generate());
                binding.setTenantId(tenantId);
                binding.setRoleId(role.getId());
                binding.setPermissionId(permissionDTO.getId());
                binding.setGrantType(StatusConstants.GRANT);
                binding.setPriority(0);
                binding.setStatus(StatusConstants.ACTIVE);
                binding.setDeletedFlag(false);
                binding.setCreatedAt(Instant.now());
                binding.setUpdatedAt(Instant.now());
                rolePermissionMapper.insert(binding);
                bound++;
            }
        }
        if (bound > 0) {
            log.info("Materialized {} wildcard role grant(s) for imported codes, plugin {}", bound, LogSanitizer.safe(pluginId));
            userPermissionService.evictPermissionDefinitions(tenantId);
            for (Role role : wildcardRoles) {
                userPermissionService.evictRoleUsers(tenantId, role.getId());
            }
        }
    }
}
