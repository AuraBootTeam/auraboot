package com.auraboot.framework.organization.service.impl;

import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.service.DynamicDataService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PlatformApproverResolverImpl")
class PlatformApproverResolverImplTest {

    @Mock private DynamicDataService dynamicDataService;
    @InjectMocks private PlatformApproverResolverImpl resolver;

    private static Map<String, Object> emp(String userId, String reportTo, String positionId, String deptId) {
        Map<String, Object> e = new HashMap<>();
        e.put("org_emp_user_id", userId);
        e.put("org_emp_report_to", reportTo);
        e.put("org_emp_position_id", positionId);
        e.put("org_emp_dept_id", deptId);
        return e;
    }

    private void stubFindEmployeeByUserId(String userId, Map<String, Object> emp) {
        List<Map<String, Object>> records = emp == null ? List.of() : List.of(emp);
        when(dynamicDataService.list(eq("org_employee"), any(DynamicQueryRequest.class)))
            .thenReturn(PaginationResult.of(records, (long) records.size(), 1, 1));
    }

    @Test
    @DisplayName("resolveApprovers throws when employee not found")
    void resolveApproversNoEmployee() {
        stubFindEmployeeByUserId("u1", null);
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> resolver.resolveApprovers("u1", "manager"));
        assertTrue(ex.getMessage().contains("u1"));
    }

    @Test
    @DisplayName("resolveApprovers throws on unknown level")
    void resolveApproversUnknownLevel() {
        stubFindEmployeeByUserId("u1", emp("u1", "emp2", null, null));
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> resolver.resolveApprovers("u1", "godhood"));
        assertTrue(ex.getMessage().contains("godhood"));
    }

    @Test
    @DisplayName("resolveApprovers walks chain and returns first sufficient level")
    void resolveApproversWalksChain() {
        stubFindEmployeeByUserId("u1", emp("u1", "emp2", null, null));
        Map<String, Object> emp2 = emp("u2", "emp3", "pos2", null);
        Map<String, Object> emp3 = emp("u3", null, "pos3", null);
        when(dynamicDataService.getById("org_employee", "emp2")).thenReturn(emp2);
        when(dynamicDataService.getById("org_employee", "emp3")).thenReturn(emp3);

        Map<String, Object> pos2 = new HashMap<>();
        pos2.put("org_pos_level", "supervisor");
        Map<String, Object> pos3 = new HashMap<>();
        pos3.put("org_pos_level", "manager");
        when(dynamicDataService.getById("org_position", "pos2")).thenReturn(pos2);
        when(dynamicDataService.getById("org_position", "pos3")).thenReturn(pos3);

        List<String> approvers = resolver.resolveApprovers("u1", "manager");
        assertEquals(List.of("u3"), approvers);
    }

    @Test
    @DisplayName("resolveApprovers falls back to dept manager")
    void resolveApproversDeptFallback() {
        Map<String, Object> me = emp("u1", null, null, "d1");
        stubFindEmployeeByUserId("u1", me);

        Map<String, Object> dept = new HashMap<>();
        dept.put("org_dept_manager_id", "mgr-emp");
        when(dynamicDataService.getById("org_department", "d1")).thenReturn(dept);
        Map<String, Object> mgr = emp("u-mgr", null, null, null);
        when(dynamicDataService.getById("org_employee", "mgr-emp")).thenReturn(mgr);

        List<String> approvers = resolver.resolveApprovers("u1", "manager");
        assertEquals(List.of("u-mgr"), approvers);
    }

    @Test
    @DisplayName("resolveApprovers returns empty when nothing found")
    void resolveApproversEmpty() {
        stubFindEmployeeByUserId("u1", emp("u1", null, null, null));
        assertTrue(resolver.resolveApprovers("u1", "manager").isEmpty());
    }

    @Test
    @DisplayName("resolveDirectSupervisor returns superior user id")
    void resolveDirectSupervisor() {
        stubFindEmployeeByUserId("u1", emp("u1", "emp2", null, null));
        when(dynamicDataService.getById("org_employee", "emp2"))
            .thenReturn(emp("u2", null, null, null));
        assertEquals("u2", resolver.resolveDirectSupervisor("u1"));
    }

    @Test
    @DisplayName("resolveDirectSupervisor falls back to dept manager")
    void resolveDirectSupervisorFallback() {
        stubFindEmployeeByUserId("u1", emp("u1", null, null, "d1"));
        Map<String, Object> dept = new HashMap<>();
        dept.put("org_dept_manager_id", "mgr-emp");
        when(dynamicDataService.getById("org_department", "d1")).thenReturn(dept);
        when(dynamicDataService.getById("org_employee", "mgr-emp"))
            .thenReturn(emp("u-mgr", null, null, null));
        assertEquals("u-mgr", resolver.resolveDirectSupervisor("u1"));
    }

    @Test
    @DisplayName("resolveDirectSupervisor throws when employee missing")
    void resolveDirectSupervisorMissing() {
        stubFindEmployeeByUserId("u1", null);
        assertThrows(RuntimeException.class, () -> resolver.resolveDirectSupervisor("u1"));
    }

    @Test
    @DisplayName("resolveDepartmentManager returns null when employee not found")
    void resolveDepartmentManagerNoEmployee() {
        stubFindEmployeeByUserId("u1", null);
        assertNull(resolver.resolveDepartmentManager("u1"));
    }

    @Test
    @DisplayName("resolveDepartmentManager handles full happy path and missing layers")
    void resolveDepartmentManagerHappy() {
        stubFindEmployeeByUserId("u1", emp("u1", null, null, "d1"));
        Map<String, Object> dept = new HashMap<>();
        dept.put("org_dept_manager_id", "mgr-emp");
        when(dynamicDataService.getById("org_department", "d1")).thenReturn(dept);
        when(dynamicDataService.getById("org_employee", "mgr-emp"))
            .thenReturn(emp("u-mgr", null, null, null));
        assertEquals("u-mgr", resolver.resolveDepartmentManager("u1"));
    }

    @Test
    @DisplayName("resolveDepartmentManager null when no dept on employee")
    void resolveDepartmentManagerNoDept() {
        stubFindEmployeeByUserId("u1", emp("u1", null, null, null));
        assertNull(resolver.resolveDepartmentManager("u1"));
    }

    @Test
    @DisplayName("resolveDepartmentManager null when dept missing or has no manager")
    void resolveDepartmentManagerMissingDept() {
        stubFindEmployeeByUserId("u1", emp("u1", null, null, "d1"));
        when(dynamicDataService.getById("org_department", "d1")).thenReturn(null);
        assertNull(resolver.resolveDepartmentManager("u1"));
    }
}
