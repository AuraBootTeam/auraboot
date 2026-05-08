package com.auraboot.framework.tenant.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.service.SessionManagementService;
import com.auraboot.framework.auth.util.JwtUtil;
import com.auraboot.framework.common.constant.StatusConstants;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.permission.service.AutoPermissionAssignmentService;
import com.auraboot.framework.menu.service.MenuService;
import com.auraboot.framework.plugin.service.BuiltinPluginImportService;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.auraboot.framework.tenant.dao.entity.Invitation;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.dto.TenantRequest;
import com.auraboot.framework.tenant.dto.TenantResponse;
import com.auraboot.framework.tenant.dto.TenantSelectionRequest;
import com.auraboot.framework.tenant.dto.TenantSelectionResponse;
import com.auraboot.framework.tenant.service.TenantBootstrapService;
import com.auraboot.framework.tenant.service.TenantInviteService;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

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
@DisplayName("TenantApplicationServiceImpl")
class TenantApplicationServiceImplTest {

    @Mock private TenantService tenantService;
    @Mock private TenantMemberService tenantMemberService;
    @Mock private TenantInviteService tenantInviteService;
    @Mock private UserService userService;
    @Mock private JwtUtil jwtUtil;
    @Mock private UserDetailsService userDetailsService;
    @Mock private SessionManagementService sessionManagementService;
    @Mock private RoleService roleService;
    @Mock private MenuService menuService;
    @Mock private UserRoleService userRoleService;
    @Mock private AutoPermissionAssignmentService autoPermissionAssignmentService;
    @Mock private TenantBootstrapService tenantBootstrapService;
    @Mock private BuiltinPluginImportService builtinPluginImportService;

    @InjectMocks
    private TenantApplicationServiceImpl service;

    private MockedStatic<MetaContext> metaContextMock;

    @AfterEach
    void tearDown() {
        if (metaContextMock != null) metaContextMock.close();
    }

    private User user(Long id, String email) {
        User u = new User();
        u.setId(id);
        u.setPid("upid-" + id);
        u.setEmail(email);
        u.setSecurityVersion(1);
        return u;
    }

    private Tenant tenant(Long id, String name) {
        Tenant t = new Tenant();
        t.setId(id);
        t.setPid("tpid-" + id);
        t.setName(name);
        return t;
    }

    @Test
    @DisplayName("getCurrentTenantInfo throws when no MetaContext")
    void getCurrentNoContext() {
        metaContextMock = Mockito.mockStatic(MetaContext.class);
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(null);

        assertThrows(ValidationException.class, () -> service.getCurrentTenantInfo(7L));
    }

    @Test
    @DisplayName("getCurrentTenantInfo throws when tenant missing")
    void getCurrentTenantMissing() {
        metaContextMock = Mockito.mockStatic(MetaContext.class);
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);
        when(tenantService.getById(99L)).thenReturn(null);

        assertThrows(ValidationException.class, () -> service.getCurrentTenantInfo(7L));
    }

    @Test
    @DisplayName("getCurrentTenantInfo returns response when found")
    void getCurrentTenantOk() {
        metaContextMock = Mockito.mockStatic(MetaContext.class);
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(99L);
        when(tenantService.getById(99L)).thenReturn(tenant(99L, "Acme"));

        TenantResponse resp = service.getCurrentTenantInfo(7L);
        assertNotNull(resp);
    }

    @Test
    @DisplayName("updateTenant throws when not found")
    void updateMissing() {
        when(tenantService.findByPid("p")).thenReturn(null);

        assertThrows(ValidationException.class,
                () -> service.updateTenant("p", new TenantRequest(), 7L));
    }

    @Test
    @DisplayName("updateTenant copies non-null fields and updates")
    void updateCopiesFields() {
        Tenant t = tenant(1L, "n");
        when(tenantService.findByPid("p")).thenReturn(t);
        when(tenantService.updateTenant(t)).thenReturn(t);
        TenantRequest req = new TenantRequest();
        req.setDisplayName("D");
        req.setIndustry("Tech");
        req.setContactEmail("e@x.com");
        req.setContactPhone("123");
        req.setDescription("desc");

        TenantResponse resp = service.updateTenant("p", req, 7L);

        assertNotNull(resp);
        assertEquals("D", t.getDisplayName());
        assertEquals("Tech", t.getIndustry());
        assertEquals("e@x.com", t.getContactEmail());
        assertEquals("123", t.getContactPhone());
        assertEquals("desc", t.getDescription());
    }

    @Test
    @DisplayName("createTenantForUser rejects duplicate name")
    void createDuplicate() {
        when(tenantService.findByName("acme")).thenReturn(tenant(1L, "acme"));
        TenantSelectionRequest req = new TenantSelectionRequest();
        req.setTenantName("acme");

        assertThrows(ValidationException.class, () -> service.createTenantForUser(req, user(7L, "u@x.com")));
    }

    @Test
    @DisplayName("createTenantForUser bootstraps tenant + assigns admin")
    void createForUserOk() {
        when(tenantService.findByName("acme")).thenReturn(null);
        Tenant created = tenant(99L, "acme");
        when(tenantService.createTenant(any(Tenant.class))).thenReturn(created);
        TenantBootstrapService.BootstrapResult bootstrapResult =
                TenantBootstrapService.BootstrapResult.success(3, 5, 10, 100);
        when(tenantBootstrapService.bootstrapTenant(99L, 7L)).thenReturn(bootstrapResult);
        when(userDetailsService.loadUserByUsername(anyString())).thenReturn(null);
        when(jwtUtil.generateTokenWithTenantId(any(), anyString(), anyLong(), anyInt()))
                .thenReturn("jwt-token");

        TenantSelectionRequest req = new TenantSelectionRequest();
        req.setTenantName("acme");
        req.setDisplayName("Acme Inc");
        TenantSelectionResponse resp = service.createTenantForUser(req, user(7L, "u@x.com"));

        assertEquals(StatusConstants.SUCCESS, resp.getStatus());
        assertEquals(99L, resp.getTenantId());
        assertEquals("jwt-token", resp.getJwt());
        verify(tenantMemberService).addMember(7L, 99L, StatusConstants.ACTIVE);
        verify(builtinPluginImportService).importForTenant(99L, 7L);
        verify(sessionManagementService).createSession(eq(7L), eq("jwt-token"), any(), any());
    }

    @Test
    @DisplayName("createTenantForUser maps DuplicateKeyException to validation error")
    void createDuplicateKey() {
        when(tenantService.findByName("acme")).thenReturn(null);
        when(tenantService.createTenant(any(Tenant.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("dup"));

        TenantSelectionRequest req = new TenantSelectionRequest();
        req.setTenantName("acme");

        assertThrows(ValidationException.class,
                () -> service.createTenantForUser(req, user(7L, "u@x.com")));
    }

    @Test
    @DisplayName("joinTenantByInviteCode error when invite not found")
    void joinInviteNotFound() {
        when(tenantInviteService.findByInvitationCode("c")).thenReturn(null);
        TenantSelectionRequest req = new TenantSelectionRequest();
        req.setInviteCode("c");

        TenantSelectionResponse resp = service.joinTenantByInviteCode(req, user(7L, "x"));
        assertEquals("error", resp.getStatus());
        verify(tenantMemberService, never()).addMember(any(), any(), any());
    }

    @Test
    @DisplayName("joinTenantByInviteCode error when invite expired")
    void joinInviteExpired() {
        Invitation inv = new Invitation();
        inv.setStatus(StatusConstants.ACTIVE);
        inv.setExpiredAt(Instant.now().minus(1, ChronoUnit.DAYS));
        when(tenantInviteService.findByInvitationCode("c")).thenReturn(inv);
        TenantSelectionRequest req = new TenantSelectionRequest();
        req.setInviteCode("c");

        TenantSelectionResponse resp = service.joinTenantByInviteCode(req, user(7L, "x"));
        assertEquals("error", resp.getStatus());
    }

    @Test
    @DisplayName("joinTenantByInviteCode error when invite status not ACTIVE")
    void joinInviteInactive() {
        Invitation inv = new Invitation();
        inv.setStatus(StatusConstants.EXPIRED);
        when(tenantInviteService.findByInvitationCode("c")).thenReturn(inv);
        TenantSelectionRequest req = new TenantSelectionRequest();
        req.setInviteCode("c");

        TenantSelectionResponse resp = service.joinTenantByInviteCode(req, user(7L, "x"));
        assertEquals("error", resp.getStatus());
    }

    @Test
    @DisplayName("joinTenantByInviteCode pending when invite valid")
    void joinInviteOk() {
        Invitation inv = new Invitation();
        inv.setStatus(StatusConstants.ACTIVE);
        inv.setTenantId(99L);
        inv.setExpiredAt(Instant.now().plus(1, ChronoUnit.DAYS));
        when(tenantInviteService.findByInvitationCode("c")).thenReturn(inv);
        when(tenantService.getById(99L)).thenReturn(tenant(99L, "Acme"));

        TenantSelectionRequest req = new TenantSelectionRequest();
        req.setInviteCode("c");

        TenantSelectionResponse resp = service.joinTenantByInviteCode(req, user(7L, "u"));
        assertEquals(StatusConstants.PENDING, resp.getStatus());
        assertEquals(99L, resp.getTenantId());
        assertEquals("Acme", resp.getTenantName());
        assertTrue(resp.getNeedsApproval());
        verify(tenantMemberService).addMember(7L, 99L, StatusConstants.PENDING);
    }

    @Test
    @DisplayName("getTenantByPid returns response when found")
    void getByPidOk() {
        when(tenantService.findByPid("p")).thenReturn(tenant(1L, "x"));
        TenantResponse resp = service.getTenantByPid("p", 7L);
        assertNotNull(resp);
    }

    @Test
    @DisplayName("getTenantByPid throws when missing")
    void getByPidMissing() {
        when(tenantService.findByPid("p")).thenReturn(null);
        assertThrows(ValidationException.class, () -> service.getTenantByPid("p", 7L));
    }
}
