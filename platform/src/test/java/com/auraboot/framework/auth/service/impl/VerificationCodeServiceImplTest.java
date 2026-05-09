package com.auraboot.framework.auth.service.impl;

import com.auraboot.framework.auth.entity.VerificationCode;
import com.auraboot.framework.auth.mapper.VerificationCodeMapper;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.notification.service.EmailSender;
import com.auraboot.framework.notification.sms.SmsSendResult;
import com.auraboot.framework.notification.sms.SmsSenderRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("VerificationCodeServiceImpl")
class VerificationCodeServiceImplTest {

    @Mock private VerificationCodeMapper mapper;
    @Mock private SmsSenderRouter smsSenderRouter;
    @Mock private EmailSender emailSender;

    private VerificationCodeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new VerificationCodeServiceImpl(mapper, smsSenderRouter, emailSender);
    }

    @Test
    @DisplayName("sendCode rejects when same target requested within 60s")
    void sendCodeRateLimitTarget() {
        VerificationCode prev = new VerificationCode();
        prev.setCreatedAt(Instant.now().minus(Duration.ofSeconds(10)));
        when(mapper.findLatestUnverified("a@b.com", "login")).thenReturn(prev);

        assertThrows(BusinessException.class, () -> service.sendCode("a@b.com", "login", "1.1.1.1"));
    }

    @Test
    @DisplayName("sendCode rejects when IP exceeded 10/hour")
    void sendCodeRateLimitIp() {
        when(mapper.findLatestUnverified(any(), any())).thenReturn(null);
        when(mapper.countByIpInLastHour("1.1.1.1")).thenReturn(10);

        assertThrows(BusinessException.class, () -> service.sendCode("a@b.com", "login", "1.1.1.1"));
    }

    @Test
    @DisplayName("sendCode dispatches email when target is email")
    void sendCodeEmail() {
        when(mapper.findLatestUnverified(any(), any())).thenReturn(null);
        when(mapper.countByIpInLastHour(any())).thenReturn(0);
        service.sendCode("a@b.com", "login", "1.1.1.1");
        verify(emailSender).send(eq("a@b.com"), anyString(), anyString());
        verify(smsSenderRouter, never()).send(any(), any(), any());
        verify(mapper).insert(any(VerificationCode.class));
    }

    @Test
    @DisplayName("sendCode dispatches SMS when target is phone")
    void sendCodeSms() {
        when(mapper.findLatestUnverified(any(), any())).thenReturn(null);
        when(mapper.countByIpInLastHour(any())).thenReturn(0);
        when(smsSenderRouter.send(eq("13800138000"), any(), any())).thenReturn(SmsSendResult.ok("msg-id"));
        service.sendCode("13800138000", "login", "1.1.1.1");
        verify(smsSenderRouter).send(eq("13800138000"), eq("verification_code"), any());
        verify(emailSender, never()).send(any(), any(), any());
    }

    @Test
    @DisplayName("sendCode SMS failure throws")
    void sendCodeSmsFailure() {
        when(mapper.findLatestUnverified(any(), any())).thenReturn(null);
        when(mapper.countByIpInLastHour(any())).thenReturn(0);
        when(smsSenderRouter.send(any(), any(), any())).thenReturn(SmsSendResult.fail("err"));
        assertThrows(BusinessException.class,
                () -> service.sendCode("13800138000", "login", "1.1.1.1"));
    }

    @Test
    @DisplayName("sendCode bypasses target rate limit when no previous record exists")
    void sendCodeNoPreviousRecord() {
        when(mapper.findLatestUnverified(any(), any())).thenReturn(null);
        // null ipAddress also bypasses IP rate limit
        service.sendCode("a@b.com", "login", null);
        verify(mapper).insert(any(VerificationCode.class));
    }

    @Test
    @DisplayName("verifyCode false when no unverified code")
    void verifyCodeNone() {
        when(mapper.findLatestUnverified(any(), any())).thenReturn(null);
        assertFalse(service.verifyCode("a@b.com", "123456", "login"));
    }

    @Test
    @DisplayName("verifyCode false when code is expired")
    void verifyCodeExpired() {
        VerificationCode c = new VerificationCode();
        c.setCode("123456");
        c.setExpiresAt(Instant.now().minus(Duration.ofMinutes(1)));
        c.setAttempts(0);
        when(mapper.findLatestUnverified(any(), any())).thenReturn(c);
        assertFalse(service.verifyCode("a@b.com", "123456", "login"));
    }

    @Test
    @DisplayName("verifyCode false and marks as expired when max attempts reached")
    void verifyCodeMaxAttempts() {
        VerificationCode c = new VerificationCode();
        c.setCode("123456");
        c.setExpiresAt(Instant.now().plus(Duration.ofMinutes(1)));
        c.setAttempts(3);
        when(mapper.findLatestUnverified(any(), any())).thenReturn(c);
        assertFalse(service.verifyCode("a@b.com", "123456", "login"));
        verify(mapper).updateById(c);
    }

    @Test
    @DisplayName("verifyCode increments attempts on wrong code and returns false")
    void verifyCodeWrong() {
        VerificationCode c = new VerificationCode();
        c.setCode("123456");
        c.setExpiresAt(Instant.now().plus(Duration.ofMinutes(1)));
        c.setAttempts(0);
        when(mapper.findLatestUnverified(any(), any())).thenReturn(c);
        assertFalse(service.verifyCode("a@b.com", "wrong0", "login"));
        org.junit.jupiter.api.Assertions.assertEquals(1, c.getAttempts());
        verify(mapper).updateById(c);
    }

    @Test
    @DisplayName("verifyCode marks verified on correct code")
    void verifyCodeCorrect() {
        VerificationCode c = new VerificationCode();
        c.setCode("123456");
        c.setExpiresAt(Instant.now().plus(Duration.ofMinutes(1)));
        c.setAttempts(0);
        when(mapper.findLatestUnverified(any(), any())).thenReturn(c);
        assertTrue(service.verifyCode("a@b.com", "123456", "login"));
        org.junit.jupiter.api.Assertions.assertTrue(Boolean.TRUE.equals(c.getVerified()));
        verify(mapper).updateById(c);
    }
}
