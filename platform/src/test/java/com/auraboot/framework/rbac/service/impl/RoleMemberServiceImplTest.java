package com.auraboot.framework.rbac.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.organization.service.OrganizationService;
import com.auraboot.framework.rbac.dto.RoleMemberDTO;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.entity.UserRole;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RoleMemberServiceImpl")
class RoleMemberServiceImplTest {

    @Mock private UserRoleService userRoleService;
    @Mock private RoleService roleService;
    @Mock private TenantMemberService tenantMemberService;
    @Mock private UserService userService;
    @Mock private OrganizationService organizationService;

    private RoleMemberServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RoleMemberServiceImpl(userRoleService, roleService, tenantMemberService, userService, organizationService);
        MetaContext.setContext(10L, 1L, "u-1", "user");
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    private TenantMember member(Long id, Long userId, String pid) {
        TenantMember m = new TenantMember();
        m.setId(id);
        m.setPid(pid);
        m.setUserId(userId);
        m.setTenantId(10L);
        m.setDeletedFlag(false);
        return m;
    }

    private User user(Long id, String email, String name) {
        User u = new User();
        u.setId(id);
        u.setEmail(email);
        u.setNickName(name);
        return u;
    }

    private UserRole ur(Long memberId, Long roleId) {
        UserRole r = new UserRole();
        r.setMemberId(memberId);
        r.setRoleId(roleId);
        r.setTenantId(10L);
        return r;
    }

    @Test
    @DisplayName("getMembers returns empty when no UserRole for role")
    void getMembersEmpty() {
        when(userRoleService.list(any(QueryWrapper.class))).thenReturn(List.of());
        PaginationResult<RoleMemberDTO> out = service.getMembers(100L, 1, 10);
        assertEquals(0, out.getRecords().size());
    }

    @Test
    @DisplayName("getMembers paginates and enriches with user and employee data")
    void getMembersHappy() {
        when(userRoleService.list(any(QueryWrapper.class))).thenReturn(List.of(ur(1L, 100L)));
        TenantMember m = member(1L, 11L, "mp-1");
        when(tenantMemberService.listByIds(any())).thenReturn(List.of(m));
        when(userService.findByUserIds(any())).thenReturn(List.of(user(11L, "a@b.com", "Nick")));
        when(organizationService.getEmployeesByMemberPids(anyCollection()))
                .thenReturn(Map.of("mp-1", Map.of(
                        "org_emp_name", "EmpName",
                        "org_emp_dept_id", 99L,
                        "org_emp_dept_id_display", "Sales",
                        "org_emp_position_id", 88L)));

        PaginationResult<RoleMemberDTO> out = service.getMembers(100L, 1, 10);
        assertEquals(1, out.getRecords().size());
        RoleMemberDTO dto = out.getRecords().get(0);
        assertEquals("EmpName", dto.userName());
        assertEquals("Sales", dto.departmentName());
        assertEquals("88", dto.positionName());
    }

    @Test
    @DisplayName("getMembers returns empty when fromIndex beyond total")
    void getMembersBeyondPage() {
        when(userRoleService.list(any(QueryWrapper.class))).thenReturn(List.of(ur(1L, 100L)));
        when(tenantMemberService.listByIds(any())).thenReturn(List.of(member(1L, 11L, "mp-1")));
        PaginationResult<RoleMemberDTO> out = service.getMembers(100L, 5, 10);
        assertEquals(0, out.getRecords().size());
    }

    @Test
    @DisplayName("addMembers no-op for empty list")
    void addMembersEmpty() {
        service.addMembers(100L, List.of());
        verify(userRoleService, never()).assignRolesToMember(any(), any(), any(), any());
    }

    @Test
    @DisplayName("addMembers throws when role missing")
    void addMembersRoleMissing() {
        when(roleService.getById(100L)).thenReturn(null);
        assertThrows(BusinessException.class, () -> service.addMembers(100L, List.of("mp-1")));
    }

    @Test
    @DisplayName("addMembers skips invalid pid and cross-tenant members")
    void addMembersSkipInvalid() {
        when(roleService.getById(100L)).thenReturn(new Role());
        when(tenantMemberService.findByPid("none")).thenReturn(null);

        TenantMember crossTenant = member(2L, 22L, "mp-2");
        crossTenant.setTenantId(99L);
        when(tenantMemberService.findByPid("mp-2")).thenReturn(crossTenant);

        TenantMember ok = member(1L, 11L, "mp-1");
        when(tenantMemberService.findByPid("mp-1")).thenReturn(ok);

        service.addMembers(100L, List.of("none", "mp-2", "mp-1"));
        verify(userRoleService).assignRolesToMember(1L, List.of(100L), 10L, 1L);
        verify(userRoleService, never()).assignRolesToMember(2L, List.of(100L), 10L, 1L);
    }

    @Test
    @DisplayName("removeMembers no-op for empty list")
    void removeMembersEmpty() {
        service.removeMembers(100L, List.of());
        verify(userRoleService, never()).removeRolesFromMember(any(), any(), any());
    }

    @Test
    @DisplayName("removeMembers skips missing/cross-tenant and removes valid")
    void removeMembersSkipInvalid() {
        when(tenantMemberService.findByPid("none")).thenReturn(null);
        TenantMember crossTenant = member(2L, 22L, "mp-2");
        crossTenant.setTenantId(99L);
        when(tenantMemberService.findByPid("mp-2")).thenReturn(crossTenant);
        when(tenantMemberService.findByPid("mp-1")).thenReturn(member(1L, 11L, "mp-1"));

        service.removeMembers(100L, List.of("none", "mp-2", "mp-1"));
        verify(userRoleService).removeRolesFromMember(1L, List.of(100L), 10L);
    }

    @Test
    @DisplayName("getCandidates filters out already-assigned and deleted members")
    void getCandidates() {
        when(userRoleService.list(any(QueryWrapper.class))).thenReturn(List.of(ur(1L, 100L)));
        TenantMember assigned = member(1L, 11L, "mp-1");
        TenantMember deleted = member(2L, 22L, "mp-2");
        deleted.setDeletedFlag(true);
        TenantMember candidate = member(3L, 33L, "mp-3");

        when(tenantMemberService.findByTenantId(10L)).thenReturn(List.of(assigned, deleted, candidate));
        when(userService.findByUserIds(any())).thenReturn(List.of(user(33L, "c@x.com", "Carla")));
        when(organizationService.getEmployeesByMemberPids(anyCollection())).thenReturn(Map.of());

        List<RoleMemberDTO> out = service.getCandidates(100L, null);
        assertEquals(1, out.size());
        assertEquals("Carla", out.get(0).userName());
    }

    @Test
    @DisplayName("getCandidates filters by keyword (name and email)")
    void getCandidatesKeyword() {
        when(userRoleService.list(any(QueryWrapper.class))).thenReturn(List.of());
        TenantMember m1 = member(3L, 33L, "mp-3");
        TenantMember m2 = member(4L, 44L, "mp-4");
        when(tenantMemberService.findByTenantId(10L)).thenReturn(List.of(m1, m2));
        when(userService.findByUserIds(any())).thenReturn(List.of(
                user(33L, "carla@x.com", "Carla"),
                user(44L, "bob@x.com", "Bob")));
        when(organizationService.getEmployeesByMemberPids(anyCollection())).thenReturn(Map.of());

        List<RoleMemberDTO> out = service.getCandidates(100L, "carla");
        assertEquals(1, out.size());
        assertNotNull(out.get(0));
    }
}
