package com.auraboot.framework.auth.service.impl;

import com.auraboot.framework.auth.entity.UserSession;
import com.auraboot.framework.auth.service.SessionManagementService;
import com.auraboot.framework.auth.util.JwtUtil;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.tenant.service.TenantMemberService;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Date;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionRenewalServiceImplTest {
    private final SessionManagementService sessions = mock(SessionManagementService.class);
    private final JwtUtil jwt = mock(JwtUtil.class);
    private final UserService users = mock(UserService.class);
    private final TenantService tenants = mock(TenantService.class);
    private final TenantMemberService members = mock(TenantMemberService.class);
    private final SessionRenewalServiceImpl service = new SessionRenewalServiceImpl(sessions, jwt, users, tenants, members);

    private void active() {
        UserSession row = new UserSession(); row.setPid("session-1");
        when(sessions.findByToken("old")).thenReturn(row);
        when(jwt.extractTenantId("old")).thenReturn(null);
        when(jwt.extractIdentifier("old")).thenReturn("user");
        User user = new User(); user.setPid("user"); user.setId(1L);
        when(users.findByPid("user")).thenReturn(user);
    }
    @Test void missing_session_rejects() {
        assertThatThrownBy(() -> service.renew("old", null, null)).hasMessageContaining("no longer valid");
        verifyNoInteractions(jwt);
    }
    @Test void revoked_session_rejects() {
        UserSession row = new UserSession(); row.setRevoked(true);
        when(sessions.findByToken("old")).thenReturn(row);
        assertThatThrownBy(() -> service.renew("old", null, null)).hasMessageContaining("no longer valid");
    }
    @Test void renewal_preserves_session_and_never_creates_another_login() {
        active(); when(jwt.renewSessionToken("old", "session-1")).thenReturn("new");
        when(jwt.extractExpiration("new")).thenReturn(Date.from(Instant.parse("2026-10-01T00:00:00Z")));
        assertThat(service.renew("old", null, null).getJwt()).isEqualTo("new");
        verify(sessions, never()).createSession(any(), any(), any(), any());
        verify(sessions, never()).revokeSessionByToken(any());
    }
    @Test void deadline_rejection_never_returns_a_cookie_token() {
        active(); when(jwt.renewSessionToken("old", "session-1")).thenThrow(new IllegalArgumentException());
        assertThatThrownBy(() -> service.renew("old", null, null)).hasMessageContaining("expired");
    }
    @Test void security_version_change_rejects() {
        active(); when(jwt.extractSecurityVersion("old")).thenReturn(2);
        assertThatThrownBy(() -> service.renew("old", null, null)).hasMessageContaining("security version");
    }
    @Test void removed_user_rejects() {
        active(); when(users.findByPid("user")).thenReturn(null);
        assertThatThrownBy(() -> service.renew("old", null, null)).hasMessageContaining("no longer active");
    }
    @Test void removed_tenant_rejects() {
        active(); when(jwt.extractTenantId("old")).thenReturn(10L);
        assertThatThrownBy(() -> service.renew("old", null, null)).hasMessageContaining("membership");
    }
}
