package com.auraboot.framework.organization.controller;

import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.organization.dto.DepartmentAdminRequests;
import com.auraboot.framework.organization.dto.DepartmentTreeNode;
import com.auraboot.framework.organization.dto.OrgEmployeeDTO;
import com.auraboot.framework.organization.service.OrgEmployeeService;
import com.auraboot.framework.organization.service.OrganizationService;
import com.auraboot.framework.organization.service.PermittedDepartmentTreeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
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
class OrgControllerDepartmentAdminTest {

    @Mock
    private OrganizationService organizationService;
    @Mock
    private PermittedDepartmentTreeService permittedDepartmentTreeService;
    @Mock
    private OrgEmployeeService orgEmployeeService;
    @Mock
    private DynamicDataService dynamicDataService;

    @InjectMocks
    private OrgController controller;

    @Test
    void employeeReadAndAccountOpeningDeclareSeparatePermissions() throws Exception {
        assertThat(OrgController.class.getMethod("getEmployeesByTenant", int.class, int.class, String.class)
            .getAnnotation(RequirePermission.class).value()).isEqualTo("model.org_employee.read");
        assertThat(OrgController.class.getMethod("getEmployeesByDept", String.class, boolean.class,
            int.class, int.class, String.class).getAnnotation(RequirePermission.class).value())
            .isEqualTo("model.org_employee.read");
        assertThat(OrgController.class.getMethod("getEmployeeProvisionOptions", int.class, int.class,
            String.class).getAnnotation(RequirePermission.class).value())
            .isEqualTo("model.tenant_member.provision_member_from_employee");
    }

    @Test
    void accountOpeningOptionsProjectOnlyIdentityAndPreserveScopedQuery() {
        when(dynamicDataService.list(eq("org_employee"), any())).thenReturn(PaginationResult.of(
            List.of(Map.of("pid", "employee-1", "org_emp_name", "Employee One",
                "org_emp_email", "private@example.test", "org_emp_phone", "13912345678",
                "org_emp_user_id", "private-user", "org_emp_dept_id", "private-department")),
            12L, 2, 5));

        var result = controller.getEmployeeProvisionOptions(2, 5, "Employee").getData();
        var query = ArgumentCaptor.forClass(DynamicQueryRequest.class);
        verify(dynamicDataService).list(eq("org_employee"), query.capture());
        assertThat(query.getValue().getPageNum()).isEqualTo(2);
        assertThat(query.getValue().getPageSize()).isEqualTo(5);
        assertThat(query.getValue().getKeyword()).isEqualTo("Employee");
        assertThat(result.getRecords()).containsExactly(
            new OrgController.EmployeeProvisionOption("employee-1", "Employee One"));
        assertThat(result.getTotal()).isEqualTo(12L);
        assertThat(result.getPage()).isEqualTo(2);
        assertThat(result.getPageSize()).isEqualTo(5);
        var fields = java.util.Arrays.stream(OrgController.EmployeeProvisionOption.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName).toList();
        assertThat(fields).containsExactly("pid", "name");
    }

    @Test
    void departmentWriteEndpointsRequireHrManagementWithoutTeamManagement() {
        var writeMethods = List.of("createDepartment", "updateDepartment", "deleteDepartment",
            "sortDepartments", "setDepartmentCommander");
        var handlers = java.util.Arrays.stream(OrgController.class.getDeclaredMethods())
            .filter(method -> writeMethods.contains(method.getName()))
            .toList();
        assertThat(handlers).hasSize(writeMethods.size());
        for (var handler : handlers) {
            var guard = handler.getAnnotation(RequirePermission.class);
            assertThat(guard).as("%s must declare its write permission", handler.getName()).isNotNull();
            assertThat(guard.value()).as("%s belongs to organization maintenance, not team maintenance",
                handler.getName()).isEqualTo("org.hr.manage");
        }
    }

    private void mockDepartmentExists(String pid) {
        when(dynamicDataService.getById("org_department", pid))
            .thenReturn(Map.of("pid", pid, "name", "Dept"));
    }

    @Test
    void sort_appliesEachItemOrder() {
        var response = controller.sortDepartments(new DepartmentAdminRequests.SortRequest(List.of(
            new DepartmentAdminRequests.SortItem("d-1", 2),
            new DepartmentAdminRequests.SortItem("d-2", 1))));

        verify(dynamicDataService).update("org_department", "d-1", Map.of("org_dept_order", 2));
        verify(dynamicDataService).update("org_department", "d-2", Map.of("org_dept_order", 1));
        assertThat(response.getData()).isNull();
    }

    @Test
    void setCommander_updatesManagerFieldAfterEmployeeCheck() {
        mockDepartmentExists("d-1");
        when(dynamicDataService.getById("org_employee", "e-9"))
            .thenReturn(Map.of("pid", "e-9", "org_emp_user_id", "u-9"));

        controller.setDepartmentCommander("d-1",
            new DepartmentAdminRequests.SetCommanderRequest("e-9"));

        verify(dynamicDataService).update("org_department", "d-1",
            Map.of("org_dept_manager_id", "e-9"));
    }

    @Test
    void setCommander_rejectsUnknownEmployee() {
        mockDepartmentExists("d-1");
        when(dynamicDataService.getById("org_employee", "e-404")).thenReturn(null);

        assertThatThrownBy(() -> controller.setDepartmentCommander("d-1",
            new DepartmentAdminRequests.SetCommanderRequest("e-404")))
            .isInstanceOf(RootUnCheckedException.class);
        verify(dynamicDataService, never()).update(eq("org_department"), any(), any());
    }

    @Test
    void deleteCheck_reportsChildAndEmployeeBlockers() {
        mockDepartmentExists("d-1");
        when(organizationService.getDeptAndSubPids("d-1")).thenReturn(List.of("d-1", "d-1a", "d-1b"));
        when(organizationService.getEmployeesByDept(eq("d-1"), eq(false), eq(1), eq(1), eq(null)))
            .thenReturn(PaginationResult.of(List.of(employee("e-1")), 1L, 1, 1));

        var response = controller.checkDepartmentDelete("d-1");

        assertThat(response.getData().canDelete()).isFalse();
        assertThat(response.getData().blockers()).extracting(
                DepartmentAdminRequests.DeleteBlocker::type)
            .containsExactly("child_departments", "employees");
    }

    @Test
    void deleteCheck_allowsLeafWithoutEmployees() {
        mockDepartmentExists("d-1");
        when(organizationService.getDeptAndSubPids("d-1")).thenReturn(List.of("d-1"));
        when(organizationService.getEmployeesByDept(eq("d-1"), eq(false), eq(1), eq(1), eq(null)))
            .thenReturn(PaginationResult.of(List.of(), 0L, 1, 1));

        var response = controller.checkDepartmentDelete("d-1");

        assertThat(response.getData().canDelete()).isTrue();
        assertThat(response.getData().blockers()).isEmpty();
    }

    @Test
    void deleteCheck_rejectsUnknownDepartment() {
        when(dynamicDataService.getById("org_department", "d-404")).thenReturn(null);

        assertThatThrownBy(() -> controller.checkDepartmentDelete("d-404"))
            .isInstanceOf(RootUnCheckedException.class);
    }

    private OrgEmployeeDTO employee(String pid) {
        return new OrgEmployeeDTO(pid, "Zhang San", null, null, null, null,
            "d-1", "Dept", null, null, "active", null, "m-1", "u-1");
    }
}
