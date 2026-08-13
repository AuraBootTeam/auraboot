package com.auraboot.framework.tenant.offboarding;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.permission.enums.RoleCodes;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import com.auraboot.framework.rbac.mapper.UserRoleMapper;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.dao.mapper.TenantMemberMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;

/** Prevents member or role lifecycle operations from leaving a tenant without an active admin. */
@Service
@RequiredArgsConstructor
public class TenantAdminContinuityGuard {

    private final TenantMemberMapper tenantMemberMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;

    public void assertMemberCanBeOffboarded(TenantMember member) {
        Role tenantAdmin = roleMapper.findByTenantIdAndCode(member.getTenantId(), RoleCodes.TENANT_ADMIN);
        if (tenantAdmin == null) {
            return;
        }
        var assignment = userRoleMapper.findByMemberIdAndRoleIdAndTenantId(
                member.getId(), tenantAdmin.getId(), member.getTenantId());
        if (assignment == null || !"active".equalsIgnoreCase(assignment.getStatus())) {
            return;
        }
        assertAnotherActiveAdmin(member, tenantAdmin);
    }

    public void assertRolesCanBeRemoved(Long memberId, Long tenantId, Collection<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return;
        }
        Role tenantAdmin = roleMapper.findByTenantIdAndCode(tenantId, RoleCodes.TENANT_ADMIN);
        if (tenantAdmin == null || !roleIds.contains(tenantAdmin.getId())) {
            return;
        }
        TenantMember member = tenantMemberMapper.findByTenantIdAndId(tenantId, memberId);
        if (member != null && "active".equalsIgnoreCase(member.getStatus())) {
            assertAnotherActiveAdmin(member, tenantAdmin);
        }
    }

    private void assertAnotherActiveAdmin(TenantMember member, Role tenantAdmin) {
        long remainingAdmins = tenantMemberMapper.countOtherActiveMembersWithRole(
                member.getTenantId(), member.getId(), tenantAdmin.getId());
        if (remainingAdmins == 0) {
            throw new BusinessException(ResponseCode.BadParam,
                    "The last active tenant administrator cannot be removed or deactivated");
        }
    }
}
