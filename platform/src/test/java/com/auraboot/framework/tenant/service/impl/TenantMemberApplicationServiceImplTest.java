package com.auraboot.framework.tenant.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.auth.service.PasswordManagementService;
import com.auraboot.framework.auth.service.PasswordPolicyService;
import com.auraboot.framework.auth.service.SessionManagementService;
import com.auraboot.framework.common.constant.StatusConstants;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.organization.service.TeamMemberService;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.dao.mapper.TenantMemberMapper;
import com.auraboot.framework.tenant.dto.MemberQueryRequest;
import com.auraboot.framework.tenant.dto.MemberResponse;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.offboarding.TenantMemberOffboardingCoordinator;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.permission.service.PermissionFacade;
import com.auraboot.framework.permission.engine.model.PermissionResult;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TenantMemberApplicationServiceImpl")
class TenantMemberApplicationServiceImplTest {

    @Mock private TenantMemberService tenantMemberService;
    @Mock private UserService userService;
    @Mock private PasswordManagementService passwordManagementService;
    @Mock private PasswordPolicyService passwordPolicyService;
    @Mock private SessionManagementService sessionManagementService;
    @Mock private TeamMemberService teamMemberService;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private TenantMemberOffboardingCoordinator offboardingCoordinator;
    @Mock private UserPermissionService userPermissionService;
    @Mock private DynamicDataService dynamicDataService;
    @Mock private PermissionFacade permissionFacade;
    @Mock private TenantMemberMapper tenantMemberMapper;

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

    private TenantMember member(Long id, Long tenantId, Long userId, String status) {
        TenantMember m = new TenantMember();
        m.setId(id);
        m.setPid("mpid-" + id);
        m.setTenantId(tenantId);
        m.setUserId(userId);
        m.setStatus(status);
        return m;
    }

    private User u(Long id, String email) {
        User u = new User();
        u.setId(id);
        u.setPid("up-" + id);
        u.setEmail(email);
        u.setNickName("用户 " + id);
        return u;
    }

    @Test
    void offboardingImpactRejectsCallerWithoutTheRequestedAction() {
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);
        when(tenantMemberService.findByPid("mpid-2")).thenReturn(member(2L, 99L, 8L, "active"));
        assertEquals(ResponseCode.FORBIDDEN, assertThrows(BusinessException.class,
                () -> service.inspectOffboardingImpact("mpid-2", null, "suspend", 7L)).getResponseCode());
        verify(offboardingCoordinator, never()).inspect(any(), any(), any(), any());
    }

    @Test
    void offboardingImpactChecksTheRealModelMapWithTheMemberSubject() {
        TenantMember target = member(2L, 99L, 8L, "active");
        Map<String, Object> record = Map.of("pid", target.getPid(), "created_by", 8L);
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);
        metaContextMock.when(MetaContext::getCurrentMemberId).thenReturn(77L);
        metaContextMock.when(() -> MetaContext.runWithCommandPermitScope(eq("ALL"),
                Mockito.<java.util.function.Supplier<Map<String, Object>>>any()))
                .thenAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(1)).get());
        when(tenantMemberService.findByPid(target.getPid())).thenReturn(target);
        when(userPermissionService.hasPermission(7L, "admin_tenant_member")).thenReturn(false);
        when(userPermissionService.hasPermission(7L, "model.tenant_member.suspend")).thenReturn(true);
        when(dynamicDataService.getById("tenant_member", target.getPid())).thenReturn(record);
        when(permissionFacade.canOperate(77L, "tenant_member", "read", record))
                .thenReturn(PermissionResult.allow(List.of()));
        service.inspectOffboardingImpact(target.getPid(), null, "suspend", 7L);
        verify(permissionFacade).canOperate(77L, "tenant_member", "read", record);
        verify(offboardingCoordinator).inspect(target, null, 7L,
                com.auraboot.framework.tenant.offboarding.TenantMemberOffboardingAction.SUSPEND);
    }

    @Test
    void offboardingImpactRejectsAnUnreadableTargetEvenWithTheAction() {
        TenantMember target = member(2L, 99L, 8L, "active");
        Map<String, Object> record = Map.of("pid", target.getPid(), "created_by", 8L);
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);
        metaContextMock.when(MetaContext::getCurrentMemberId).thenReturn(77L);
        metaContextMock.when(() -> MetaContext.runWithCommandPermitScope(eq("ALL"),
                Mockito.<java.util.function.Supplier<Map<String, Object>>>any()))
                .thenAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(1)).get());
        when(tenantMemberService.findByPid(target.getPid())).thenReturn(target);
        when(userPermissionService.hasPermission(7L, "admin_tenant_member")).thenReturn(false);
        when(userPermissionService.hasPermission(7L, "model.tenant_member.suspend")).thenReturn(true);
        when(dynamicDataService.getById("tenant_member", target.getPid())).thenReturn(record);
        when(permissionFacade.canOperate(77L, "tenant_member", "read", record))
                .thenReturn(PermissionResult.deny("outside scope", List.of()));
        assertEquals(ResponseCode.FORBIDDEN, assertThrows(BusinessException.class,
                () -> service.inspectOffboardingImpact(target.getPid(), null, "suspend", 7L)).getResponseCode());
        verify(offboardingCoordinator, never()).inspect(any(), any(), any(), any());
    }

    @Test
    void offboardingLegacyAdministratorRetainsTenantWidePreflight() {
        TenantMember target = member(2L, 99L, 8L, "active");
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);
        when(tenantMemberService.findByPid(target.getPid())).thenReturn(target);
        when(userPermissionService.hasPermission(7L, "admin_tenant_member")).thenReturn(true);
        service.inspectOffboardingImpact(target.getPid(), null, "remove", 7L);
        verify(offboardingCoordinator).inspect(target, null, 7L,
                com.auraboot.framework.tenant.offboarding.TenantMemberOffboardingAction.REMOVE);
        verify(dynamicDataService, never()).getById(anyString(), anyString());
    }

    @Test
    void offboardingPreflightCannotCrossTenantBoundaries() {
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);
        when(tenantMemberService.findByPid("mpid-2")).thenReturn(member(2L, 100L, 8L, "active"));
        assertEquals(ResponseCode.FORBIDDEN, assertThrows(BusinessException.class,
                () -> service.inspectOffboardingImpact("mpid-2", null, "suspend", 7L)).getResponseCode());
        verify(offboardingCoordinator, never()).inspect(any(), any(), any(), any());
    }

    @Test
    void offboardingCandidatesRequireTheActualRemovalAction() {
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);
        when(tenantMemberService.findByPid("mpid-2")).thenReturn(member(2L, 99L, 8L, "active"));
        assertEquals(ResponseCode.FORBIDDEN, assertThrows(BusinessException.class,
                () -> service.listOffboardingCandidates("mpid-2", "remove", 7L)).getResponseCode());
        verify(userPermissionService).hasPermission(7L, "model.tenant_member.delete");
        verify(tenantMemberMapper, never()).findActiveOffboardingCandidates(anyLong(), anyString());
    }

    @Test
    void offboardingCandidatesFilterRecordsOutsideTheReadScope() {
        TenantMember target = member(2L, 99L, 8L, "active");
        Map<String, Object> targetRecord = Map.of("pid", target.getPid(), "created_by", 7L);
        Map<String, Object> visible = Map.of("pid", "visible", "created_by", 7L);
        Map<String, Object> hidden = Map.of("pid", "hidden", "created_by", 8L);
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);
        metaContextMock.when(MetaContext::getCurrentMemberId).thenReturn(77L);
        metaContextMock.when(() -> MetaContext.runWithCommandPermitScope(eq("ALL"),
                Mockito.<java.util.function.Supplier<Map<String, Object>>>any()))
                .thenAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(1)).get());
        when(tenantMemberService.findByPid(target.getPid())).thenReturn(target);
        when(userPermissionService.hasPermission(7L, "admin_tenant_member")).thenReturn(false);
        when(userPermissionService.hasPermission(7L, "model.tenant_member.suspend")).thenReturn(true);
        when(dynamicDataService.getById("tenant_member", target.getPid())).thenReturn(targetRecord);
        when(dynamicDataService.getById("tenant_member", "visible")).thenReturn(visible);
        when(dynamicDataService.getById("tenant_member", "hidden")).thenReturn(hidden);
        when(permissionFacade.canOperate(77L, "tenant_member", "read", targetRecord))
                .thenReturn(PermissionResult.allow(List.of()));
        when(permissionFacade.canOperate(77L, "tenant_member", "read", visible))
                .thenReturn(PermissionResult.allow(List.of()));
        when(permissionFacade.canOperate(77L, "tenant_member", "read", hidden))
                .thenReturn(PermissionResult.deny("outside scope", List.of()));
        when(tenantMemberMapper.findActiveOffboardingCandidates(99L, target.getPid())).thenReturn(List.of(
                Map.of("memberPid", "visible", "displayName", "Allowed", "email", "allowed@example.test"),
                Map.of("memberPid", "hidden", "displayName", "Hidden", "email", "hidden@example.test")));
        var result = service.listOffboardingCandidates(target.getPid(), "suspend", 7L);
        assertEquals(1, result.size());
        assertEquals("visible", result.get(0).memberPid());
    }

    @Test
    @DisplayName("searchMembers throws when no tenant context and no membership")
    void searchMembersNoTenant() {
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(null);
        when(tenantMemberService.getTenantIdByUserId(7L)).thenReturn(null);

        MemberQueryRequest req = new MemberQueryRequest();
        req.setPageNum(1);
        req.setPageSize(10);

        assertThrows(BusinessException.class, () -> service.searchMembers(req, 7L));
    }

    @Test
    @DisplayName("searchMembers paginates and converts to response")
    void searchMembersOk() {
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);
        Page<TenantMember> page = new Page<>(1, 10);
        page.setRecords(List.of(member(1L, 99L, 7L, StatusConstants.ACTIVE)));
        page.setTotal(1L);
        when(tenantMemberService.findMembers(anyInt(), anyInt(), eq(99L), any(), any(), any()))
                .thenReturn(page);
        when(userService.findByUserId(7L)).thenReturn(u(7L, "u@x.com"));

        MemberQueryRequest req = new MemberQueryRequest();
        req.setPageNum(1);
        req.setPageSize(10);

        var result = service.searchMembers(req, 7L);
        assertNotNull(result);
        assertEquals(1, result.getRecords().size());
        assertEquals("用户 7", result.getRecords().get(0).getUser().getRealName());
    }

    @Test
    @DisplayName("getMemberById throws when not found")
    void getMemberMissing() {
        when(tenantMemberService.findByPid("p")).thenReturn(null);
        assertThrows(BusinessException.class, () -> service.getMemberById("p", 7L));
    }

    @Test
    @DisplayName("getMemberById denies cross-tenant access")
    void getMemberCrossTenant() {
        when(tenantMemberService.findByPid("p")).thenReturn(member(1L, 100L, 5L, StatusConstants.ACTIVE));
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);

        assertThrows(BusinessException.class, () -> service.getMemberById("p", 7L));
    }

    @Test
    @DisplayName("getMemberById returns response when authorized")
    void getMemberOk() {
        when(tenantMemberService.findByPid("p")).thenReturn(member(1L, 99L, 5L, StatusConstants.ACTIVE));
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);
        when(userService.findByUserId(5L)).thenReturn(u(5L, "u@x.com"));
        when(userPermissionService.hasPermission(7L, MetaPermission.TENANT_MEMBER_MANAGE)).thenReturn(true);

        MemberResponse resp = service.getMemberById("p", 7L);
        assertNotNull(resp);
    }

    @Test
    @DisplayName("approveMember invalid action throws")
    void approveInvalidAction() {
        when(tenantMemberService.findByPid("p")).thenReturn(member(1L, 99L, 5L, StatusConstants.PENDING));
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);

        assertThrows(BusinessException.class,
                () -> service.approveMember("p", "ignore", null, 7L));
    }

    @Test
    @DisplayName("approveMember approve sets ACTIVE")
    void approveApproves() {
        TenantMember m = member(1L, 99L, 5L, StatusConstants.PENDING);
        when(tenantMemberService.findByPid("p")).thenReturn(m);
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);
        when(tenantMemberService.updateMember(any(TenantMember.class))).thenReturn(m);

        assertTrue(service.approveMember("p", "approve", null, 7L));
        assertEquals(StatusConstants.ACTIVE, m.getStatus());
    }

    @Test
    @DisplayName("approveMember reject persists rejection reason in extensions")
    void approveRejectsWithReason() {
        TenantMember m = member(1L, 99L, 5L, StatusConstants.PENDING);
        when(tenantMemberService.findByPid("p")).thenReturn(m);
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);
        when(tenantMemberService.updateMember(any(TenantMember.class))).thenReturn(m);

        assertTrue(service.approveMember("p", "reject", "spam", 7L));
        assertEquals(StatusConstants.REJECTED, m.getStatus());
        assertNotNull(m.getExtensions());
        assertTrue(m.getExtensions().contains("spam"));
    }

    @Test
    @DisplayName("approveMember cross-tenant denied")
    void approveCrossTenant() {
        when(tenantMemberService.findByPid("p")).thenReturn(member(1L, 100L, 5L, StatusConstants.PENDING));
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);

        assertThrows(BusinessException.class, () -> service.approveMember("p", "approve", null, 7L));
    }

    @Test
    @DisplayName("updateMemberStatus dispatches to activate/deactivate/suspend")
    void updateMemberStatusDispatches() {
        TenantMember m = member(1L, 99L, 5L, StatusConstants.ACTIVE);
        m.setEmployeeId(88L);
        when(tenantMemberService.findByPid("p")).thenReturn(m);
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);
        when(tenantMemberService.activateMember(1L)).thenReturn(true);
        when(tenantMemberService.deactivateMember(1L)).thenReturn(true);
        when(tenantMemberService.suspendMember(1L, "r")).thenReturn(true);

        assertTrue(service.updateMemberStatus("p", StatusConstants.ACTIVE, null, 7L));
        assertTrue(service.updateMemberStatus("p", StatusConstants.INACTIVE, null, 7L));
        assertTrue(service.updateMemberStatus("p", StatusConstants.SUSPENDED, "r", 7L));
        verify(tenantMemberService).activateMember(1L);
        verify(tenantMemberService).deactivateMember(1L);
        verify(tenantMemberService).suspendMember(1L, "r");
        verify(offboardingCoordinator).prepare(m, null, 7L,
                com.auraboot.framework.tenant.offboarding.TenantMemberOffboardingAction.DEACTIVATE);
        verify(offboardingCoordinator).prepare(m, null, 7L,
                com.auraboot.framework.tenant.offboarding.TenantMemberOffboardingAction.SUSPEND);
        verify(jdbcTemplate).update(
                "UPDATE mt_org_employee SET org_emp_status = ?, updated_at = NOW() WHERE id = ?",
                "resigned", 88L);
        verify(sessionManagementService, times(2)).revokeAllSessions(5L);
    }

    @Test
    @DisplayName("leave member marks employee resigned by member pid when no employee id")
    void updateMemberStatusLeaveMarksEmployeeByMemberPid() {
        TenantMember m = member(1L, 99L, 5L, StatusConstants.ACTIVE);
        when(tenantMemberService.findByPid("p")).thenReturn(m);
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);
        when(tenantMemberService.deactivateMember(1L)).thenReturn(true);

        assertTrue(service.updateMemberStatus("p", StatusConstants.INACTIVE, null, 7L));

        verify(jdbcTemplate).update(
                "UPDATE mt_org_employee SET org_emp_status = ?, updated_at = NOW() WHERE org_emp_member_id = ?",
                "resigned", "mpid-1");
        verify(sessionManagementService).revokeAllSessions(5L);
    }

    @Test
    @DisplayName("updateMemberStatus invalid status throws")
    void updateMemberStatusInvalid() {
        TenantMember m = member(1L, 99L, 5L, StatusConstants.ACTIVE);
        when(tenantMemberService.findByPid("p")).thenReturn(m);
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);

        assertThrows(BusinessException.class,
                () -> service.updateMemberStatus("p", "weird", null, 7L));
    }

    @Test
    @DisplayName("removeMember refuses self-removal")
    void removeSelf() {
        TenantMember m = member(1L, 99L, 7L, StatusConstants.ACTIVE);
        when(tenantMemberService.findByPid("p")).thenReturn(m);
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);

        assertThrows(BusinessException.class, () -> service.removeMember("p", 7L));
        verify(tenantMemberService, never()).removeMember(anyLong());
    }

    @Test
    @DisplayName("removeMember succeeds for another user")
    void removeOk() {
        TenantMember m = member(1L, 99L, 5L, StatusConstants.ACTIVE);
        when(tenantMemberService.findByPid("p")).thenReturn(m);
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);
        when(tenantMemberService.removeMember(1L)).thenReturn(true);

        assertTrue(service.removeMember("p", 7L));
        verify(offboardingCoordinator).prepare(m, null, 7L,
                com.auraboot.framework.tenant.offboarding.TenantMemberOffboardingAction.REMOVE);
    }

    @Test
    @DisplayName("sendPasswordResetEmail throws when user has no email")
    void sendResetNoEmail() {
        TenantMember m = member(1L, 99L, 5L, StatusConstants.ACTIVE);
        when(tenantMemberService.findByPid("p")).thenReturn(m);
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);
        User target = u(5L, null);
        when(userService.findByUserId(5L)).thenReturn(target);

        assertThrows(BusinessException.class, () -> service.sendPasswordResetEmail("p", 7L));
    }

    @Test
    @DisplayName("sendPasswordResetEmail succeeds when user email present")
    void sendResetOk() {
        TenantMember m = member(1L, 99L, 5L, StatusConstants.ACTIVE);
        when(tenantMemberService.findByPid("p")).thenReturn(m);
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);
        when(userService.findByUserId(5L)).thenReturn(u(5L, "x@y.com"));

        assertTrue(service.sendPasswordResetEmail("p", 7L));
        verify(passwordManagementService).sendPasswordResetEmail(5L);
    }

    @Test
    @DisplayName("sendPasswordResetEmail throws when target user missing")
    void sendResetUserMissing() {
        TenantMember m = member(1L, 99L, 5L, StatusConstants.ACTIVE);
        when(tenantMemberService.findByPid("p")).thenReturn(m);
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);
        when(userService.findByUserId(5L)).thenReturn(null);

        assertThrows(BusinessException.class, () -> service.sendPasswordResetEmail("p", 7L));
    }

    @Test
    @DisplayName("resetMemberPasswordByAdmin retries until temporary password satisfies policy")
    void resetMemberPasswordByAdminUsesPolicyCompliantTemporaryPassword() {
        TenantMember m = member(1L, 99L, 5L, StatusConstants.ACTIVE);
        when(tenantMemberService.findByPid("p")).thenReturn(m);
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);
        when(userService.findByUserId(5L)).thenReturn(u(5L, "x@y.com"));
        when(passwordPolicyService.validate(anyString()))
                .thenReturn(List.of("Password must contain at least one digit"))
                .thenReturn(List.of());

        String temporaryPassword = service.resetMemberPasswordByAdmin("p", 7L);

        assertNotNull(temporaryPassword);
        verify(passwordPolicyService, times(2)).validate(anyString());
        verify(passwordManagementService).resetPasswordByAdmin(eq("up-5"), eq(temporaryPassword));
    }

    @Test
    @DisplayName("batchRemoveMembers no-op for empty input")
    void batchRemoveEmpty() {
        assertTrue(service.batchRemoveMembers(Collections.emptyList(), 7L));
        verify(tenantMemberService, never()).removeMember(anyLong());
    }

    @Test
    @DisplayName("batchRemoveMembers fails atomically before deleting when any member is invalid")
    void batchRemoveFiltering() {
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);
        when(tenantMemberService.findByPid("p1")).thenReturn(member(1L, 99L, 5L, StatusConstants.ACTIVE));
        when(tenantMemberService.findByPid("p2")).thenReturn(member(2L, 99L, 7L, StatusConstants.ACTIVE)); // self
        assertThrows(BusinessException.class,
                () -> service.batchRemoveMembers(List.of("p1", "p2"), 7L));
        verify(tenantMemberService, never()).removeMember(1L);
        verify(tenantMemberService, never()).removeMember(2L);
        verify(tenantMemberService, never()).removeMember(3L);
    }

    @Test
    @DisplayName("getMemberTeams throws when member missing")
    void getMemberTeamsMissing() {
        when(tenantMemberService.findByPid("p")).thenReturn(null);
        assertThrows(BusinessException.class, () -> service.getMemberTeams("p"));
    }

    @Test
    @DisplayName("getMemberTeams returns teams for member's user")
    void getMemberTeamsOk() {
        when(tenantMemberService.findByPid("p")).thenReturn(member(1L, 99L, 5L, StatusConstants.ACTIVE));
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);
        metaContextMock.when(MetaContext::getCurrentUserId).thenReturn(7L);
        when(userPermissionService.hasPermission(7L, MetaPermission.TENANT_MEMBER_MANAGE)).thenReturn(true);
        List<Map<String, Object>> teams = List.of(Map.of("id", 1L));
        when(teamMemberService.getTeamMembershipsByUserId(5L, 99L)).thenReturn(teams);

        assertEquals(teams, service.getMemberTeams("p"));
    }

}
