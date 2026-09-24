package com.auraboot.framework.auth.strategy;

import com.auraboot.framework.auth.dto.AuthenticationResponse;
import com.auraboot.framework.auth.dto.LoginContextRef;
import com.auraboot.framework.auth.dto.SessionTokenContext;
import com.auraboot.framework.auth.constant.ExecutionScope;
import com.auraboot.framework.auth.constant.SessionStage;
import com.auraboot.framework.auth.mapper.LoginApplicationChannelMapper;
import com.auraboot.framework.auth.service.PasswordManagementService;
import com.auraboot.framework.auth.service.SessionManagementService;
import com.auraboot.framework.auth.util.JwtUtil;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserApplicationPreferenceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

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

    @Mock
    private LoginApplicationChannelMapper loginApplicationChannelMapper;

    @Mock
    private UserApplicationPreferenceService userApplicationPreferenceService;

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

    @Test
    void completeLogin_resolvedApplicationChannel_areBoundIntoReadyTenantToken() {
        ReflectionTestUtils.setField(helper, "loginApplicationChannelMapper", loginApplicationChannelMapper);
        User user = buildUser(13L, "user-pid-013", null, false, false);
        user.setSecurityVersion(7);
        TenantMember member = new TenantMember();
        member.setId(501L);
        member.setStatus("active");
        LoginContextRef loginContext = new LoginContextRef();
        loginContext.setApplicationId(601L);
        loginContext.setLoginChannelId(701L);

        when(tenantMemberService.getTenantIdsByUserId(13L)).thenReturn(java.util.List.of(401L));
        when(tenantMemberService.findByTenantIdAndUserId(401L, 13L)).thenReturn(member);
        when(tenantService.getById(401L)).thenReturn(tenantWithStatus("active"));
        when(loginApplicationChannelMapper.resolveLoginContext(
                "business-web", "default-business-web", null)).thenReturn(loginContext);
        when(loginApplicationChannelMapper.resolveLoginContext(
                "business-web", "default-business-web", 401L)).thenReturn(loginContext);
        when(jwtUtil.generateTokenWithContext(any(), any(), any())).thenReturn("jwt-context");
        when(passwordManagementService.isPasswordExpired(user)).thenReturn(false);

        AuthenticationResponse result = helper.completeLogin(user, null, null);

        org.mockito.ArgumentCaptor<SessionTokenContext> contextCaptor =
                org.mockito.ArgumentCaptor.forClass(SessionTokenContext.class);
        verify(jwtUtil).generateTokenWithContext(any(), eq("user-pid-013"), contextCaptor.capture());
        SessionTokenContext context = contextCaptor.getValue();
        assertThat(result.getJwt()).isEqualTo("jwt-context");
        assertThat(context.tenantId()).isEqualTo(401L);
        assertThat(context.memberId()).isEqualTo(501L);
        assertThat(context.applicationId()).isEqualTo(601L);
        assertThat(context.loginChannelId()).isEqualTo(701L);
        assertThat(context.executionScope()).isEqualTo(ExecutionScope.TENANT);
        assertThat(context.sessionStage()).isEqualTo(SessionStage.READY);
        assertThat(context.contextVersion()).isEqualTo(1);
        assertThat(context.securityVersion()).isEqualTo(7);
        verify(jwtUtil, never()).generateTokenWithTenantId(any(), any(), any(), any(), anyInt());
    }

    @Test
    void completeLogin_multipleTenants_usesValidatedRecentTenantForApplication() {
        ReflectionTestUtils.setField(helper, "loginApplicationChannelMapper", loginApplicationChannelMapper);
        User user = buildUser(20L, "user-pid-020", null, false, false);
        LoginContextRef context = new LoginContextRef();
        context.setApplicationId(601L);
        context.setLoginChannelId(701L);
        Tenant recent = tenantWithStatus("active");
        recent.setId(402L);
        recent.setPid("tenant-pid-402");
        TenantMember member = new TenantMember();
        member.setId(502L);
        member.setStatus("active");

        when(loginApplicationChannelMapper.resolveLoginContext("business-web", "default-business-web", null))
                .thenReturn(context);
        when(loginApplicationChannelMapper.resolveLoginContext("business-web", "default-business-web", 402L))
                .thenReturn(context);
        when(tenantMemberService.getTenantIdsByUserId(20L)).thenReturn(java.util.List.of(401L, 402L));
        when(userApplicationPreferenceService.getLastTenantPid(20L, 601L)).thenReturn("tenant-pid-402");
        when(tenantService.findByPid("tenant-pid-402")).thenReturn(recent);
        when(tenantMemberService.findByTenantIdAndUserId(402L, 20L)).thenReturn(member);
        when(tenantService.getById(402L)).thenReturn(recent);
        when(jwtUtil.generateTokenWithContext(any(), any(), any())).thenReturn("jwt-recent");

        AuthenticationResponse result = helper.completeLogin(user, null, null);

        assertThat(result.getTenantId()).isEqualTo(402L);
        assertThat(result.getNextAction()).isEqualTo("ENTER");
        verify(userApplicationPreferenceService).setLastTenant(20L, 601L, "tenant-pid-402");
    }

    @Test
    void completeLogin_multipleTenantsWithStalePreference_requiresPrivateSelection() {
        ReflectionTestUtils.setField(helper, "loginApplicationChannelMapper", loginApplicationChannelMapper);
        User user = buildUser(21L, "user-pid-021", null, false, false);
        LoginContextRef context = new LoginContextRef();
        context.setApplicationId(602L);
        context.setLoginChannelId(702L);
        Tenant stale = tenantWithStatus("active");
        stale.setId(999L);

        when(loginApplicationChannelMapper.resolveLoginContext("business-web", "default-business-web", null))
                .thenReturn(context);
        when(tenantMemberService.getTenantIdsByUserId(21L)).thenReturn(java.util.List.of(401L, 402L));
        when(userApplicationPreferenceService.getLastTenantPid(21L, 602L)).thenReturn("stale-tenant");
        when(tenantService.findByPid("stale-tenant")).thenReturn(stale);
        when(jwtUtil.generateTokenWithContext(any(), any(), any())).thenReturn("jwt-onboarding");

        AuthenticationResponse result = helper.completeLogin(user, null, null);

        assertThat(result.getTenantId()).isNull();
        assertThat(result.getNextAction()).isEqualTo("SELECT_SCHOOL");
        verify(userApplicationPreferenceService, never()).setLastTenant(anyLong(), anyLong(), anyString());
    }

    @Test
    void completeLogin_applicationWithNoMembership_requiresSchoolBinding() {
        ReflectionTestUtils.setField(helper, "loginApplicationChannelMapper", loginApplicationChannelMapper);
        User user = buildUser(22L, "user-pid-022", null, false, false);
        LoginContextRef context = new LoginContextRef();
        context.setApplicationId(603L);
        context.setLoginChannelId(703L);
        when(loginApplicationChannelMapper.resolveLoginContext(
                "business-web", "default-business-web", null)).thenReturn(context);
        when(tenantMemberService.getTenantIdsByUserId(22L)).thenReturn(java.util.List.of());
        when(jwtUtil.generateTokenWithContext(any(), any(), any())).thenReturn("jwt-bind-school");

        AuthenticationResponse result = helper.completeLogin(user, null, null);

        assertThat(result.getTenantId()).isNull();
        assertThat(result.getNextAction()).isEqualTo("BIND_SCHOOL");
        var tokenContext = org.mockito.ArgumentCaptor.forClass(SessionTokenContext.class);
        verify(jwtUtil).generateTokenWithContext(any(), eq("user-pid-022"), tokenContext.capture());
        assertThat(tokenContext.getValue().applicationId()).isEqualTo(603L);
        assertThat(tokenContext.getValue().sessionStage()).isEqualTo(SessionStage.ONBOARDING);
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
