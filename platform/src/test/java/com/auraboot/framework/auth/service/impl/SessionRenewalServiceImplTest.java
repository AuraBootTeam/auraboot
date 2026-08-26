package com.auraboot.framework.auth.service.impl;

import com.auraboot.framework.auth.dto.AuthenticationResponse;
import com.auraboot.framework.auth.dto.TokenRenewResponse;
import com.auraboot.framework.auth.entity.UserSession;
import com.auraboot.framework.auth.service.SessionManagementService;
import com.auraboot.framework.auth.strategy.LoginCompletionHelper;
import com.auraboot.framework.auth.util.JwtUtil;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionRenewalServiceImplTest {

    private static final long RENEW_WINDOW_SECONDS = 7 * 24 * 3600L;
    private static final String OLD_TOKEN = "old.token.value";
    private static final String NEW_TOKEN = "new.token.value";
    private static final String USER_PID = "user-pid-1";

    @Mock
    private SessionManagementService sessionManagementService;
    @Mock
    private LoginCompletionHelper loginCompletionHelper;
    @Mock
    private JwtUtil jwtUtil;
    @Mock
    private UserService userService;

    private SessionRenewalServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SessionRenewalServiceImpl(
                sessionManagementService, loginCompletionHelper, jwtUtil, userService);
        ReflectionTestUtils.setField(service, "renewWindowSeconds", RENEW_WINDOW_SECONDS);
    }

    private UserSession activeSession(Instant createdAt) {
        UserSession session = new UserSession();
        session.setCreatedAt(createdAt);
        session.setRevoked(false);
        return session;
    }

    private User enabledUser() {
        User user = new User();
        user.setPid(USER_PID);
        return user;
    }

    @Test
    void renew_withoutSessionRecord_throws() {
        when(sessionManagementService.findByToken(OLD_TOKEN)).thenReturn(null);

        assertThatThrownBy(() -> service.renew(OLD_TOKEN, "127.0.0.1", "UA"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("no longer valid");
    }

    @Test
    void renew_withRevokedSession_throws() {
        UserSession session = activeSession(Instant.now().minusSeconds(60));
        session.setRevoked(true);
        when(sessionManagementService.findByToken(OLD_TOKEN)).thenReturn(session);

        assertThatThrownBy(() -> service.renew(OLD_TOKEN, "127.0.0.1", "UA"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("no longer valid");
    }

    @Test
    void renew_outsideRenewWindow_throws() {
        UserSession session = activeSession(Instant.now().minusSeconds(RENEW_WINDOW_SECONDS + 1));
        when(sessionManagementService.findByToken(OLD_TOKEN)).thenReturn(session);

        assertThatThrownBy(() -> service.renew(OLD_TOKEN, "127.0.0.1", "UA"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("expired");

        verify(loginCompletionHelper, never()).completeLogin(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void renew_insideWindow_rotatesAndReturnsRenewedToken() {
        UserSession session = activeSession(Instant.now().minusSeconds(60));
        when(sessionManagementService.findByToken(OLD_TOKEN)).thenReturn(session);
        when(jwtUtil.extractIdentifier(OLD_TOKEN)).thenReturn(USER_PID);
        when(userService.findByPid(USER_PID)).thenReturn(enabledUser());

        AuthenticationResponse auth = new AuthenticationResponse(
                NEW_TOKEN, 42L, USER_PID, "admin", 7L, "member");
        when(loginCompletionHelper.completeLogin(
                org.mockito.ArgumentMatchers.any(User.class), org.mockito.ArgumentMatchers.eq("127.0.0.1"),
                org.mockito.ArgumentMatchers.eq("UA"))).thenReturn(auth);
        when(sessionManagementService.isSessionValid(NEW_TOKEN)).thenReturn(true);
        when(jwtUtil.extractExpiration(NEW_TOKEN))
                .thenReturn(Date.from(Instant.now().plusSeconds(86400)));

        TokenRenewResponse response = service.renew(OLD_TOKEN, "127.0.0.1", "UA");

        assertThat(response.getJwt()).isEqualTo(NEW_TOKEN);
        assertThat(response.getExpiresAt()).isNotNull();
        verify(sessionManagementService).revokeSessionByToken(OLD_TOKEN);
    }

    @Test
    void renew_whenNewTokenHasNoSessionRecord_throws() {
        UserSession session = activeSession(Instant.now().minusSeconds(60));
        when(sessionManagementService.findByToken(OLD_TOKEN)).thenReturn(session);
        when(jwtUtil.extractIdentifier(OLD_TOKEN)).thenReturn(USER_PID);
        when(userService.findByPid(USER_PID)).thenReturn(enabledUser());

        AuthenticationResponse auth = new AuthenticationResponse(
                NEW_TOKEN, 42L, USER_PID, "admin", 7L, "member");
        when(loginCompletionHelper.completeLogin(
                org.mockito.ArgumentMatchers.any(User.class), org.mockito.ArgumentMatchers.eq("127.0.0.1"),
                org.mockito.ArgumentMatchers.eq("UA"))).thenReturn(auth);
        when(sessionManagementService.isSessionValid(NEW_TOKEN)).thenReturn(false);

        assertThatThrownBy(() -> service.renew(OLD_TOKEN, "127.0.0.1", "UA"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("failed");

        verify(sessionManagementService, never()).revokeSessionByToken(OLD_TOKEN);
    }
}
