package com.auraboot.framework.user.service;

import com.auraboot.framework.auth.service.VerificationCodeService;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserEmailLinkServiceTest {

    @Mock UserMapper userMapper;
    @Mock VerificationCodeService verificationCodeService;

    private UserEmailLinkService service;

    @BeforeEach
    void setUp() {
        service = new UserEmailLinkService(userMapper, verificationCodeService);
    }

    @Test
    void sendCodeNormalizesAddressBeforeAvailabilityAndDelivery() {
        service.sendCode(1L, "  Me@QQ.COM ", "127.0.0.1");

        verify(userMapper).findByNormalizedEmail("me@qq.com");
        verify(verificationCodeService).sendCode("me@qq.com", "email_bind", "127.0.0.1");
    }

    @Test
    void refusesAddressOwnedByAnotherUser() {
        User owner = new User();
        owner.setId(2L);
        when(userMapper.findByNormalizedEmail("used@qq.com")).thenReturn(owner);

        assertThatThrownBy(() -> service.sendCode(1L, "used@qq.com", null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void confirmConsumesOtpThenAtomicallyBindsVerifiedAddress() {
        User updated = new User();
        updated.setId(1L);
        updated.setEmail("me@qq.com");
        updated.setEmailVerified(true);
        when(verificationCodeService.verifyCode("me@qq.com", "123456", "email_bind"))
                .thenReturn(true);
        when(userMapper.bindVerifiedEmail(1L, "me@qq.com")).thenReturn(1);
        when(userMapper.selectById(1L)).thenReturn(updated);

        var response = service.confirm(1L, "ME@qq.com", "123456");

        assertThat(response.linked()).isTrue();
        assertThat(response.verified()).isTrue();
        assertThat(response.maskedEmail()).isEqualTo("me***@qq.com");
        verify(userMapper).bindVerifiedEmail(1L, "me@qq.com");
    }

    @Test
    void confirmDoesNotWriteWhenOtpIsInvalid() {
        when(verificationCodeService.verifyCode("me@qq.com", "000000", "email_bind"))
                .thenReturn(false);

        assertThatThrownBy(() -> service.confirm(1L, "me@qq.com", "000000"))
                .isInstanceOf(BusinessException.class);
    }
}
