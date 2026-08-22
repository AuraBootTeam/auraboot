package com.auraboot.framework.user.service;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.organization.dto.LinkMemberRequest;
import com.auraboot.framework.organization.dto.OrgEmployeeDTO;
import com.auraboot.framework.organization.service.OrgEmployeeService;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.user.dto.EmployeeAccountProvisionRequest;
import com.auraboot.framework.user.dto.EmployeeAccountProvisionResponse;
import com.auraboot.framework.user.dto.EmployeeAccountRow;
import com.auraboot.framework.user.dto.RoleAssignmentMode;
import com.auraboot.framework.user.dto.UserProvisionRequest;
import com.auraboot.framework.user.dto.UserProvisionResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmployeeAccountProvisioningServiceTest {

    @Mock
    private UserProvisioningService userProvisioningService;
    @Mock
    private UserService userService;
    @Mock
    private RoleService roleService;
    @Mock
    private DynamicDataService dynamicDataService;
    @Mock
    private OrgEmployeeService orgEmployeeService;

    @InjectMocks
    private EmployeeAccountProvisioningService service;

    @Test
    void provision_assignsTheRolesEachRowCarriesAndGeneratesCustomerPasswords() {
        when(roleService.findByTenantId(7L)).thenReturn(List.of(
                role(1L, "tenant_admin"),
                role(2L, "qo_sales"),
                role(3L, "qo_procurement"),
                role(4L, "bom_engineering")
        ));
        when(userProvisioningService.provision(any(), eq(7L), eq(100L)))
                .thenAnswer(invocation -> response(invocation.getArgument(0)));

        EmployeeAccountProvisionResponse result = service.provision(request(List.of(
                row("吴书生", "管理员", "tenant_admin"),
                row("袁称磊", "销售", "qo_sales"),
                row("刘星梅", "采购", "qo_procurement"),
                row("邓康铭", "工程", "bom_engineering")
        )), 7L, 100L);

        assertThat(result.getAccounts()).hasSize(4);
        assertThat(result.getAccounts())
                .extracting(EmployeeAccountProvisionResponse.Account::getUserName)
                .containsExactly("吴书生", "袁称磊", "刘星梅", "邓康铭");
        assertThat(result.getAccounts())
                .extracting(EmployeeAccountProvisionResponse.Account::getInitialPassword)
                .allMatch(password -> password.matches("jjzz@\\d{4}"));
        assertThat(result.getAccounts())
                .extracting(EmployeeAccountProvisionResponse.Account::isMustChangePassword)
                .containsOnly(false);

        ArgumentCaptor<UserProvisionRequest> captor = ArgumentCaptor.forClass(UserProvisionRequest.class);
        verify(userProvisioningService, org.mockito.Mockito.times(4)).provision(captor.capture(), eq(7L), eq(100L));
        assertThat(captor.getAllValues())
                .extracting(UserProvisionRequest::getRoleCodes)
                .containsExactly(
                        List.of("tenant_admin"),
                        List.of("qo_sales"),
                        List.of("qo_procurement"),
                        List.of("bom_engineering")
                );
    }

    @Test
    void provision_multipleRolesPerRowAreAllAssigned() {
        when(roleService.findByTenantId(7L)).thenReturn(List.of(
                role(2L, "qo_sales"),
                role(5L, "crm_account_common")
        ));
        when(userProvisioningService.provision(any(), eq(7L), eq(100L)))
                .thenAnswer(invocation -> response(invocation.getArgument(0)));

        service.provision(request(List.of(row("袁称磊", "销售", "qo_sales", "crm_account_common"))), 7L, 100L);

        ArgumentCaptor<UserProvisionRequest> captor = ArgumentCaptor.forClass(UserProvisionRequest.class);
        verify(userProvisioningService).provision(captor.capture(), eq(7L), eq(100L));
        assertThat(captor.getValue().getRoleCodes()).containsExactly("qo_sales", "crm_account_common");
    }

    @Test
    void provision_employeeWithNoRolesIsCreatedAsBareAccount() {
        when(userProvisioningService.provision(any(), eq(7L), eq(100L)))
                .thenAnswer(invocation -> response(invocation.getArgument(0)));

        EmployeeAccountProvisionResponse result = service.provision(
                request(List.of(row("访客小陈", null))), 7L, 100L);

        ArgumentCaptor<UserProvisionRequest> captor = ArgumentCaptor.forClass(UserProvisionRequest.class);
        verify(userProvisioningService).provision(captor.capture(), eq(7L), eq(100L));
        assertThat(captor.getValue().getRoleCodes()).isEmpty();
        assertThat(captor.getValue().getRoleAssignmentMode()).isEqualTo(RoleAssignmentMode.NONE);
        assertThat(result.getAccounts().get(0).getOrganizationAction()).isEqualTo("NONE");
        verify(orgEmployeeService, never()).linkMember(any());
        verify(orgEmployeeService, never()).linkExistingMember(any(), any());
    }

    @Test
    void provision_roleNotInTenantFailsBeforeCreatingUsers() {
        when(roleService.findByTenantId(7L)).thenReturn(List.of(role(1L, "qo_quoter")));

        assertThatThrownBy(() -> service.provision(request(List.of(row("袁称磊", "销售", "qo_sales"))), 7L, 100L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Missing tenant roles")
                .hasMessageContaining("qo_sales");
        verify(userProvisioningService, never()).provision(any(), any(), any());
    }

    @Test
    void provision_typeIsOptionalMetadataAndDoesNotDeriveRoles() {
        when(roleService.findByTenantId(7L)).thenReturn(List.of(role(2L, "qo_sales")));
        when(userProvisioningService.provision(any(), eq(7L), eq(100L)))
                .thenAnswer(invocation -> response(invocation.getArgument(0)));

        // No type at all, roles given explicitly — provisioning proceeds and the
        // type never influences the assigned roles.
        service.provision(request(List.of(row("袁称磊", null, "qo_sales"))), 7L, 100L);

        ArgumentCaptor<UserProvisionRequest> captor = ArgumentCaptor.forClass(UserProvisionRequest.class);
        verify(userProvisioningService).provision(captor.capture(), eq(7L), eq(100L));
        assertThat(captor.getValue().getRoleCodes()).containsExactly("qo_sales");
    }

    @Test
    void provision_duplicateDefaultLoginNamesInSameBatchAreRejected() {
        assertThatThrownBy(() -> service.provision(request(List.of(
                row("吴书生", "管理员", "tenant_admin"),
                row(" 吴书生 ", "管理员", "tenant_admin")
        )), 7L, 100L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Duplicate login name");
        verify(userProvisioningService, never()).provision(any(), any(), any());
    }

    @Test
    void preview_rejectsMoreThanFiveHundredRowsBeforeLookup() {
        List<EmployeeAccountRow> rows = java.util.stream.IntStream.rangeClosed(1, 501)
                .mapToObj(index -> row("employee-" + index, null))
                .toList();

        assertThatThrownBy(() -> service.preview(rows, 7L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("At most 500 employees");
        verify(userService, never()).findByUserName(any());
        verify(dynamicDataService, never()).list(any(), any());
    }

    @Test
    void provision_duplicateDisplayNamesAreAllowedWithDistinctLoginNames() {
        when(userProvisioningService.provision(any(), eq(7L), eq(100L)))
                .thenAnswer(invocation -> response(invocation.getArgument(0)));
        EmployeeAccountRow first = row("王佳霞", null);
        first.setUserName("wangjiaxia-1");
        EmployeeAccountRow second = row("王佳霞", null);
        second.setUserName("wangjiaxia-2");

        EmployeeAccountProvisionResponse result = service.provision(
                request(List.of(first, second)), 7L, 100L);

        assertThat(result.getAccounts())
                .extracting(EmployeeAccountProvisionResponse.Account::getUserName)
                .containsExactly("wangjiaxia-1", "wangjiaxia-2");
    }

    @Test
    void preview_duplicateDepartmentCodeFailsInsteadOfTakingFirstRecord() {
        when(dynamicDataService.list(eq("org_employee"), any()))
                .thenReturn(PaginationResult.empty(1, 2));
        when(dynamicDataService.list(eq("org_department"), any()))
                .thenReturn(PaginationResult.of(List.of(
                        Map.of("pid", "dept-1", "org_dept_code", "SALES"),
                        Map.of("pid", "dept-2", "org_dept_code", "SALES")), 2L, 1, 2));
        EmployeeAccountRow employee = row("王佳霞", null);
        employee.setEmployeeCode("EMP001");
        employee.setDepartmentCode("SALES");

        var preview = service.preview(List.of(employee), 7L);

        assertThat(preview.getErrorCount()).isEqualTo(1);
        assertThat(preview.getRows().get(0).getErrors())
                .contains("Duplicate department code in tenant: SALES");
    }

    @Test
    void preview_duplicateEmployeeCodesInWorkbookAreRejected() {
        when(dynamicDataService.list(eq("org_employee"), any()))
                .thenReturn(PaginationResult.empty(1, 2));
        when(dynamicDataService.list(eq("org_department"), any()))
                .thenReturn(PaginationResult.of(
                        List.of(Map.of("pid", "dept-1", "org_dept_code", "SALES")), 1L, 1, 2));
        EmployeeAccountRow first = row("王佳霞", null);
        first.setUserName("wang-1");
        first.setEmployeeCode("EMP001");
        first.setDepartmentCode("SALES");
        EmployeeAccountRow second = row("王佳霞", null);
        second.setUserName("wang-2");
        second.setEmployeeCode("EMP001");
        second.setDepartmentCode("SALES");

        var preview = service.preview(List.of(first, second), 7L);

        assertThat(preview.getErrorCount()).isEqualTo(1);
        assertThat(preview.getRows().get(1).getErrors())
                .contains("Duplicate employee code in workbook: EMP001");
    }

    @Test
    void preview_positionOutsideDepartmentIsRejected() {
        when(dynamicDataService.list(eq("org_employee"), any()))
                .thenReturn(PaginationResult.empty(1, 2));
        when(dynamicDataService.list(eq("org_department"), any()))
                .thenReturn(PaginationResult.of(
                        List.of(Map.of("pid", "dept-support", "org_dept_code", "SUPPORT")),
                        1L, 1, 2));
        when(dynamicDataService.list(eq("org_position"), any()))
                .thenReturn(PaginationResult.of(List.of(Map.of(
                        "pid", "pos-sales",
                        "org_pos_code", "SALES_REP",
                        "org_pos_dept_id", "dept-sales")), 1L, 1, 2));
        EmployeeAccountRow employee = row("王佳霞", null);
        employee.setEmployeeCode("EMP001");
        employee.setDepartmentCode("SUPPORT");
        employee.setPositionCode("SALES_REP");

        var preview = service.preview(List.of(employee), 7L);

        assertThat(preview.getErrorCount()).isEqualTo(1);
        assertThat(preview.getRows().get(0).getErrors())
                .contains("Position SALES_REP does not belong to department SUPPORT");
    }

    @Test
    void preview_employeeAlreadyLinkedToMemberIsRejected() {
        when(dynamicDataService.list(eq("org_employee"), any()))
                .thenReturn(PaginationResult.of(List.of(Map.of(
                        "pid", "employee-existing",
                        "org_emp_code", "EMP002",
                        "org_emp_dept_id", "dept-1",
                        "org_emp_position_id", "pos-1",
                        "org_emp_member_id", "member-existing")), 1L, 1, 2));
        EmployeeAccountRow employee = row("已有人员", null);
        employee.setEmployeeCode("EMP002");

        var preview = service.preview(List.of(employee), 7L);

        assertThat(preview.getErrorCount()).isEqualTo(1);
        assertThat(preview.getRows().get(0).getErrors())
                .contains("Employee is already linked to a tenant member: EMP002");
    }

    @Test
    void provision_createsEmployeeByUniqueDepartmentAndPositionCodes() {
        when(dynamicDataService.list(eq("org_employee"), any()))
                .thenReturn(PaginationResult.empty(1, 2));
        when(dynamicDataService.list(eq("org_department"), any()))
                .thenReturn(PaginationResult.of(
                        List.of(Map.of("pid", "dept-1", "org_dept_code", "SALES")), 1L, 1, 2));
        when(dynamicDataService.list(eq("org_position"), any()))
                .thenReturn(PaginationResult.of(List.of(Map.of(
                        "pid", "pos-1",
                        "org_pos_code", "SALES_REP",
                        "org_pos_dept_id", "dept-1")), 1L, 1, 2));
        when(userProvisioningService.provision(any(), eq(7L), eq(100L)))
                .thenAnswer(invocation -> response(invocation.getArgument(0)));
        when(orgEmployeeService.linkMember(any())).thenReturn(new OrgEmployeeDTO(
                "employee-pid", "王佳霞", "EMP001", null, "13800000000", null,
                "dept-1", "销售部", "pos-1", "销售", "active", "human", "member-pid", "u-pid"));
        EmployeeAccountRow employee = row("王佳霞", null);
        employee.setMobile("13800000000");
        employee.setEmployeeCode("EMP001");
        employee.setDepartmentCode("SALES");
        employee.setPositionCode("SALES_REP");

        EmployeeAccountProvisionResponse result = service.provision(
                request(List.of(employee)), 7L, 100L);

        assertThat(result.getAccounts().get(0).getOrganizationAction()).isEqualTo("CREATED");
        assertThat(result.getAccounts().get(0).getEmployeePid()).isEqualTo("employee-pid");
        ArgumentCaptor<LinkMemberRequest> linkCaptor = ArgumentCaptor.forClass(LinkMemberRequest.class);
        verify(orgEmployeeService).linkMember(linkCaptor.capture());
        assertThat(linkCaptor.getValue().getMemberPid()).isEqualTo("member-pid");
        assertThat(linkCaptor.getValue().getEmployeeCode()).isEqualTo("EMP001");
        assertThat(linkCaptor.getValue().getDeptPid()).isEqualTo("dept-1");
        assertThat(linkCaptor.getValue().getPositionPid()).isEqualTo("pos-1");
    }

    @Test
    void provision_linksExistingEmployeeByUniqueEmployeeCode() {
        when(dynamicDataService.list(eq("org_employee"), any()))
                .thenReturn(PaginationResult.of(List.of(Map.of(
                        "pid", "employee-existing",
                        "org_emp_code", "EMP002",
                        "org_emp_dept_id", "dept-1",
                        "org_emp_position_id", "pos-1")), 1L, 1, 2));
        when(userProvisioningService.provision(any(), eq(7L), eq(100L)))
                .thenAnswer(invocation -> response(invocation.getArgument(0)));
        when(orgEmployeeService.linkExistingMember("employee-existing", "member-pid"))
                .thenReturn(new OrgEmployeeDTO(
                        "employee-existing", "已有人员", "EMP002", null, null, null,
                        "dept-1", "销售部", "pos-1", "销售", "active", "human",
                        "member-pid", "u-pid"));
        EmployeeAccountRow employee = row("已有人员", null);
        employee.setEmployeeCode("EMP002");

        EmployeeAccountProvisionResponse result = service.provision(
                request(List.of(employee)), 7L, 100L);

        assertThat(result.getAccounts().get(0).getOrganizationAction()).isEqualTo("LINKED");
        assertThat(result.getAccounts().get(0).getEmployeePid()).isEqualTo("employee-existing");
        verify(orgEmployeeService).linkExistingMember("employee-existing", "member-pid");
        verify(orgEmployeeService, never()).linkMember(any());
    }

    @Test
    void provision_existingUserNameIsRejectedBeforeCreatingUsers() {
        when(userService.findByUserName("吴书生")).thenReturn(new com.auraboot.framework.user.dao.entity.User());

        assertThatThrownBy(() -> service.provision(request(List.of(row("吴书生", "管理员", "tenant_admin"))), 7L, 100L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("User already exists");
        verify(userProvisioningService, never()).provision(any(), any(), any());
    }

    @Test
    void provision_rejectsTooLongPasswordPrefixBeforeCreatingUsers() {
        EmployeeAccountProvisionRequest request = request(List.of(row("吴书生", "管理员", "tenant_admin")));
        request.setPasswordPrefix("x".repeat(33));

        assertThatThrownBy(() -> service.provision(request, 7L, 100L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("passwordPrefix");
        verify(userProvisioningService, never()).provision(any(), any(), any());
    }

    private EmployeeAccountProvisionRequest request(List<EmployeeAccountRow> rows) {
        EmployeeAccountProvisionRequest request = new EmployeeAccountProvisionRequest();
        request.setEmployees(rows);
        return request;
    }

    private EmployeeAccountRow row(String name, String type, String... roles) {
        EmployeeAccountRow row = new EmployeeAccountRow();
        row.setName(name);
        row.setType(type);
        row.setRoles(roles.length == 0 ? null : List.of(roles));
        return row;
    }

    private Role role(Long id, String code) {
        Role role = new Role();
        role.setId(id);
        role.setCode(code);
        return role;
    }

    private UserProvisionResponse response(UserProvisionRequest request) {
        return UserProvisionResponse.builder()
                .userId(1L)
                .userPid("u-pid")
                .memberPid("member-pid")
                .displayName(request.getDisplayName())
                .assignedRoles(request.getRoleCodes())
                .mustChangePassword(false)
                .temporaryPassword(null)
                .build();
    }
}
