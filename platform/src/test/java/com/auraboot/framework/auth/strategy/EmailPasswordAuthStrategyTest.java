package com.auraboot.framework.auth.strategy;

import com.auraboot.framework.auth.dto.AuthStrategyRequest;
import com.auraboot.framework.auth.dto.AuthenticationResponse;
import com.auraboot.framework.auth.service.PasswordManagementService;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for EmailPasswordAuthStrategy.
 */
@ExtendWith(MockitoExtension.class)
class EmailPasswordAuthStrategyTest {

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private PasswordManagementService passwordManagementService;

    @Mock
    private LoginCompletionHelper loginCompletionHelper;

    @Mock
    private UserService userService;

    @InjectMocks
    private EmailPasswordAuthStrategy strategy;

    // =========================================================
    // getChannelCode / supports
    // =========================================================

    @Test
    void getChannelCode_returnsEmailPassword() {
        assertThat(strategy.getChannelCode()).isEqualTo("email_password");
    }

    @Test
    void supports_emailPassword_returnsTrue() {
        assertThat(strategy.supports("email_password")).isTrue();
    }

    @Test
    void supports_other_returnsFalse() {
        assertThat(strategy.supports("sms")).isFalse();
        assertThat(strategy.supports("email_code")).isFalse();
    }

    // =========================================================
    // Locked account pre-check
    // =========================================================

    @Test
    void authenticate_lockedAccount_throwsRootUnCheckedException() {
        User lockedUser = buildUser("admin@auraboot.com");
        when(userService.findByEmail(anyString())).thenReturn(lockedUser);
        when(passwordManagementService.isAccountLocked(lockedUser)).thenReturn(true);

        AuthStrategyRequest request = buildRequest("admin@auraboot.com", "pass");

        assertThatThrownBy(() -> strategy.authenticate(request))
                .isInstanceOf(RootUnCheckedException.class)
                .hasMessageContaining("locked");
    }

    // =========================================================
    // Successful authentication
    // =========================================================

    @Test
    void authenticate_validCredentials_completesLogin() {
        User user = buildUser("user@example.com");
        when(userService.findByEmail(anyString())).thenReturn(user);
        when(passwordManagementService.isAccountLocked(user)).thenReturn(false);

        Authentication auth = mock(Authentication.class);
        when(authenticationManager.authenticate(any())).thenReturn(auth);

        AuthenticationResponse response = mock(AuthenticationResponse.class);
        when(loginCompletionHelper.completeLogin(any(), any(), any())).thenReturn(response);

        AuthStrategyRequest request = buildRequest("user@example.com", "correct-pass");
        AuthenticationResponse result = strategy.authenticate(request);

        assertThat(result).isSameAs(response);
        verify(passwordManagementService).resetLoginFailures(user);
        verify(authenticationManager).authenticate(argThat(token ->
                token instanceof UsernamePasswordAuthenticationToken &&
                "user@example.com".equals(token.getPrincipal()) &&
                "correct-pass".equals(token.getCredentials())));
    }

    @Test
    void authenticate_userNameIdentifier_completesLoginWithIdentifierPrincipal() {
        User user = buildUser("user@example.com", "吴书生");
        when(userService.findByEmail("吴书生")).thenReturn(null);
        when(userService.findByUserName("吴书生")).thenReturn(user);
        when(passwordManagementService.isAccountLocked(user)).thenReturn(false);

        Authentication auth = mock(Authentication.class);
        when(authenticationManager.authenticate(any())).thenReturn(auth);

        AuthenticationResponse response = mock(AuthenticationResponse.class);
        when(loginCompletionHelper.completeLogin(any(), any(), any())).thenReturn(response);

        AuthStrategyRequest request = buildIdentifierRequest("吴书生", "correct-pass");
        AuthenticationResponse result = strategy.authenticate(request);

        assertThat(result).isSameAs(response);
        verify(passwordManagementService).resetLoginFailures(user);
        verify(authenticationManager).authenticate(argThat(token ->
                token instanceof UsernamePasswordAuthenticationToken &&
                "吴书生".equals(token.getPrincipal()) &&
                "correct-pass".equals(token.getCredentials())));
    }

    @Test
    void authenticate_userNotFoundInPreCheck_loadsAfterAuth() {
        // The canonical lookup is repeated after successful authentication.
        User user = buildUser("newlogin@example.com");
        when(userService.findByEmail(anyString()))
                .thenReturn(null)
                .thenReturn(user);

        Authentication auth = mock(Authentication.class);
        when(authenticationManager.authenticate(any())).thenReturn(auth);

        AuthenticationResponse response = mock(AuthenticationResponse.class);
        when(loginCompletionHelper.completeLogin(eq(user), any(), any())).thenReturn(response);

        AuthStrategyRequest request = buildRequest("newlogin@example.com", "pass");
        strategy.authenticate(request);

        verify(loginCompletionHelper).completeLogin(eq(user), any(), any());
    }

    @Test
    void authenticate_mixedCaseEmail_usesCanonicalUserLookupForSessionAndFailureReset() {
        User user = buildUser("user@example.com");
        when(userService.findByEmail("USER@Example.COM")).thenReturn(user);
        when(authenticationManager.authenticate(any())).thenReturn(mock(Authentication.class));
        AuthenticationResponse response = mock(AuthenticationResponse.class);
        when(loginCompletionHelper.completeLogin(eq(user), any(), any())).thenReturn(response);

        assertThat(strategy.authenticate(buildRequest("USER@Example.COM", "pass"))).isSameAs(response);
        verify(passwordManagementService).isAccountLocked(user);
        verify(passwordManagementService).resetLoginFailures(user);
        verify(loginCompletionHelper).completeLogin(eq(user), any(), any());
    }

    @Test
    void authenticate_identifierWhitespace_usesSameTrimmedLookupAsAuthentication() {
        User user = buildUser("user@example.com", "Teacher");
        when(userService.findByEmail("Teacher")).thenReturn(null);
        when(userService.findByUserName("Teacher")).thenReturn(user);
        when(authenticationManager.authenticate(any())).thenReturn(mock(Authentication.class));

        strategy.authenticate(buildIdentifierRequest("  Teacher  ", "pass"));

        verify(authenticationManager).authenticate(argThat(token -> "Teacher".equals(token.getPrincipal())));
        verify(loginCompletionHelper).completeLogin(eq(user), any(), any());
    }

    @Test
    void authenticate_lookupFailure_propagatesWithoutCreatingSession() {
        IllegalStateException failure = new IllegalStateException("database lookup failed");
        when(userService.findByEmail(anyString())).thenThrow(failure);

        assertThatThrownBy(() -> strategy.authenticate(buildRequest("user@example.com", "pass")))
                .isSameAs(failure);
        verifyNoInteractions(authenticationManager, loginCompletionHelper);
    }

    @Test
    void authenticate_successWithoutResolvedUser_rejectsSessionCreation() {
        when(authenticationManager.authenticate(any())).thenReturn(mock(Authentication.class));

        assertThatThrownBy(() -> strategy.authenticate(buildRequest("missing@example.com", "pass")))
                .isInstanceOf(BadCredentialsException.class);
        verifyNoInteractions(loginCompletionHelper);
        verify(passwordManagementService, never()).resetLoginFailures(any());
    }

    // =========================================================
    // Bad credentials — records failure
    // =========================================================

    @Test
    void authenticate_badCredentials_recordsFailureAndRethrows() {
        User user = buildUser("user@example.com");
        when(userService.findByEmail(anyString())).thenReturn(user);
        when(passwordManagementService.isAccountLocked(user)).thenReturn(false);
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("bad password"));

        AuthStrategyRequest request = buildRequest("user@example.com", "wrong-pass");

        assertThatThrownBy(() -> strategy.authenticate(request))
                .isInstanceOf(BadCredentialsException.class);

        verify(passwordManagementService).recordLoginFailure(user);
        verify(passwordManagementService, never()).resetLoginFailures(any());
    }

    @Test
    void authenticate_badCredentials_userNotFound_noFailureRecorded() {
        when(userService.findByEmail(anyString())).thenReturn(null);
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("bad"));

        AuthStrategyRequest request = buildRequest("unknown@example.com", "pass");

        assertThatThrownBy(() -> strategy.authenticate(request))
                .isInstanceOf(BadCredentialsException.class);

        verify(passwordManagementService, never()).recordLoginFailure(any());
    }

    // =========================================================
    // Helpers
    // =========================================================

    private AuthStrategyRequest buildRequest(String email, String password) {
        AuthStrategyRequest r = new AuthStrategyRequest();
        r.setEmail(email);
        r.setPassword(password);
        r.setChannelCode("email_password");
        r.setIpAddress("127.0.0.1");
        return r;
    }

    private AuthStrategyRequest buildIdentifierRequest(String identifier, String password) {
        AuthStrategyRequest r = new AuthStrategyRequest();
        r.setIdentifier(identifier);
        r.setPassword(password);
        r.setChannelCode("email_password");
        r.setIpAddress("127.0.0.1");
        return r;
    }

    private User buildUser(String email) {
        return buildUser(email, null);
    }

    private User buildUser(String email, String userName) {
        User user = new User();
        user.setId(1L);
        user.setEmail(email);
        user.setUserName(userName);
        user.setEnabled(true);
        return user;
    }
}
