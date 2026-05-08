package com.auraboot.framework.tenant.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.service.PasswordManagementService;
import com.auraboot.framework.common.constant.StatusConstants;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.organization.service.TeamMemberService;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.dto.TenantMemberImportResult;
import com.auraboot.framework.tenant.dto.TenantMemberImportRow;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Branch coverage for TenantMemberApplicationServiceImpl.importMembers(rows, userId)
 * and related private helpers (loadNameToPidMap / createEmployeeRecord / extractId /
 * resolveRelatedPid / normalizeEmail / generateTemporaryPassword).
 *
 * Avoids overlap with {@link TenantMemberApplicationServiceImplTest}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TenantMemberApplicationServiceImpl import branches")
class TenantMemberApplicationServiceImplBranchTest {

    @Mock private TenantMemberService tenantMemberService;
    @Mock private UserService userService;
    @Mock private PasswordManagementService passwordManagementService;
    @Mock private TeamMemberService teamMemberService;
    @Mock private DynamicDataService dynamicDataService;

    @InjectMocks
    private TenantMemberApplicationServiceImpl service;

    private MockedStatic<MetaContext> metaContextMock;

    @BeforeEach
    void setUp() {
        metaContextMock = Mockito.mockStatic(MetaContext.class);
    }

    @AfterEach
    void tearDown() {
        if (metaContextMock != null) metaContextMock.close();
    }

    private TenantMemberImportRow row(String name, String email, String phone, String dept, String pos) {
        TenantMemberImportRow r = new TenantMemberImportRow();
        r.setName(name);
        r.setEmail(email);
        r.setPhone(phone);
        r.setDepartment(dept);
        r.setPosition(pos);
        return r;
    }

    private User user(Long id, String email) {
        User u = new User();
        u.setId(id);
        u.setPid("upid-" + id);
        u.setEmail(email);
        return u;
    }

    private TenantMember newMember(Long id, Long tenantId, Long userId) {
        TenantMember m = new TenantMember();
        m.setId(id);
        m.setPid("mpid-" + id);
        m.setTenantId(tenantId);
        m.setUserId(userId);
        return m;
    }

    @Test
    @DisplayName("importMembers throws when no tenant context resolvable")
    void importNoTenant() {
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(null);
        when(tenantMemberService.getTenantIdByUserId(7L)).thenReturn(null);

        assertThrows(BusinessException.class,
                () -> service.importMembers(List.of(row("A", "a@x.com", null, null, null)), 7L));
    }

    @Test
    @DisplayName("importMembers skips fully blank rows and counts only non-blank")
    void importSkipsBlankRows() {
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);

        TenantMemberImportRow blank = row(null, null, null, null, null);
        TenantMemberImportRow good = row("Alice", "Alice@X.com", "13800000000", null, null);

        when(userService.findByEmail("alice@x.com")).thenReturn(null);
        User newUser = user(5L, "alice@x.com");
        when(userService.signUp(eq("alice@x.com"), anyString(), eq("Alice"))).thenReturn(newUser);
        when(tenantMemberService.findByTenantIdAndUserId(99L, 5L)).thenReturn(null);
        when(tenantMemberService.addMember(5L, 99L, StatusConstants.PENDING)).thenReturn(newMember(1L, 99L, 5L));

        TenantMemberImportResult result = service.importMembers(List.of(blank, good), 7L);

        assertEquals(1, result.getTotalRows());
        assertEquals(1, result.getSuccessCount());
        assertEquals(1, result.getInvitedCount());
        assertEquals(0, result.getExistingUserBoundCount());
        assertTrue(result.getErrors().isEmpty());
        verify(passwordManagementService).sendPasswordResetEmail(5L);
        // user mobile should be set on update
        verify(userService).update(any(User.class));
    }

    @Test
    @DisplayName("importMembers reports error rows: name missing / email missing / duplicate member")
    void importPerRowErrors() {
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);

        // duplicate-row setup
        when(userService.findByEmail("dup@x.com")).thenReturn(user(11L, "dup@x.com"));
        when(tenantMemberService.findByTenantIdAndUserId(99L, 11L)).thenReturn(newMember(2L, 99L, 11L));

        List<TenantMemberImportRow> rows = List.of(
                row(null, "x@x.com", null, null, null),       // missing name
                row("Bob", null, null, null, null),           // missing email
                row("Dup", "dup@x.com", null, null, null)     // duplicate member
        );

        TenantMemberImportResult result = service.importMembers(rows, 7L);

        assertEquals(3, result.getTotalRows());
        assertEquals(0, result.getSuccessCount());
        assertEquals(3, result.getErrorCount());
        // error row indices reflect index+2 (1-based with header row)
        assertEquals(2, result.getErrors().get(0).getRowNumber());
        assertEquals(3, result.getErrors().get(1).getRowNumber());
        assertEquals(4, result.getErrors().get(2).getRowNumber());
    }

    @Test
    @DisplayName("importMembers existing user adds phone when blank, marks ACTIVE, no invite email")
    void importExistingUserBindsPhoneAndActivates() {
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);

        User existing = user(20L, "ex@x.com");
        // mobile blank → must be set
        when(userService.findByEmail("ex@x.com")).thenReturn(existing);
        when(tenantMemberService.findByTenantIdAndUserId(99L, 20L)).thenReturn(null);
        when(tenantMemberService.addMember(20L, 99L, StatusConstants.ACTIVE))
                .thenReturn(newMember(3L, 99L, 20L));

        TenantMemberImportResult result = service.importMembers(
                List.of(row("Existing", "ex@x.com", "13912345678", null, null)), 7L);

        assertEquals(1, result.getSuccessCount());
        assertEquals(1, result.getExistingUserBoundCount());
        assertEquals(0, result.getInvitedCount());
        verify(passwordManagementService, never()).sendPasswordResetEmail(anyLong());
        verify(userService).update(existing);
        assertEquals("13912345678", existing.getMobile());
    }

    @Test
    @DisplayName("importMembers existing user with mobile already set is not updated")
    void importExistingUserMobileNotOverwritten() {
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);

        User existing = user(21L, "ex2@x.com");
        existing.setMobile("13800000000");
        when(userService.findByEmail("ex2@x.com")).thenReturn(existing);
        when(tenantMemberService.findByTenantIdAndUserId(99L, 21L)).thenReturn(null);
        when(tenantMemberService.addMember(21L, 99L, StatusConstants.ACTIVE))
                .thenReturn(newMember(4L, 99L, 21L));

        TenantMemberImportResult result = service.importMembers(
                List.of(row("Existing2", "ex2@x.com", "13900000000", null, null)), 7L);

        assertEquals(1, result.getSuccessCount());
        // userService.update should NOT be called — mobile already present
        verify(userService, never()).update(any(User.class));
    }

    @Test
    @DisplayName("importMembers with department + position resolves pids and creates employee")
    void importCreatesEmployeeRecord() {
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);

        User existing = user(30L, "emp@x.com");
        existing.setMobile("13700000000");
        when(userService.findByEmail("emp@x.com")).thenReturn(existing);
        when(tenantMemberService.findByTenantIdAndUserId(99L, 30L)).thenReturn(null);
        TenantMember member = newMember(5L, 99L, 30L);
        when(tenantMemberService.addMember(30L, 99L, StatusConstants.ACTIVE)).thenReturn(member);

        // dept loadNameToPidMap
        Map<String, Object> deptRec = new HashMap<>();
        deptRec.put("org_dept_name", "销售部");
        deptRec.put("pid", "dept-pid-1");
        Map<String, Object> deptRecBlank = new HashMap<>();
        deptRecBlank.put("org_dept_name", "  ");
        deptRecBlank.put("pid", "dept-pid-blank");
        // pos loadNameToPidMap
        Map<String, Object> posRec = new HashMap<>();
        posRec.put("org_pos_name", "经理");
        posRec.put("pid", "pos-pid-1");

        when(dynamicDataService.list(eq("org_department"), any()))
                .thenReturn(PaginationResult.of(List.of(deptRec, deptRecBlank), 2L, 1, 500));
        when(dynamicDataService.list(eq("org_position"), any()))
                .thenReturn(PaginationResult.of(List.of(posRec), 1L, 1, 500));

        Map<String, Object> created = new HashMap<>();
        created.put("id", 12345L);
        when(dynamicDataService.create(eq("org_employee"), any())).thenReturn(created);
        when(tenantMemberService.updateMember(any(TenantMember.class))).thenReturn(member);

        TenantMemberImportResult result = service.importMembers(
                List.of(row("Emp", "emp@x.com", null, "销售部", "经理")), 7L);

        assertEquals(1, result.getSuccessCount());
        assertEquals(1, result.getEmployeeCreatedCount());

        ArgumentCaptor<Map<String, Object>> empCaptor = ArgumentCaptor.forClass(Map.class);
        verify(dynamicDataService).create(eq("org_employee"), empCaptor.capture());
        Map<String, Object> empData = empCaptor.getValue();
        assertEquals("dept-pid-1", empData.get("org_emp_dept_id"));
        assertEquals("pos-pid-1", empData.get("org_emp_position_id"));
        assertEquals("emp@x.com", empData.get("org_emp_email"));
        assertEquals("Emp", empData.get("org_emp_name"));
        assertEquals(12345L, member.getEmployeeId());
    }

    @Test
    @DisplayName("importMembers reports error when department/position not found")
    void importDepartmentNotFound() {
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);

        User existing = user(40L, "emp2@x.com");
        existing.setMobile("13700000001");
        when(userService.findByEmail("emp2@x.com")).thenReturn(existing);
        when(tenantMemberService.findByTenantIdAndUserId(99L, 40L)).thenReturn(null);
        when(tenantMemberService.addMember(40L, 99L, StatusConstants.ACTIVE))
                .thenReturn(newMember(6L, 99L, 40L));
        when(dynamicDataService.list(eq("org_department"), any()))
                .thenReturn(PaginationResult.of(List.of(), 0L, 1, 500));

        TenantMemberImportResult result = service.importMembers(
                List.of(row("Emp2", "emp2@x.com", null, "未知部门", null)), 7L);

        assertEquals(0, result.getSuccessCount());
        assertEquals(1, result.getErrorCount());
        assertTrue(result.getErrors().get(0).getReason().contains("部门"));
    }

    @Test
    @DisplayName("importMembers handles String id from create() result (extractId branch)")
    void importExtractIdString() {
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);

        User existing = user(50L, "emp3@x.com");
        existing.setMobile("13700000002");
        when(userService.findByEmail("emp3@x.com")).thenReturn(existing);
        when(tenantMemberService.findByTenantIdAndUserId(99L, 50L)).thenReturn(null);
        TenantMember m = newMember(7L, 99L, 50L);
        when(tenantMemberService.addMember(50L, 99L, StatusConstants.ACTIVE)).thenReturn(m);

        Map<String, Object> deptRec = new HashMap<>();
        deptRec.put("org_dept_name", "Dept");
        deptRec.put("pid", "d1");
        when(dynamicDataService.list(eq("org_department"), any()))
                .thenReturn(PaginationResult.of(List.of(deptRec), 1L, 1, 500));

        Map<String, Object> created = new HashMap<>();
        created.put("id", "9876");
        when(dynamicDataService.create(eq("org_employee"), any())).thenReturn(created);
        when(tenantMemberService.updateMember(any(TenantMember.class))).thenReturn(m);

        TenantMemberImportResult result = service.importMembers(
                List.of(row("E3", "emp3@x.com", null, "Dept", null)), 7L);
        assertEquals(1, result.getSuccessCount());
        assertEquals(9876L, m.getEmployeeId());
    }

    @Test
    @DisplayName("importMembers extractId throws when result has no parseable id")
    void importExtractIdInvalid() {
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);

        User existing = user(60L, "emp4@x.com");
        existing.setMobile("13700000003");
        when(userService.findByEmail("emp4@x.com")).thenReturn(existing);
        when(tenantMemberService.findByTenantIdAndUserId(99L, 60L)).thenReturn(null);
        when(tenantMemberService.addMember(60L, 99L, StatusConstants.ACTIVE))
                .thenReturn(newMember(8L, 99L, 60L));

        Map<String, Object> deptRec = new HashMap<>();
        deptRec.put("org_dept_name", "D");
        deptRec.put("pid", "d2");
        when(dynamicDataService.list(eq("org_department"), any()))
                .thenReturn(PaginationResult.of(List.of(deptRec), 1L, 1, 500));

        Map<String, Object> created = new HashMap<>();
        created.put("id", new Object()); // not Number, not String
        when(dynamicDataService.create(eq("org_employee"), any())).thenReturn(created);

        TenantMemberImportResult result = service.importMembers(
                List.of(row("E4", "emp4@x.com", null, "D", null)), 7L);

        assertEquals(0, result.getSuccessCount());
        assertEquals(1, result.getErrorCount());
    }

    @Test
    @DisplayName("importMembers loads dept/position lookup once across multiple rows")
    void importLookupMapsCachedAcrossRows() {
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);

        Map<String, Object> deptRec = new HashMap<>();
        deptRec.put("org_dept_name", "Dept");
        deptRec.put("pid", "dpid");
        when(dynamicDataService.list(eq("org_department"), any()))
                .thenReturn(PaginationResult.of(List.of(deptRec), 1L, 1, 500));

        // first row
        User u1 = user(70L, "x1@y.com");
        u1.setMobile("1");
        when(userService.findByEmail("x1@y.com")).thenReturn(u1);
        when(tenantMemberService.findByTenantIdAndUserId(99L, 70L)).thenReturn(null);
        when(tenantMemberService.addMember(70L, 99L, StatusConstants.ACTIVE))
                .thenReturn(newMember(101L, 99L, 70L));
        // second row
        User u2 = user(71L, "x2@y.com");
        u2.setMobile("2");
        when(userService.findByEmail("x2@y.com")).thenReturn(u2);
        when(tenantMemberService.findByTenantIdAndUserId(99L, 71L)).thenReturn(null);
        when(tenantMemberService.addMember(71L, 99L, StatusConstants.ACTIVE))
                .thenReturn(newMember(102L, 99L, 71L));

        Map<String, Object> created = new HashMap<>();
        created.put("id", 1L);
        when(dynamicDataService.create(eq("org_employee"), any())).thenReturn(created);

        TenantMemberImportResult result = service.importMembers(List.of(
                row("Row1", "x1@y.com", null, "Dept", null),
                row("Row2", "x2@y.com", null, "Dept", null)
        ), 7L);

        assertEquals(2, result.getSuccessCount());
        // dept lookup should be called only once (cached) across the two rows
        verify(dynamicDataService, times(1)).list(eq("org_department"), any());
        verify(dynamicDataService, never()).list(eq("org_position"), any());
    }

    @Test
    @DisplayName("importMembers null/empty rows raise BadParam")
    void importEmptyRows() {
        assertThrows(BusinessException.class, () -> service.importMembers((List<TenantMemberImportRow>) null, 1L));
        assertThrows(BusinessException.class, () -> service.importMembers(List.of(), 1L));
    }
}
