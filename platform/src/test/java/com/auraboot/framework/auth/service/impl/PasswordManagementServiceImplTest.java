package com.auraboot.framework.auth.service.impl;

import com.auraboot.framework.auth.entity.PasswordHistory;
import com.auraboot.framework.auth.mapper.PasswordHistoryMapper;
import com.auraboot.framework.auth.service.PasswordPolicyService;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.notification.service.EmailSender;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PasswordManagementServiceImpl")
class PasswordManagementServiceImplTest {

    @Mock private UserMapper userMapper;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private PasswordPolicyService passwordPolicyService;
    @Mock private PasswordHistoryMapper passwordHistoryMapper;
    @Mock private EmailSender emailSender;

    private PasswordManagementServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PasswordManagementServiceImpl(userMapper, passwordEncoder,
                passwordPolicyService, passwordHistoryMapper, emailSender);
        ReflectionTestUtils.setField(service, "maxAttempts", 3);
        ReflectionTestUtils.setField(service, "lockoutDurationMinutes", 30);
        ReflectionTestUtils.setField(service, "historyCount", 5);
        ReflectionTestUtils.setField(service, "expiryDays", 90);
        ReflectionTestUtils.setField(service, "resetTokenExpiryMinutes", 30);
        ReflectionTestUtils.setField(service, "frontendBaseUrl", "http://test.local");
    }

    private User user(Long id, String pwHash) {
        User u = new User();
        u.setId(id);
        u.setPid("u-" + id);
        u.setEmail("a@b.com");
        u.setNickName("Nick");
        u.setUserName("nick");
        u.setPassword(pwHash);
        return u;
    }

    @Test
    @DisplayName("changePassword throws when user not found")
    void changePasswordUserMissing() {
        when(userMapper.selectById(1L)).thenReturn(null);
        assertThrows(RootUnCheckedException.class, () -> service.changePassword(1L, "old", "Newpw1!"));
    }

    @Test
    @DisplayName("changePassword throws when current password mismatch")
    void changePasswordMismatch() {
        User u = user(1L, "hash");
        when(userMapper.selectById(1L)).thenReturn(u);
        when(passwordEncoder.matches("old", "hash")).thenReturn(false);
        assertThrows(RootUnCheckedException.class, () -> service.changePassword(1L, "old", "Newpw1!"));
    }

    @Test
    @DisplayName("changePassword throws when new password fails policy")
    void changePasswordPolicyFail() {
        User u = user(1L, "hash");
        when(userMapper.selectById(1L)).thenReturn(u);
        when(passwordEncoder.matches("old", "hash")).thenReturn(true);
        when(passwordPolicyService.validate("weak")).thenReturn(List.of("Too weak"));
        assertThrows(RootUnCheckedException.class, () -> service.changePassword(1L, "old", "weak"));
    }

    @Test
    @DisplayName("changePassword throws when password reused")
    void changePasswordReused() {
        User u = user(1L, "hash");
        when(userMapper.selectById(1L)).thenReturn(u);
        when(passwordEncoder.matches("old", "hash")).thenReturn(true);
        when(passwordPolicyService.validate(any())).thenReturn(List.of());
        when(passwordHistoryMapper.findRecentHashes(1L, 5)).thenReturn(List.of("oldhash"));
        when(passwordEncoder.matches("Newpw1!", "oldhash")).thenReturn(true);
        assertThrows(RootUnCheckedException.class, () -> service.changePassword(1L, "old", "Newpw1!"));
    }

    @Test
    @DisplayName("changePassword saves new hash, increments security version, saves history")
    void changePasswordHappy() {
        User u = user(1L, "hash");
        u.setSecurityVersion(2);
        when(userMapper.selectById(1L)).thenReturn(u);
        when(passwordEncoder.matches("old", "hash")).thenReturn(true);
        when(passwordPolicyService.validate(any())).thenReturn(List.of());
        when(passwordHistoryMapper.findRecentHashes(1L, 5)).thenReturn(List.of());
        when(passwordEncoder.encode("Newpw1!")).thenReturn("encoded");

        service.changePassword(1L, "old", "Newpw1!");
        assertTrue(u.getPassword().equals("encoded"));
        org.junit.jupiter.api.Assertions.assertEquals(3, u.getSecurityVersion());
        verify(passwordHistoryMapper).insert(any(PasswordHistory.class));
        verify(userMapper).updateById(u);
    }

    @Test
    @DisplayName("recordLoginFailure increments and locks after max attempts")
    void recordLoginFailureLocks() {
        User u = user(1L, "hash");
        u.setFailedLoginAttempts(2);
        service.recordLoginFailure(u);
        org.junit.jupiter.api.Assertions.assertEquals(3, u.getFailedLoginAttempts());
        org.junit.jupiter.api.Assertions.assertNotNull(u.getLockedAt());
        verify(userMapper).updateById(u);
    }

    @Test
    @DisplayName("recordLoginFailure increments without locking when below threshold")
    void recordLoginFailureBelowThreshold() {
        User u = user(1L, "hash");
        u.setFailedLoginAttempts(0);
        service.recordLoginFailure(u);
        org.junit.jupiter.api.Assertions.assertEquals(1, u.getFailedLoginAttempts());
        org.junit.jupiter.api.Assertions.assertNull(u.getLockedAt());
    }

    @Test
    @DisplayName("resetLoginFailures clears counter when > 0")
    void resetLoginFailuresClears() {
        User u = user(1L, "hash");
        u.setFailedLoginAttempts(2);
        service.resetLoginFailures(u);
        org.junit.jupiter.api.Assertions.assertEquals(0, u.getFailedLoginAttempts());
        verify(userMapper).updateById(u);
    }

    @Test
    @DisplayName("resetLoginFailures noop when no failures")
    void resetLoginFailuresNoop() {
        User u = user(1L, "hash");
        u.setFailedLoginAttempts(0);
        service.resetLoginFailures(u);
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    @DisplayName("isAccountLocked false when lockedAt null")
    void isAccountLockedNullLockedAt() {
        User u = user(1L, "hash");
        assertFalse(service.isAccountLocked(u));
    }

    @Test
    @DisplayName("isAccountLocked true when within window")
    void isAccountLockedWithinWindow() {
        User u = user(1L, "hash");
        u.setLockedAt(Instant.now().minus(Duration.ofMinutes(5)));
        assertTrue(service.isAccountLocked(u));
    }

    @Test
    @DisplayName("isAccountLocked false when window expired")
    void isAccountLockedExpired() {
        User u = user(1L, "hash");
        u.setLockedAt(Instant.now().minus(Duration.ofHours(2)));
        assertFalse(service.isAccountLocked(u));
    }

    @Test
    @DisplayName("incrementSecurityVersion increments when user found")
    void incrementSecurityVersionFound() {
        User u = user(1L, "hash");
        u.setSecurityVersion(4);
        when(userMapper.selectById(1L)).thenReturn(u);
        service.incrementSecurityVersion(1L);
        org.junit.jupiter.api.Assertions.assertEquals(5, u.getSecurityVersion());
    }

    @Test
    @DisplayName("incrementSecurityVersion is silent when user missing")
    void incrementSecurityVersionMissing() {
        when(userMapper.selectById(1L)).thenReturn(null);
        service.incrementSecurityVersion(1L);
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    @DisplayName("initiatePasswordReset is silent when user not found")
    void initiateResetUserMissing() {
        when(userMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        service.initiatePasswordReset("missing@example.com");
        verify(emailSender, never()).send(any(), any(), any());
    }

    @Test
    @DisplayName("initiatePasswordReset sends email when user found")
    void initiateResetSendsEmail() {
        User u = user(1L, "hash");
        when(userMapper.selectOne(any(QueryWrapper.class))).thenReturn(u);
        service.initiatePasswordReset("a@b.com");
        verify(emailSender).send(eq("a@b.com"), anyString(), anyString());
        verify(userMapper).updateById(u);
    }

    @Test
    @DisplayName("sendPasswordResetEmail throws when user missing")
    void sendResetEmailMissing() {
        when(userMapper.selectById(1L)).thenReturn(null);
        assertThrows(RootUnCheckedException.class, () -> service.sendPasswordResetEmail(1L));
    }

    @Test
    @DisplayName("sendPasswordResetEmail throws when email blank")
    void sendResetEmailBlank() {
        User u = user(1L, "hash");
        u.setEmail("");
        when(userMapper.selectById(1L)).thenReturn(u);
        assertThrows(RootUnCheckedException.class, () -> service.sendPasswordResetEmail(1L));
    }

    @Test
    @DisplayName("resetPasswordWithToken throws on invalid token")
    void resetWithTokenInvalid() {
        when(userMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        assertThrows(RootUnCheckedException.class, () -> service.resetPasswordWithToken("tok", "Newpw1!"));
    }

    @Test
    @DisplayName("resetPasswordWithToken throws on expired token")
    void resetWithTokenExpired() {
        User u = user(1L, "hash");
        u.setResetPasswordSentAt(Instant.now().minus(Duration.ofHours(2)));
        when(userMapper.selectOne(any(QueryWrapper.class))).thenReturn(u);
        assertThrows(RootUnCheckedException.class, () -> service.resetPasswordWithToken("tok", "Newpw1!"));
    }

    @Test
    @DisplayName("resetPasswordWithToken happy path updates password and clears token")
    void resetWithTokenHappy() {
        User u = user(1L, "hash");
        u.setResetPasswordSentAt(Instant.now().minus(Duration.ofMinutes(5)));
        when(userMapper.selectOne(any(QueryWrapper.class))).thenReturn(u);
        when(passwordPolicyService.validate(any())).thenReturn(List.of());
        when(passwordHistoryMapper.findRecentHashes(1L, 5)).thenReturn(List.of());
        when(passwordEncoder.encode("Newpw1!")).thenReturn("enc");

        service.resetPasswordWithToken("tok", "Newpw1!");
        verify(userMapper).clearResetToken(1L);
        verify(passwordHistoryMapper).insert(any(PasswordHistory.class));
    }

    @Test
    @DisplayName("isPasswordExpired false when expiryDays<=0")
    void isPasswordExpiredDisabled() {
        ReflectionTestUtils.setField(service, "expiryDays", 0);
        User u = user(1L, "hash");
        u.setPasswordChangedAt(Instant.now().minus(Duration.ofDays(1000)));
        assertFalse(service.isPasswordExpired(u));
    }

    @Test
    @DisplayName("isPasswordExpired false when never changed")
    void isPasswordExpiredNeverChanged() {
        User u = user(1L, "hash");
        u.setPasswordChangedAt(null);
        assertFalse(service.isPasswordExpired(u));
    }

    @Test
    @DisplayName("isPasswordExpired true when older than expiry window")
    void isPasswordExpiredOld() {
        User u = user(1L, "hash");
        u.setPasswordChangedAt(Instant.now().minus(Duration.ofDays(120)));
        assertTrue(service.isPasswordExpired(u));
    }
}
