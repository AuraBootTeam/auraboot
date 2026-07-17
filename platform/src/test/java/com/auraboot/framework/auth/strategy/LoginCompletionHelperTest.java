package com.auraboot.framework.auth.strategy;

import com.auraboot.framework.auth.dto.AuthenticationResponse;
import com.auraboot.framework.auth.service.PasswordManagementService;
import com.auraboot.framework.auth.service.SessionManagementService;
import com.auraboot.framework.auth.util.JwtUtil;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.user.dao.entity.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for LoginCompletionHelper.
 */
@ExtendWith(MockitoExtension.class)
class LoginCompletionHelperTest {

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private TenantMemberService tenantMemberService;

    @Mock
    private TenantService tenantService;

    @Mock
    private SessionManagementService sessionManagementService;

    @Mock
    private PasswordManagementService passwordManagementService;

    @InjectMocks
    private LoginCompletionHelper helper;

    // =========================================================
    // completeLogin — happy path (member of tenant)
    // =========================================================

    @Test
    void completeLogin_activeMember_returnsMemberStatus() {
        User user = buildUser(1L, "user-pid-001", null, false, false);

        when(tenantMemberService.getTenantIdByUserId(1L)).thenReturn(100L);
        TenantMember member = new TenantMember();
        member.setStatus("active");
        when(tenantMemberService.findByTenantIdAndUserId(100L, 1L)).thenReturn(member);
        when(tenantService.getById(100L)).thenReturn(tenantWithStatus("active"));
        when(jwtUtil.generateTokenWithTenantId(any(), any(), any(), any(), anyInt())).thenReturn("jwt-token-abc");
        when(passwordManagementService.isPasswordExpired(user)).thenReturn(false);

        AuthenticationResponse result = helper.completeLogin(user, "127.0.0.1", "Mozilla/5.0");

        assertThat(result.getJwt()).isEqualTo("jwt-token-abc");
        assertThat(result.getTenantId()).isEqualTo(100L);
        assertThat(result.getTenantStatus()).isEqualTo("member"); // ACTIVE → MEMBER
        assertThat(result.isMustChangePassword()).isFalse();
    }

    @Test
    void completeLogin_noTenant_returnsNoneStatus() {
        User user = buildUser(2L, "user-pid-002", null, false, false);

        when(tenantMemberService.getTenantIdByUserId(2L)).thenReturn(null);
        when(jwtUtil.generateTokenWithTenantId(any(), any(), isNull(), isNull(), anyInt())).thenReturn("jwt-token-def");
        when(passwordManagementService.isPasswordExpired(user)).thenReturn(false);

        AuthenticationResponse result = helper.completeLogin(user, null, null);

        assertThat(result.getTenantId()).isNull();
        assertThat(result.getTenantStatus()).isEqualTo("none");
    }

    @Test
    void completeLogin_pendingMember_returnsPendingStatus() {
        User user = buildUser(3L, "user-pid-003", null, false, false);

        when(tenantMemberService.getTenantIdByUserId(3L)).thenReturn(200L);
        TenantMember member = new TenantMember();
        member.setStatus("pending");
        when(tenantMemberService.findByTenantIdAndUserId(200L, 3L)).thenReturn(member);
        when(tenantService.getById(200L)).thenReturn(tenantWithStatus("active"));
        when(jwtUtil.generateTokenWithTenantId(any(), any(), any(), any(), anyInt())).thenReturn("jwt-token-ghi");
        when(passwordManagementService.isPasswordExpired(user)).thenReturn(false);

        AuthenticationResponse result = helper.completeLogin(user, null, null);

        assertThat(result.getTenantStatus()).isEqualTo("pending");
    }

    // =========================================================
    // completeLogin — suspended organization is blocked
    // =========================================================

    @Test
    void completeLogin_suspendedTenant_refusesLoginAndMintsNothing() {
        User user = buildUser(10L, "user-pid-010", null, false, false);

        when(tenantMemberService.getTenantIdByUserId(10L)).thenReturn(300L);
        TenantMember member = new TenantMember();
        member.setStatus("active"); // the member is fine; the ORG is suspended
        when(tenantMemberService.findByTenantIdAndUserId(300L, 10L)).thenReturn(member);
        when(tenantService.getById(300L)).thenReturn(tenantWithStatus("suspended"));

        assertThatThrownBy(() -> helper.completeLogin(user, "127.0.0.1", "Mozilla/5.0"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("tenant.suspended");

        // The block sits before any token or session is minted — a suspended tenant must not walk
        // away with a usable credential.
        verify(jwtUtil, never()).generateTokenWithTenantId(any(), any(), any(), any(), anyInt());
        verify(sessionManagementService, never()).createSession(anyLong(), anyString(), any(), any());
    }

    @Test
    void completeLogin_suspendedStatusIsCaseInsensitive() {
        User user = buildUser(11L, "user-pid-011", null, false, false);

        when(tenantMemberService.getTenantIdByUserId(11L)).thenReturn(301L);
        when(tenantService.getById(301L)).thenReturn(tenantWithStatus("SUSPENDED"));

        assertThatThrownBy(() -> helper.completeLogin(user, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("tenant.suspended");
    }

    @Test
    void completeLogin_inactiveTenant_isNotBlocked() {
        // Only SUSPENDED is a hard block. An inactive (deactivated) tenant is a softer state and is
        // intentionally left to log in, so this change cannot silently widen the lockout.
        User user = buildUser(12L, "user-pid-012", null, false, false);

        when(tenantMemberService.getTenantIdByUserId(12L)).thenReturn(302L);
        when(tenantService.getById(302L)).thenReturn(tenantWithStatus("inactive"));
        when(jwtUtil.generateTokenWithTenantId(any(), any(), any(), any(), anyInt())).thenReturn("jwt-inactive");
        when(passwordManagementService.isPasswordExpired(user)).thenReturn(false);

        AuthenticationResponse result = helper.completeLogin(user, null, null);

        assertThat(result.getJwt()).isEqualTo("jwt-inactive");
    }

    // =========================================================
    // completeLogin — password status
    // =========================================================

    @Test
    void completeLogin_mustChangePassword_flagsInResponse() {
        User user = buildUser(4L, "user-pid-004", null, true, false);

        when(tenantMemberService.getTenantIdByUserId(4L)).thenReturn(null);
        when(jwtUtil.generateTokenWithTenantId(any(), any(), any(), any(), anyInt())).thenReturn("jwt");
        // isPasswordExpired is never called due to short-circuit (mustChangePassword=true)

        AuthenticationResponse result = helper.completeLogin(user, null, null);

        assertThat(result.isMustChangePassword()).isTrue();
    }

    @Test
    void completeLogin_passwordExpired_flagsInResponse() {
        User user = buildUser(5L, "user-pid-005", null, false, false);

        when(tenantMemberService.getTenantIdByUserId(5L)).thenReturn(null);
        when(jwtUtil.generateTokenWithTenantId(any(), any(), any(), any(), anyInt())).thenReturn("jwt");
        when(passwordManagementService.isPasswordExpired(user)).thenReturn(true);

        AuthenticationResponse result = helper.completeLogin(user, null, null);

        assertThat(result.isMustChangePassword()).isTrue();
    }

    // =========================================================
    // completeLogin — session and tenant failure resilience
    // =========================================================

    @Test
    void completeLogin_sessionCreationFails_stillReturnsResponse() {
        User user = buildUser(6L, "user-pid-006", null, false, false);

        when(tenantMemberService.getTenantIdByUserId(6L)).thenReturn(null);
        when(jwtUtil.generateTokenWithTenantId(any(), any(), any(), any(), anyInt())).thenReturn("jwt-fallback");
        when(passwordManagementService.isPasswordExpired(user)).thenReturn(false);
        doThrow(new RuntimeException("DB down")).when(sessionManagementService)
                .createSession(anyLong(), anyString(), any(), any());

        // Session creation is non-fatal — login should succeed even if session persistence fails
        AuthenticationResponse result = helper.completeLogin(user, "10.0.0.1", "curl");

        assertThat(result).isNotNull();
        assertThat(result.getJwt()).isEqualTo("jwt-fallback");
    }

    @Test
    void completeLogin_tenantServiceFails_returnsNoneStatus() {
        User user = buildUser(7L, "user-pid-007", null, false, false);

        when(tenantMemberService.getTenantIdByUserId(7L)).thenThrow(new RuntimeException("tenant lookup failed"));
        when(jwtUtil.generateTokenWithTenantId(any(), any(), any(), any(), anyInt())).thenReturn("jwt-ok");
        when(passwordManagementService.isPasswordExpired(user)).thenReturn(false);

        // Should NOT propagate the exception
        AuthenticationResponse result = helper.completeLogin(user, null, null);

        assertThat(result.getTenantStatus()).isEqualTo("none");
        assertThat(result.getJwt()).isEqualTo("jwt-ok");
    }

    // =========================================================
    // completeLogin — security version
    // =========================================================

    @Test
    void completeLogin_nullSecurityVersion_usesZero() {
        User user = buildUser(8L, "user-pid-008", null, false, false);
        user.setSecurityVersion(null); // explicitly null

        when(tenantMemberService.getTenantIdByUserId(8L)).thenReturn(null);
        when(jwtUtil.generateTokenWithTenantId(any(), any(), any(), any(), eq(0))).thenReturn("jwt-sv0");
        when(passwordManagementService.isPasswordExpired(user)).thenReturn(false);

        helper.completeLogin(user, null, null);

        verify(jwtUtil).generateTokenWithTenantId(any(), any(), any(), any(), eq(0));
    }

    @Test
    void completeLogin_withSecurityVersion_usesProvidedValue() {
        User user = buildUser(9L, "user-pid-009", null, false, false);
        user.setSecurityVersion(5);

        when(tenantMemberService.getTenantIdByUserId(9L)).thenReturn(null);
        when(jwtUtil.generateTokenWithTenantId(any(), any(), any(), any(), eq(5))).thenReturn("jwt-sv5");
        when(passwordManagementService.isPasswordExpired(user)).thenReturn(false);

        helper.completeLogin(user, null, null);

        verify(jwtUtil).generateTokenWithTenantId(any(), any(), any(), any(), eq(5));
    }

    // =========================================================
    // Helper
    // =========================================================

    private Tenant tenantWithStatus(String status) {
        Tenant tenant = new Tenant();
        tenant.setStatus(status);
        return tenant;
    }

    private User buildUser(Long id, String pid, String nickName,
                           boolean mustChangePassword, boolean accountLocked) {
        User user = new User();
        user.setId(id);
        user.setPid(pid);
        user.setEmail(pid + "@example.com");
        user.setNickName(nickName);
        user.setMustChangePassword(mustChangePassword);
        user.setEnabled(true);
        user.setAccountNonExpired(true);
        user.setAccountNonLocked(!accountLocked);
        user.setCredentialsNonExpired(true);
        user.setSecurityVersion(0);
        return user;
    }
}
