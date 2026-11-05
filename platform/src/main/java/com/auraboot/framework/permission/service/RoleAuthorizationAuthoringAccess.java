package com.auraboot.framework.permission.service;

import com.auraboot.framework.application.security.AdminRoleChecker;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.enums.RoleCodes;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** Application authoring boundary shared by HTTP and direct role-duty callers. */
@Component
@RequiredArgsConstructor
public class RoleAuthorizationAuthoringAccess {
    private final AdminRoleChecker administrators;
    private final UserPermissionService permissions;
    private final RoleMapper roles;

    /** Call inside the tenant authoring lock, before resolving or changing the requested grants. */
    public void requireWrite() {
        Long tenant = MetaContext.exists() ? MetaContext.getCurrentTenantId() : null;
        Long user = MetaContext.exists() ? MetaContext.getCurrentUserId() : null;
        if (com.auraboot.framework.application.security.MachineDataAccessContext.isActive()
                || com.auraboot.framework.openplatform.service.InstalledIntegrationExecution.active()
                || tenant == null || user == null || MetaContext.getCurrentMemberId() == null
                || !administrators.hasRole(tenant, user, RoleCodes.TENANT_ADMIN)
                || !permissions.hasPermission(user, MetaPermission.ROLE_MANAGE)) {
            throw new AccessDeniedException("Current tenant administrator and role authoring permission required");
        }
    }

    /** Numeric role IDs are internal identities, never an exemption from tenant ownership. */
    public void requireTarget(Long roleId) {
        var role = roleId == null ? null : roles.selectById(roleId);
        if (role == null || Boolean.TRUE.equals(role.getDeletedFlag()) || !java.util.Objects.equals(role.getTenantId(), MetaContext.getCurrentTenantId())) {
            throw new AccessDeniedException("Role authoring target is unavailable in this tenant");
        }
    }
}
