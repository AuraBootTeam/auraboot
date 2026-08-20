package com.auraboot.framework.tenant.offboarding;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.permission.enums.RoleCodes;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.entity.UserRole;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import com.auraboot.framework.rbac.mapper.UserRoleMapper;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.dao.mapper.TenantMemberMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantAdminContinuityGuardTest {

    @Mock private TenantMemberMapper memberMapper;
    @Mock private RoleMapper roleMapper;
    @Mock private UserRoleMapper userRoleMapper;
    private TenantAdminContinuityGuard guard;
    private TenantMember member;
    private Role admin;

    @BeforeEach
    void setUp() {
        guard = new TenantAdminContinuityGuard(memberMapper, roleMapper, userRoleMapper);
        member = new TenantMember();
        member.setId(1L);
        member.setTenantId(7L);
        member.setStatus("active");
        admin = new Role();
        admin.setId(70L);
        admin.setCode(RoleCodes.TENANT_ADMIN);
    }

    @Test
    void rejectsOffboardingTheLastActiveAdmin() {
        when(roleMapper.findByTenantIdAndCode(7L, RoleCodes.TENANT_ADMIN)).thenReturn(admin);
        UserRole assignment = new UserRole();
        assignment.setStatus("active");
        when(userRoleMapper.findByMemberIdAndRoleIdAndTenantId(1L, 70L, 7L)).thenReturn(assignment);
        when(memberMapper.countOtherActiveMembersWithRole(7L, 1L, 70L)).thenReturn(0L);

        assertThatThrownBy(() -> guard.assertMemberCanBeOffboarded(member))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("last active tenant administrator");
    }

    @Test
    void allowsOffboardingWhenAnotherActiveAdminExists() {
        when(roleMapper.findByTenantIdAndCode(7L, RoleCodes.TENANT_ADMIN)).thenReturn(admin);
        UserRole assignment = new UserRole();
        assignment.setStatus("active");
        when(userRoleMapper.findByMemberIdAndRoleIdAndTenantId(1L, 70L, 7L)).thenReturn(assignment);
        when(memberMapper.countOtherActiveMembersWithRole(7L, 1L, 70L)).thenReturn(1L);

        assertThatCode(() -> guard.assertMemberCanBeOffboarded(member)).doesNotThrowAnyException();
    }

    @Test
    void rejectsRemovingTheLastAdminRole() {
        when(roleMapper.findByTenantIdAndCode(7L, RoleCodes.TENANT_ADMIN)).thenReturn(admin);
        when(memberMapper.findByTenantIdAndId(7L, 1L)).thenReturn(member);
        when(memberMapper.countOtherActiveMembersWithRole(7L, 1L, 70L)).thenReturn(0L);

        assertThatThrownBy(() -> guard.assertRolesCanBeRemoved(1L, 7L, List.of(70L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("last active tenant administrator");
    }

    @Test
    void ignoresRemovalOfNonAdminRole() {
        when(roleMapper.findByTenantIdAndCode(7L, RoleCodes.TENANT_ADMIN)).thenReturn(admin);
        assertThatCode(() -> guard.assertRolesCanBeRemoved(1L, 7L, List.of(71L)))
                .doesNotThrowAnyException();
    }

    @Test
    void ignoresAnAlreadyInactiveAdminAssignmentDuringMemberOffboarding() {
        when(roleMapper.findByTenantIdAndCode(7L, RoleCodes.TENANT_ADMIN)).thenReturn(admin);
        UserRole assignment = new UserRole();
        assignment.setStatus("inactive");
        when(userRoleMapper.findByMemberIdAndRoleIdAndTenantId(1L, 70L, 7L)).thenReturn(assignment);

        assertThatCode(() -> guard.assertMemberCanBeOffboarded(member)).doesNotThrowAnyException();
    }
}
