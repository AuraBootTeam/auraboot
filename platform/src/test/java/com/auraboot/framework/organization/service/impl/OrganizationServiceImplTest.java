package com.auraboot.framework.organization.service.impl;

import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.organization.dto.DepartmentTreeNode;
import com.auraboot.framework.organization.dto.OrgEmployeeDTO;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.entity.UserRole;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrganizationServiceImpl")
class OrganizationServiceImplTest {

    @Mock private DynamicDataService dynamicDataService;
    @Mock private RoleService roleService;
    @Mock private UserRoleService userRoleService;
    @Mock private TenantMemberService tenantMemberService;

    @InjectMocks private OrganizationServiceImpl service;

    private static Map<String, Object> dept(String pid, String name, String parent, String managerId) {
        Map<String, Object> d = new HashMap<>();
        d.put("pid", pid);
        d.put("org_dept_name", name);
        d.put("org_dept_parent_id", parent);
        d.put("org_dept_manager_id", managerId);
        return d;
    }

    private static Map<String, Object> emp(String pid, String deptPid, String reportTo) {
        Map<String, Object> e = new HashMap<>();
        e.put("pid", pid);
        e.put("org_emp_dept_id", deptPid);
        e.put("org_emp_report_to", reportTo);
        e.put("org_emp_name", "name-" + pid);
        e.put("org_emp_member_id", "member-" + pid);
        return e;
    }

    private static <T> PaginationResult<T> page(List<T> records) {
        return PaginationResult.of(records, (long) records.size(), 1, records.size() == 0 ? 10 : records.size());
    }

    @BeforeEach
    void setUp() {
        // some tests don't need dept/emp lookups — use lenient stubs only when registered
    }

    @Test
    @DisplayName("getDepartmentTree returns empty when no records")
    void getDepartmentTreeEmpty() {
        when(dynamicDataService.list(eq("org_department"), any(DynamicQueryRequest.class)))
            .thenReturn(page(List.of()));
        assertTrue(service.getDepartmentTree(1L).isEmpty());
    }

    @Test
    @DisplayName("getDepartmentTree builds nested tree with employee counts")
    void getDepartmentTreeBuildsTree() {
        Map<String, Object> root = dept("d1", "root", null, null);
        Map<String, Object> child = dept("d2", "child", "d1", null);
        Map<String, Object> grand = dept("d3", "grand", "d2", null);
        when(dynamicDataService.list(eq("org_department"), any(DynamicQueryRequest.class)))
            .thenReturn(page(List.of(root, child, grand)));
        when(dynamicDataService.list(eq("org_employee"), any(DynamicQueryRequest.class)))
            .thenReturn(page(List.of(emp("e1", "d2", null), emp("e2", "d2", null), emp("e3", "d3", null))));

        List<DepartmentTreeNode> tree = service.getDepartmentTree(null);
        assertEquals(1, tree.size());
        DepartmentTreeNode r = tree.get(0);
        assertEquals("d1", r.pid());
        assertEquals(0, r.employeeCount());
        assertEquals(1, r.children().size());
        DepartmentTreeNode c = r.children().get(0);
        assertEquals("d2", c.pid());
        assertEquals(2, c.employeeCount());
        assertEquals(1, c.children().get(0).employeeCount());
    }

    @Test
    @DisplayName("getDeptAndSubPids walks subtree")
    void getDeptAndSubPids() {
        Map<String, Object> root = dept("d1", "root", null, null);
        Map<String, Object> child = dept("d2", "child", "d1", null);
        when(dynamicDataService.list(eq("org_department"), any(DynamicQueryRequest.class)))
            .thenReturn(page(List.of(root, child)));
        when(dynamicDataService.list(eq("org_employee"), any(DynamicQueryRequest.class)))
            .thenReturn(page(List.of()));

        List<String> pids = service.getDeptAndSubPids("d1");
        assertEquals(List.of("d1", "d2"), pids);

        List<String> none = service.getDeptAndSubPids("missing");
        assertTrue(none.isEmpty());
    }

    @Test
    @DisplayName("getEmployeeByMemberPid returns first record or null")
    void getEmployeeByMemberPid() {
        Map<String, Object> e = emp("e1", "d1", null);
        when(dynamicDataService.list(eq("org_employee"), any(DynamicQueryRequest.class)))
            .thenReturn(page(List.of(e)));
        assertSame(e, service.getEmployeeByMemberPid("m1"));

        when(dynamicDataService.list(eq("org_employee"), any(DynamicQueryRequest.class)))
            .thenReturn(page(List.of()));
        assertNull(service.getEmployeeByMemberPid("m2"));
    }

    @Test
    @DisplayName("getEmployeesByMemberPids handles null/empty + populated maps")
    void getEmployeesByMemberPids() {
        assertTrue(service.getEmployeesByMemberPids(null).isEmpty());
        assertTrue(service.getEmployeesByMemberPids(List.of()).isEmpty());

        Map<String, Object> e1 = emp("e1", "d1", null);
        e1.put("org_emp_member_id", "m1");
        Map<String, Object> e2 = emp("e2", "d1", null);
        e2.put("org_emp_member_id", "m2");
        when(dynamicDataService.list(eq("org_employee"), any(DynamicQueryRequest.class)))
            .thenReturn(page(List.of(e1, e2)));

        Map<String, Map<String, Object>> result = service.getEmployeesByMemberPids(List.of("m1", "m2"));
        assertEquals(2, result.size());
        assertEquals("e1", result.get("m1").get("pid"));
    }

    @Test
    @DisplayName("getEmployeesByMemberPids returns empty when DB has no matches")
    void getEmployeesByMemberPidsEmptyResult() {
        when(dynamicDataService.list(eq("org_employee"), any(DynamicQueryRequest.class)))
            .thenReturn(page(List.of()));
        assertTrue(service.getEmployeesByMemberPids(List.of("m1")).isEmpty());
    }

    @Test
    @DisplayName("getManager returns reportTo employee when present")
    void getManagerReportTo() {
        Map<String, Object> e = emp("e1", "d1", "boss");
        Map<String, Object> boss = emp("boss", "d1", null);
        when(dynamicDataService.getById("org_employee", "e1")).thenReturn(e);
        when(dynamicDataService.getById("org_employee", "boss")).thenReturn(boss);
        assertSame(boss, service.getManager("e1"));
    }

    @Test
    @DisplayName("getManager returns dept manager fallback when no reportTo")
    void getManagerDeptFallback() {
        Map<String, Object> e = emp("e1", "d1", null);
        Map<String, Object> d = dept("d1", "x", null, "boss");
        Map<String, Object> boss = emp("boss", "d1", null);
        when(dynamicDataService.getById("org_employee", "e1")).thenReturn(e);
        when(dynamicDataService.getById("org_department", "d1")).thenReturn(d);
        when(dynamicDataService.getById("org_employee", "boss")).thenReturn(boss);
        assertSame(boss, service.getManager("e1"));
    }

    @Test
    @DisplayName("getManager skips dept manager when employee IS the manager")
    void getManagerSkipsSelf() {
        Map<String, Object> e = emp("e1", "d1", null);
        Map<String, Object> d = dept("d1", "x", null, "e1");
        when(dynamicDataService.getById("org_employee", "e1")).thenReturn(e);
        when(dynamicDataService.getById("org_department", "d1")).thenReturn(d);
        assertNull(service.getManager("e1"));
    }

    @Test
    @DisplayName("getManager null when employee missing")
    void getManagerMissing() {
        when(dynamicDataService.getById("org_employee", "x")).thenReturn(null);
        assertNull(service.getManager("x"));
    }

    @Test
    @DisplayName("getEmployeesByDept non-recursive returns dtos")
    void getEmployeesByDeptNonRecursive() {
        when(dynamicDataService.list(eq("org_employee"), any(DynamicQueryRequest.class)))
            .thenReturn(page(List.of(emp("e1", "d1", null))));

        PaginationResult<OrgEmployeeDTO> result = service.getEmployeesByDept("d1", false, 1, 10, null);
        assertEquals(1, result.getRecords().size());
    }

    @Test
    @DisplayName("getEmployeesByDept recursive returns empty when no descendant pids")
    void getEmployeesByDeptRecursiveNoDept() {
        when(dynamicDataService.list(eq("org_department"), any(DynamicQueryRequest.class)))
            .thenReturn(page(List.of()));
        PaginationResult<OrgEmployeeDTO> result = service.getEmployeesByDept("missing", true, 1, 10, null);
        assertEquals(0L, result.getTotal());
    }

    @Test
    @DisplayName("getEmployeesByDept recursive walks subtree")
    void getEmployeesByDeptRecursive() {
        Map<String, Object> root = dept("d1", "root", null, null);
        Map<String, Object> child = dept("d2", "child", "d1", null);
        when(dynamicDataService.list(eq("org_department"), any(DynamicQueryRequest.class)))
            .thenReturn(page(List.of(root, child)));
        when(dynamicDataService.list(eq("org_employee"), any(DynamicQueryRequest.class)))
            .thenReturn(page(List.of(emp("e1", "d1", null), emp("e2", "d2", null))));

        PaginationResult<OrgEmployeeDTO> result = service.getEmployeesByDept("d1", true, 1, 10, "kw");
        assertEquals(2, result.getRecords().size());
    }

    @Test
    @DisplayName("getEmployeesByTenant returns dtos")
    void getEmployeesByTenant() {
        when(dynamicDataService.list(eq("org_employee"), any(DynamicQueryRequest.class)))
            .thenReturn(page(List.of(emp("e1", "d1", null))));
        PaginationResult<OrgEmployeeDTO> result = service.getEmployeesByTenant(1, 10, "kw");
        assertEquals(1, result.getRecords().size());
    }

    @Test
    @DisplayName("getManagersUpToLevel returns empty for invalid input")
    void getManagersUpToLevelInvalid() {
        assertTrue(service.getManagersUpToLevel(null, 5).isEmpty());
        assertTrue(service.getManagersUpToLevel("e1", 0).isEmpty());
    }

    @Test
    @DisplayName("getManagersUpToLevel walks chain up to limit")
    void getManagersUpToLevelChain() {
        Map<String, Object> e1 = emp("e1", "d1", "e2");
        Map<String, Object> e2 = emp("e2", "d1", "e3");
        Map<String, Object> e3 = emp("e3", "d1", null);
        when(dynamicDataService.getById("org_employee", "e1")).thenReturn(e1);
        when(dynamicDataService.getById("org_employee", "e2")).thenReturn(e2);
        when(dynamicDataService.getById("org_employee", "e3")).thenReturn(e3);

        List<Map<String, Object>> managers = service.getManagersUpToLevel("e1", 5);
        assertEquals(2, managers.size());
        assertSame(e2, managers.get(0));
        assertSame(e3, managers.get(1));
    }

    @Test
    @DisplayName("getManagersUpToLevel breaks when manager record missing")
    void getManagersUpToLevelBreaksOnMissing() {
        Map<String, Object> e1 = emp("e1", "d1", "ghost");
        when(dynamicDataService.getById("org_employee", "e1")).thenReturn(e1);
        when(dynamicDataService.getById("org_employee", "ghost")).thenReturn(null);
        assertTrue(service.getManagersUpToLevel("e1", 3).isEmpty());
    }

    @Test
    @DisplayName("getDeptLeader handles null/missing/no-manager paths")
    void getDeptLeaderEdgeCases() {
        assertNull(service.getDeptLeader(null));
        when(dynamicDataService.getById("org_department", "x")).thenReturn(null);
        assertNull(service.getDeptLeader("x"));
        when(dynamicDataService.getById("org_department", "d1")).thenReturn(dept("d1", "x", null, null));
        assertNull(service.getDeptLeader("d1"));
    }

    @Test
    @DisplayName("getDeptLeader returns manager record")
    void getDeptLeaderReturnsManager() {
        Map<String, Object> d = dept("d1", "x", null, "boss");
        Map<String, Object> boss = emp("boss", "d1", null);
        when(dynamicDataService.getById("org_department", "d1")).thenReturn(d);
        when(dynamicDataService.getById("org_employee", "boss")).thenReturn(boss);
        assertSame(boss, service.getDeptLeader("d1"));
    }

    @Test
    @DisplayName("getEmployeesByRole guard branches")
    void getEmployeesByRoleGuards() {
        assertTrue(service.getEmployeesByRole(null, "x").isEmpty());
        assertTrue(service.getEmployeesByRole(1L, null).isEmpty());
        assertTrue(service.getEmployeesByRole(1L, " ").isEmpty());
    }

    @Test
    @DisplayName("getEmployeesByRole returns empty when role not found")
    void getEmployeesByRoleNotFound() {
        when(roleService.findByTenantId(1L)).thenReturn(List.of());
        assertTrue(service.getEmployeesByRole(1L, "admin").isEmpty());
    }

    @Test
    @DisplayName("getEmployeesByRole returns empty when no user-roles")
    void getEmployeesByRoleNoUsers() {
        Role r = new Role();
        r.setId(10L);
        r.setCode("admin");
        when(roleService.findByTenantId(1L)).thenReturn(List.of(r));
        when(userRoleService.findByRoleIds(List.of(10L))).thenReturn(List.of());
        assertTrue(service.getEmployeesByRole(1L, "admin").isEmpty());
    }

    @Test
    @DisplayName("getEmployeesByRole resolves employees through members")
    void getEmployeesByRoleResolvesEmployees() {
        Role r = new Role();
        r.setId(10L);
        r.setCode("admin");
        when(roleService.findByTenantId(1L)).thenReturn(List.of(r));

        UserRole ur1 = new UserRole();
        ur1.setMemberId(100L);
        UserRole ur2 = new UserRole();
        ur2.setMemberId(null);
        UserRole ur3 = new UserRole();
        ur3.setMemberId(101L);
        when(userRoleService.findByRoleIds(List.of(10L))).thenReturn(List.of(ur1, ur2, ur3));

        TenantMember m1 = new TenantMember();
        m1.setPid("mem-1");
        when(tenantMemberService.getById(100L)).thenReturn(m1);
        when(tenantMemberService.getById(101L)).thenReturn(null);

        Map<String, Object> e = emp("e1", "d1", null);
        when(dynamicDataService.list(eq("org_employee"), any(DynamicQueryRequest.class)))
            .thenReturn(page(List.of(e)));

        List<Map<String, Object>> result = service.getEmployeesByRole(1L, "admin");
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("getEmployeesByPosition edge cases")
    void getEmployeesByPositionEdge() {
        assertTrue(service.getEmployeesByPosition(1L, null).isEmpty());
        assertTrue(service.getEmployeesByPosition(1L, " ").isEmpty());

        when(dynamicDataService.list(eq("org_position"), any(DynamicQueryRequest.class)))
            .thenReturn(page(List.of()));
        assertTrue(service.getEmployeesByPosition(1L, "ceo").isEmpty());
    }

    @Test
    @DisplayName("getEmployeesByPosition returns matching employees")
    void getEmployeesByPositionReturns() {
        Map<String, Object> pos = new HashMap<>();
        pos.put("pid", "p1");
        when(dynamicDataService.list(eq("org_position"), any(DynamicQueryRequest.class)))
            .thenReturn(page(List.of(pos)));
        when(dynamicDataService.list(eq("org_employee"), any(DynamicQueryRequest.class)))
            .thenReturn(page(List.of(emp("e1", "d1", null))));
        assertEquals(1, service.getEmployeesByPosition(1L, "ceo").size());
    }

    @Test
    @DisplayName("getPeers returns same-dept employees excluding self")
    void getPeers() {
        assertTrue(service.getPeers(null).isEmpty());

        Map<String, Object> e1 = emp("e1", "d1", null);
        when(dynamicDataService.getById("org_employee", "e1")).thenReturn(e1);
        when(dynamicDataService.list(eq("org_employee"), any(DynamicQueryRequest.class)))
            .thenReturn(page(List.of(e1, emp("e2", "d1", null))));
        List<Map<String, Object>> peers = service.getPeers("e1");
        assertEquals(1, peers.size());
        assertEquals("e2", peers.get(0).get("pid"));
    }

    @Test
    @DisplayName("getPeers returns empty when employee missing or no dept")
    void getPeersGuards() {
        when(dynamicDataService.getById("org_employee", "missing")).thenReturn(null);
        assertTrue(service.getPeers("missing").isEmpty());

        Map<String, Object> e = emp("e1", null, null);
        e.put("org_emp_dept_id", null);
        when(dynamicDataService.getById("org_employee", "e1")).thenReturn(e);
        assertTrue(service.getPeers("e1").isEmpty());
    }

    @Test
    @DisplayName("getSubordinates BFS walks tree without cycles")
    void getSubordinates() {
        assertTrue(service.getSubordinates(null).isEmpty());

        // root -> a, b ; a -> c
        Map<String, Object> root = emp("root", "d1", null);
        Map<String, Object> a = emp("a", "d1", "root");
        Map<String, Object> b = emp("b", "d1", "root");
        Map<String, Object> c = emp("c", "d1", "a");

        // first iteration: managerPid=root -> [a, b]
        // second: a -> [c], b -> []
        // third: c -> []
        lenient().when(dynamicDataService.list(eq("org_employee"), any(DynamicQueryRequest.class)))
            .thenAnswer(inv -> {
                DynamicQueryRequest req = inv.getArgument(1);
                String mgr = (String) req.getConditions().get(0).getValue();
                if ("root".equals(mgr)) return page(List.of(a, b));
                if ("a".equals(mgr)) return page(List.of(c));
                return page(List.of());
            });

        List<Map<String, Object>> subs = service.getSubordinates("root");
        assertEquals(3, subs.size());
    }

    @Test
    @DisplayName("getOrgPath walks parent chain")
    void getOrgPath() {
        assertTrue(service.getOrgPath(null).isEmpty());

        when(dynamicDataService.getById("org_employee", "missing")).thenReturn(null);
        assertTrue(service.getOrgPath("missing").isEmpty());

        Map<String, Object> e = emp("e1", "d2", null);
        Map<String, Object> d2 = dept("d2", "child", "d1", null);
        Map<String, Object> d1 = dept("d1", "root", null, null);
        when(dynamicDataService.getById("org_employee", "e1")).thenReturn(e);
        when(dynamicDataService.getById("org_department", "d2")).thenReturn(d2);
        when(dynamicDataService.getById("org_department", "d1")).thenReturn(d1);

        List<Map<String, Object>> path = service.getOrgPath("e1");
        assertEquals(2, path.size());
        assertEquals("d2", path.get(0).get("pid"));
        assertEquals("d1", path.get(1).get("pid"));
    }

    @Test
    @DisplayName("getOrgPath empty when employee has no dept")
    void getOrgPathNoDept() {
        Map<String, Object> e = new HashMap<>();
        e.put("pid", "e1");
        e.put("org_emp_dept_id", null);
        when(dynamicDataService.getById("org_employee", "e1")).thenReturn(e);
        assertTrue(service.getOrgPath("e1").isEmpty());
    }

    @Test
    @DisplayName("toEmployeeDTO resolves dept and position names")
    void toEmployeeDTO() {
        Map<String, Object> e = new HashMap<>();
        e.put("pid", "e1");
        e.put("org_emp_name", "Alice");
        e.put("org_emp_dept_id", "d1");
        e.put("org_emp_position_id", "p1");

        when(dynamicDataService.getById("org_department", "d1")).thenReturn(dept("d1", "Eng", null, null));
        Map<String, Object> pos = new HashMap<>();
        pos.put("org_pos_name", "Engineer");
        when(dynamicDataService.getById("org_position", "p1")).thenReturn(pos);

        OrgEmployeeDTO dto = service.toEmployeeDTO(e);
        assertEquals("Alice", dto.name());
        assertEquals("Eng", dto.deptName());
        assertEquals("Engineer", dto.positionName());
    }

    @Test
    @DisplayName("toEmployeeDTO with null dept/position pids")
    void toEmployeeDTONulls() {
        OrgEmployeeDTO dto = service.toEmployeeDTO(new HashMap<>());
        assertNull(dto.deptName());
        assertNull(dto.positionName());
    }
}
