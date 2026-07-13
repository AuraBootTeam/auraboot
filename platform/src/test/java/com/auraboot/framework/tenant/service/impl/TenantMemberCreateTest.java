package com.auraboot.framework.tenant.service.impl;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.dto.TenantMemberCreateRequest;
import com.auraboot.framework.tenant.dto.TenantMemberCreateResult;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code admin:create_member} — an admin creating a tenant member directly.
 *
 * <p>The command exists because a fresh AuraBoot had no way to get a second user:
 * self-registration is off, and the employee route needs a department and a
 * position first. One user means one admin, and an admin is never refused
 * anything — so the platform's central claim, that every write passes one
 * permission path, could not be demonstrated on a fresh install at all.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TenantMemberApplicationServiceImpl#createMember")
class TenantMemberCreateTest {

    private static final Long TENANT_ID = 42L;
    private static final Long OPERATOR_ID = 7L;

    @Mock
    private TenantMemberService tenantMemberService;

    @Mock
    private UserService userService;

    @Mock
    private UserRoleService userRoleService;

    @InjectMocks
    private TenantMemberApplicationServiceImpl service;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(TENANT_ID, OPERATOR_ID, "operator-pid", "admin");
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    private TenantMemberCreateRequest request(String name, String email) {
        TenantMemberCreateRequest r = new TenantMemberCreateRequest();
        r.setName(name);
        r.setEmail(email);
        return r;
    }

    private User user(long id) {
        User u = new User();
        u.setId(id);
        u.setPid("user-pid-" + id);
        return u;
    }

    private TenantMember member() {
        TenantMember m = new TenantMember();
        m.setPid("member-pid-1");
        return m;
    }

    @Test
    @DisplayName("creates the user, activates the membership, and grants the roles")
    void createsMemberWithRoles() {
        TenantMemberCreateRequest req = request("Sam Reader", "sam@example.com");
        req.setPassword("Test2026x");
        req.setRoleCodes(List.of("tenant_member"));

        when(userService.findByEmail("sam@example.com")).thenReturn(null);
        when(userService.signUp(eq("sam@example.com"), eq("Test2026x"), eq("Sam Reader"))).thenReturn(user(1L));
        when(tenantMemberService.findByTenantIdAndUserId(TENANT_ID, 1L)).thenReturn(null);
        when(tenantMemberService.addMember(eq(1L), eq(TENANT_ID), anyString())).thenReturn(member());

        TenantMemberCreateResult result = service.createMember(req, OPERATOR_ID);

        assertEquals("member-pid-1", result.getMemberPid());
        assertEquals("sam@example.com", result.getEmail());
        // The password is echoed back: an admin who creates an account and is not
        // told the password has not created a usable account.
        assertEquals("Test2026x", result.getPassword());
        assertFalse(result.isPasswordGenerated());
        assertEquals(List.of("tenant_member"), result.getAssignedRoles());

        verify(userRoleService).assignRolesToMemberByRoleCodes(
                "member-pid-1", List.of("tenant_member"), TENANT_ID, OPERATOR_ID);
    }

    @Test
    @DisplayName("generates a password when none is supplied, and says that it did")
    void generatesPasswordWhenOmitted() {
        TenantMemberCreateRequest req = request("Rita Sales", "rita@example.com");

        when(userService.findByEmail("rita@example.com")).thenReturn(null);
        when(userService.signUp(anyString(), anyString(), anyString())).thenReturn(user(2L));
        when(tenantMemberService.findByTenantIdAndUserId(TENANT_ID, 2L)).thenReturn(null);
        when(tenantMemberService.addMember(anyLong(), anyLong(), anyString())).thenReturn(member());

        TenantMemberCreateResult result = service.createMember(req, OPERATOR_ID);

        assertTrue(result.isPasswordGenerated());
        assertNotNull(result.getPassword());
        assertFalse(result.getPassword().isBlank());
        // No roles asked for, so none granted — the account still gets whatever the
        // tenant baseline gives it, which is the account you want when the point is
        // to be refused something.
        assertTrue(result.getAssignedRoles().isEmpty());
        verify(userRoleService, never()).assignRolesToMemberByRoleCodes(any(), any(), any(), any());
    }

    @Test
    @DisplayName("refuses to reset an existing account's password on someone else's behalf")
    void refusesPasswordForExistingUser() {
        TenantMemberCreateRequest req = request("Sam Reader", "sam@example.com");
        req.setPassword("hunter2");

        // That account may belong to another tenant. Quietly overwriting its password
        // because this caller supplied one would be an account takeover with a
        // friendly name.
        when(userService.findByEmail("sam@example.com")).thenReturn(user(1L));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createMember(req, OPERATOR_ID));
        assertTrue(ex.getMessage().contains("already exists"));
        verify(tenantMemberService, never()).addMember(anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("refuses to add somebody who is already a member")
    void refusesDuplicateMember() {
        TenantMemberCreateRequest req = request("Sam Reader", "sam@example.com");

        when(userService.findByEmail("sam@example.com")).thenReturn(user(1L));
        when(tenantMemberService.findByTenantIdAndUserId(TENANT_ID, 1L)).thenReturn(member());

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createMember(req, OPERATOR_ID));
        assertTrue(ex.getMessage().contains("already a member"));
        verify(tenantMemberService, never()).addMember(anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("name and email are required")
    void requiresNameAndEmail() {
        assertThrows(BusinessException.class, () -> service.createMember(request(null, "a@b.c"), OPERATOR_ID));
        assertThrows(BusinessException.class, () -> service.createMember(request("A", null), OPERATOR_ID));
        assertThrows(BusinessException.class, () -> service.createMember(null, OPERATOR_ID));
        verify(userService, never()).signUp(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a tenant context is required — a member has to belong to somebody")
    void requiresTenantContext() {
        MetaContext.clear();
        // The platform itself refuses: MetaContext.get() throws rather than handing
        // back a null tenant, so a member can never be created into nowhere. Asserting
        // the exception the platform actually throws, not the one it would be tidier
        // to catch and rewrap.
        assertThrows(IllegalStateException.class,
                () -> service.createMember(request("Sam", "sam@example.com"), OPERATOR_ID));
        verify(tenantMemberService, never()).addMember(anyLong(), anyLong(), anyString());
    }
}
