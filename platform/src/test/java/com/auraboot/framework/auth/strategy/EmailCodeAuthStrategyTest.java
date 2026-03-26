package com.auraboot.framework.auth.strategy;

import com.auraboot.framework.auth.dto.AuthStrategyRequest;
import com.auraboot.framework.auth.dto.AuthenticationResponse;
import com.auraboot.framework.auth.service.VerificationCodeService;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for EmailCodeAuthStrategy.
 */
@ExtendWith(MockitoExtension.class)
class EmailCodeAuthStrategyTest {

    @Mock
    private VerificationCodeService verificationCodeService;

    @Mock
    private LoginCompletionHelper loginCompletionHelper;

    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private EmailCodeAuthStrategy strategy;

    // =========================================================
    // getChannelCode
    // =========================================================

    @Test
    void getChannelCode_returnsEmailCode() {
        assertThat(strategy.getChannelCode()).isEqualTo("email_code");
    }

    @Test
    void supports_emailCode_returnsTrue() {
        assertThat(strategy.supports("email_code")).isTrue();
    }

    @Test
    void supports_other_returnsFalse() {
        assertThat(strategy.supports("email_password")).isFalse();
        assertThat(strategy.supports("sms")).isFalse();
    }

    // =========================================================
    // Input validation
    // =========================================================

    @Test
    void authenticate_nullEmail_throwsBusinessException() {
        AuthStrategyRequest request = buildRequest(null, "123456");

        assertThatThrownBy(() -> strategy.authenticate(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Email");
    }

    @Test
    void authenticate_blankEmail_throwsBusinessException() {
        AuthStrategyRequest request = buildRequest("   ", "123456");

        assertThatThrownBy(() -> strategy.authenticate(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Email");
    }

    @Test
    void authenticate_nullCode_throwsBusinessException() {
        AuthStrategyRequest request = buildRequest("user@example.com", null);

        assertThatThrownBy(() -> strategy.authenticate(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("code");
    }

    @Test
    void authenticate_blankCode_throwsBusinessException() {
        AuthStrategyRequest request = buildRequest("user@example.com", "  ");

        assertThatThrownBy(() -> strategy.authenticate(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("code");
    }

    // =========================================================
    // Invalid / expired code
    // =========================================================

    @Test
    void authenticate_invalidCode_throwsBusinessException() {
        when(verificationCodeService.verifyCode("user@example.com", "wrong", "login")).thenReturn(false);
        AuthStrategyRequest request = buildRequest("user@example.com", "wrong");

        assertThatThrownBy(() -> strategy.authenticate(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid");
    }

    // =========================================================
    // Disabled account
    // =========================================================

    @Test
    void authenticate_disabledAccount_throwsBusinessException() {
        when(verificationCodeService.verifyCode(any(), any(), any())).thenReturn(true);

        User disabled = buildUser("user@example.com", false);
        when(userMapper.selectOne(any())).thenReturn(disabled);

        AuthStrategyRequest request = buildRequest("user@example.com", "123456");

        assertThatThrownBy(() -> strategy.authenticate(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("disabled");
    }

    // =========================================================
    // Successful login — existing user
    // =========================================================

    @Test
    void authenticate_existingVerifiedUser_completesLogin() {
        when(verificationCodeService.verifyCode(any(), any(), any())).thenReturn(true);

        User user = buildUser("user@example.com", true);
        user.setEmailVerified(true);
        when(userMapper.selectOne(any())).thenReturn(user);

        AuthenticationResponse response = mock(AuthenticationResponse.class);
        when(loginCompletionHelper.completeLogin(eq(user), any(), any())).thenReturn(response);

        AuthStrategyRequest request = buildRequest("user@example.com", "123456");
        AuthenticationResponse result = strategy.authenticate(request);

        assertThat(result).isSameAs(response);
        verify(userMapper, never()).updateById(any(User.class)); // email already verified
    }

    @Test
    void authenticate_unverifiedEmailUser_marksEmailVerified() {
        when(verificationCodeService.verifyCode(any(), any(), any())).thenReturn(true);

        User user = buildUser("user@example.com", true);
        user.setEmailVerified(false);
        when(userMapper.selectOne(any())).thenReturn(user);

        when(loginCompletionHelper.completeLogin(any(), any(), any()))
                .thenReturn(mock(AuthenticationResponse.class));

        AuthStrategyRequest request = buildRequest("user@example.com", "123456");
        strategy.authenticate(request);

        verify(userMapper).updateById(any(User.class));
    }

    // =========================================================
    // Auto-registration — new user
    // =========================================================

    @Test
    void authenticate_newUser_autoRegisters() {
        when(verificationCodeService.verifyCode(any(), any(), any())).thenReturn(true);
        when(userMapper.selectOne(any())).thenReturn(null); // user doesn't exist

        when(loginCompletionHelper.completeLogin(any(), any(), any()))
                .thenReturn(mock(AuthenticationResponse.class));

        AuthStrategyRequest request = buildRequest("newuser@example.com", "654321");
        strategy.authenticate(request);

        // Should insert a new user
        verify(userMapper).insert(any(User.class));
    }

    // =========================================================
    // Helpers
    // =========================================================

    private AuthStrategyRequest buildRequest(String email, String code) {
        AuthStrategyRequest r = new AuthStrategyRequest();
        r.setEmail(email);
        r.setCode(code);
        r.setChannelCode("email_code");
        return r;
    }

    private User buildUser(String email, boolean enabled) {
        User user = new User();
        user.setId(1L);
        user.setEmail(email);
        user.setEnabled(enabled);
        user.setAccountNonExpired(true);
        user.setAccountNonLocked(true);
        user.setCredentialsNonExpired(true);
        return user;
    }
}
