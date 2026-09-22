package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.plugin.extension.TenantRoleAssignmentAccessor;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.entity.UserRole;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;

import java.util.List;
import java.time.LocalDate;

/** One-command tenant binding; callers cannot select another tenant or member ID. */
public final class TenantRoleAssignmentAccessorImpl implements TenantRoleAssignmentAccessor {
    private final Long tenantId;
    private final Long operatorId;
    private final IdentityDirectoryAccessorImpl directory;
    private final RoleMapper roleMapper;
    private final UserRoleService userRoleService;
    private final TenantMemberService memberService;
    private final UserService userService;

    public TenantRoleAssignmentAccessorImpl(Long tenantId, Long operatorId,
            IdentityDirectoryAccessorImpl directory, RoleMapper roleMapper,
            UserRoleService userRoleService, TenantMemberService memberService,
            UserService userService) {
        this.tenantId = tenantId;
        this.operatorId = operatorId;
        this.directory = directory;
        this.roleMapper = roleMapper;
        this.userRoleService = userRoleService;
        this.memberService = memberService;
        this.userService = userService;
    }

    @Override public List<String> userPidsWithRole(String roleCode) {
        role(roleCode);
        return directory.userPidsForRole(tenantId, roleCode);
    }

    @Override public void setRole(String userPid, String roleCode, boolean assigned) {
        Role role = role(roleCode);
        User user = userService.findByPid(userPid);
        if (user == null) throw new IllegalStateException("Teacher user not found");
        TenantMember member = memberService.findByTenantIdAndUserId(tenantId, user.getId());
        if (member == null || member.getPid() == null) {
            throw new IllegalStateException("Teacher is not a member of the command tenant");
        }
        UserRole existing = userRoleService.findByMemberIdAndRoleIdAndTenantId(
                member.getId(), role.getId(), tenantId);
        LocalDate today = LocalDate.now();
        boolean current = existing != null && "active".equals(existing.getStatus())
                && (existing.getEffectiveDate() == null || !existing.getEffectiveDate().isAfter(today))
                && (existing.getExpiryDate() == null || !existing.getExpiryDate().isBefore(today));
        if (assigned && current) return;
        if (!assigned && existing == null) return;
        if (assigned && existing != null) {
            userRoleService.removeRolesFromMemberByRolePids(member.getPid(), List.of(role.getPid()), tenantId);
        }
        boolean ok = assigned
                ? userRoleService.assignRolesToMemberByRoleCodes(member.getPid(), List.of(roleCode), tenantId, operatorId)
                : userRoleService.removeRolesFromMemberByRolePids(member.getPid(), List.of(role.getPid()), tenantId);
        if (!ok) throw new IllegalStateException("Teacher role synchronization failed");
    }

    private Role role(String code) {
        if (tenantId == null || code == null || code.isBlank()) {
            throw new IllegalArgumentException("Tenant and role code are required");
        }
        Role role = roleMapper.findByTenantIdAndCode(tenantId, code);
        if (role == null) throw new IllegalStateException("Role is not installed in the command tenant: " + code);
        return role;
    }
}
