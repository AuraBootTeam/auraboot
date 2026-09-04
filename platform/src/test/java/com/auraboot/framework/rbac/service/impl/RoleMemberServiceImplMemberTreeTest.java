package com.auraboot.framework.rbac.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.organization.dto.DepartmentTreeNode;
import com.auraboot.framework.organization.dto.OrgEmployeeDTO;
import com.auraboot.framework.organization.service.OrganizationService;
import com.auraboot.framework.rbac.dto.RoleMemberTreeResponse;
import com.auraboot.framework.rbac.entity.UserRole;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RoleMemberServiceImpl member tree")
class RoleMemberServiceImplMemberTreeTest {

    @Mock private UserRoleService userRoleService;
    @Mock private RoleService roleService;
    @Mock private TenantMemberService tenantMemberService;
    @Mock private UserService userService;
    @Mock private OrganizationService organizationService;

    private RoleMemberServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RoleMemberServiceImpl(userRoleService, roleService, tenantMemberService,
            userService, organizationService);
        MetaContext.setContext(10L, 1L, "u-1", "user");
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void memberTree_annotatesAssignmentStatusAndGroupsByDepartment() {
        UserRole assigned = ur(1L);
        when(userRoleService.list(any(QueryWrapper.class))).thenReturn(List.of(assigned));
        TenantMember assignedMember = member(1L, "m-1");
        when(tenantMemberService.listByIds(anyCollection())).thenReturn(List.of(assignedMember));

        List<OrgEmployeeDTO> employees = List.of(
            employee("e-1", "Zhang San", "d-1", "m-1", "u-1"),
            employee("e-2", "Li Si", "d-1", "m-2", "u-2"),
            employee("e-3", "Wang Wu", null, "m-3", "u-3"));
        when(organizationService.getEmployeesByTenant(anyInt(), anyInt(), any()))
            .thenReturn(PaginationResult.of(employees, 3L, 1, 500));
        List<DepartmentTreeNode> deptTree = List.of(
            new DepartmentTreeNode("d-1", "Sales", null, 2,
                List.of(new DepartmentTreeNode("d-1a", "East", "d-1", 0, List.of()))));
        when(organizationService.getDepartmentTree(10L)).thenReturn(deptTree);

        RoleMemberTreeResponse tree = service.getMemberTree(100L);

        assertThat(tree.departments()).hasSize(1);
        RoleMemberTreeResponse.DeptUserTreeNode sales = tree.departments().get(0);
        assertThat(sales.name()).isEqualTo("Sales");
        assertThat(sales.users()).extracting(RoleMemberTreeResponse.DeptUserNodeUser::memberPid)
            .containsExactly("m-1", "m-2");
        assertThat(sales.users()).extracting(RoleMemberTreeResponse.DeptUserNodeUser::assigned)
            .containsExactly(true, false);
        assertThat(sales.children()).hasSize(1);
        assertThat(sales.children().get(0).users()).isEmpty();

        assertThat(tree.ungrouped()).hasSize(1);
        assertThat(tree.ungrouped().get(0).memberPid()).isEqualTo("m-3");
        assertThat(tree.ungrouped().get(0).assigned()).isFalse();
    }

    @Test
    void memberTree_returnsEmptyTreeWithoutAssignmentsOrEmployees() {
        when(userRoleService.list(any(QueryWrapper.class))).thenReturn(List.of());
        when(organizationService.getEmployeesByTenant(anyInt(), anyInt(), any()))
            .thenReturn(PaginationResult.empty(1, 500));
        when(organizationService.getDepartmentTree(10L)).thenReturn(List.of());

        RoleMemberTreeResponse tree = service.getMemberTree(100L);

        assertThat(tree.departments()).isEmpty();
        assertThat(tree.ungrouped()).isEmpty();
    }

    private UserRole ur(Long memberId) {
        UserRole userRole = mock(UserRole.class);
        when(userRole.getMemberId()).thenReturn(memberId);
        return userRole;
    }

    private TenantMember member(Long id, String pid) {
        TenantMember member = new TenantMember();
        member.setId(id);
        member.setPid(pid);
        return member;
    }

    private OrgEmployeeDTO employee(String pid, String name, String deptPid,
                                    String memberPid, String userPid) {
        return new OrgEmployeeDTO(pid, name, null, null, null, null,
            deptPid, deptPid == null ? null : "Dept", null, null, "active", null,
            memberPid, userPid);
    }
}
