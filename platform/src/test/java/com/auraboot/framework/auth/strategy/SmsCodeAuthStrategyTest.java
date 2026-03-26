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
 * Unit tests for SmsCodeAuthStrategy.
 */
@ExtendWith(MockitoExtension.class)
class SmsCodeAuthStrategyTest {

    @Mock
    private VerificationCodeService verificationCodeService;

    @Mock
    private LoginCompletionHelper loginCompletionHelper;

    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private SmsCodeAuthStrategy strategy;

    // =========================================================
    // getChannelCode / supports
    // =========================================================

    @Test
    void getChannelCode_returnsSms() {
        assertThat(strategy.getChannelCode()).isEqualTo("sms");
    }

    @Test
    void supports_sms_returnsTrue() {
        assertThat(strategy.supports("sms")).isTrue();
    }

    @Test
    void supports_other_returnsFalse() {
        assertThat(strategy.supports("email_code")).isFalse();
        assertThat(strategy.supports("email_password")).isFalse();
    }

    // =========================================================
    // Input validation
    // =========================================================

    @Test
    void authenticate_nullMobile_throwsBusinessException() {
        AuthStrategyRequest request = buildRequest(null, "123456");

        assertThatThrownBy(() -> strategy.authenticate(request))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void authenticate_blankMobile_throwsBusinessException() {
        AuthStrategyRequest request = buildRequest("  ", "123456");

        assertThatThrownBy(() -> strategy.authenticate(request))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void authenticate_nullCode_throwsBusinessException() {
        AuthStrategyRequest request = buildRequest("+8613800138000", null);

        assertThatThrownBy(() -> strategy.authenticate(request))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void authenticate_blankCode_throwsBusinessException() {
        AuthStrategyRequest request = buildRequest("+8613800138000", "  ");

        assertThatThrownBy(() -> strategy.authenticate(request))
                .isInstanceOf(BusinessException.class);
    }

    // =========================================================
    // Invalid code
    // =========================================================

    @Test
    void authenticate_invalidCode_throwsBusinessException() {
        when(verificationCodeService.verifyCode(any(), any(), any())).thenReturn(false);
        AuthStrategyRequest request = buildRequest("+8613800138000", "wrong");

        assertThatThrownBy(() -> strategy.authenticate(request))
                .isInstanceOf(BusinessException.class);
    }

    // =========================================================
    // Disabled account
    // =========================================================

    @Test
    void authenticate_disabledAccount_throwsBusinessException() {
        when(verificationCodeService.verifyCode(any(), any(), any())).thenReturn(true);

        User disabled = buildUser("+8613800138000", false);
        when(userMapper.selectOne(any())).thenReturn(disabled);

        assertThatThrownBy(() -> strategy.authenticate(buildRequest("+8613800138000", "123456")))
                .isInstanceOf(BusinessException.class);
    }

    // =========================================================
    // Existing verified user
    // =========================================================

    @Test
    void authenticate_existingVerifiedUser_completesLogin() {
        when(verificationCodeService.verifyCode(any(), any(), any())).thenReturn(true);

        User user = buildUser("+8613800138000", true);
        user.setPhoneVerified(true);
        when(userMapper.selectOne(any())).thenReturn(user);

        AuthenticationResponse response = mock(AuthenticationResponse.class);
        when(loginCompletionHelper.completeLogin(eq(user), any(), any())).thenReturn(response);

        AuthStrategyRequest request = buildRequest("+8613800138000", "123456");
        AuthenticationResponse result = strategy.authenticate(request);

        assertThat(result).isSameAs(response);
        verify(userMapper, never()).updateById(any(User.class)); // already verified
    }

    @Test
    void authenticate_unverifiedPhone_marksPhoneVerified() {
        when(verificationCodeService.verifyCode(any(), any(), any())).thenReturn(true);

        User user = buildUser("+8613800138000", true);
        user.setPhoneVerified(false);
        when(userMapper.selectOne(any())).thenReturn(user);

        when(loginCompletionHelper.completeLogin(any(), any(), any()))
                .thenReturn(mock(AuthenticationResponse.class));

        strategy.authenticate(buildRequest("+8613800138000", "654321"));

        verify(userMapper).updateById(any(User.class));
    }

    // =========================================================
    // Auto-registration — new user
    // =========================================================

    @Test
    void authenticate_newUser_autoRegisters() {
        when(verificationCodeService.verifyCode(any(), any(), any())).thenReturn(true);
        when(userMapper.selectOne(any())).thenReturn(null); // user not found

        when(loginCompletionHelper.completeLogin(any(), any(), any()))
                .thenReturn(mock(AuthenticationResponse.class));

        strategy.authenticate(buildRequest("+8613900139000", "987654"));

        verify(userMapper).insert(any(User.class));
    }

    // =========================================================
    // Helpers
    // =========================================================

    private AuthStrategyRequest buildRequest(String mobile, String code) {
        AuthStrategyRequest r = new AuthStrategyRequest();
        r.setMobile(mobile);
        r.setCode(code);
        r.setChannelCode("sms");
        return r;
    }

    private User buildUser(String mobile, boolean enabled) {
        User user = new User();
        user.setId(1L);
        user.setMobile(mobile);
        user.setEnabled(enabled);
        user.setAccountNonExpired(true);
        user.setAccountNonLocked(true);
        user.setCredentialsNonExpired(true);
        return user;
    }
}
