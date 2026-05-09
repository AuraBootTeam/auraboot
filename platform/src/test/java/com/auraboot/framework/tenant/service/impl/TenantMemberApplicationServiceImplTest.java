package com.auraboot.framework.tenant.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.service.PasswordManagementService;
import com.auraboot.framework.common.constant.StatusConstants;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.organization.service.TeamMemberService;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.dto.MemberQueryRequest;
import com.auraboot.framework.tenant.dto.MemberResponse;
import com.auraboot.framework.tenant.service.TenantMemberService;
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
import org.springframework.core.io.Resource;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TenantMemberApplicationServiceImpl")
class TenantMemberApplicationServiceImplTest {

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
        return u;
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
    @DisplayName("batchRemoveMembers no-op for empty input")
    void batchRemoveEmpty() {
        assertTrue(service.batchRemoveMembers(Collections.emptyList(), 7L));
        verify(tenantMemberService, never()).removeMember(anyLong());
    }

    @Test
    @DisplayName("batchRemoveMembers skips self / cross-tenant / missing")
    void batchRemoveFiltering() {
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);
        when(tenantMemberService.findByPid("p1")).thenReturn(member(1L, 99L, 5L, StatusConstants.ACTIVE));
        when(tenantMemberService.findByPid("p2")).thenReturn(member(2L, 99L, 7L, StatusConstants.ACTIVE)); // self
        when(tenantMemberService.findByPid("p3")).thenReturn(member(3L, 100L, 8L, StatusConstants.ACTIVE)); // cross
        when(tenantMemberService.findByPid("p4")).thenReturn(null); // missing
        when(tenantMemberService.removeMember(1L)).thenReturn(true);

        assertTrue(service.batchRemoveMembers(List.of("p1", "p2", "p3", "p4"), 7L));
        verify(tenantMemberService).removeMember(1L);
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
        List<Map<String, Object>> teams = List.of(Map.of("id", 1L));
        when(teamMemberService.getTeamMembershipsByUserId(5L, 99L)).thenReturn(teams);

        assertEquals(teams, service.getMemberTeams("p"));
    }

    @Test
    @DisplayName("downloadImportTemplate produces non-empty xlsx resource")
    void downloadTemplate() throws Exception {
        Resource res = service.downloadImportTemplate();
        assertNotNull(res);
        assertTrue(res.contentLength() > 0);
    }

    @Test
    @DisplayName("importMembers (MultipartFile) is unsupported")
    void importMembersFileUnsupported() {
        assertThrows(BusinessException.class,
                () -> service.importMembers((org.springframework.web.multipart.MultipartFile) null, 7L));
    }

    @Test
    @DisplayName("importMembers (rows) rejects empty input")
    void importMembersRowsEmpty() {
        assertThrows(BusinessException.class, () -> service.importMembers((List) null, 7L));
        assertThrows(BusinessException.class, () -> service.importMembers(Collections.emptyList(), 7L));
    }
}
