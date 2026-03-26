package com.auraboot.framework.auth;

import com.auraboot.framework.auth.dto.RegisterRequest;
import com.auraboot.framework.auth.service.AuthService;
import com.auraboot.framework.auth.service.PasswordManagementService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.mapper.UserMapper;
import com.auraboot.framework.user.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for PasswordManagementService — change password, lockout, reset flow.
 */
@SpringBootTest(classes = com.auraboot.framework.application.TestApplication.class)
@ActiveProfiles("integration-test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PasswordManagementIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private PasswordManagementService passwordManagementService;

    @Autowired
    private AuthService authService;

    @Autowired
    private UserService userService;

    @Autowired
    private UserMapper userMapper;

    private final String testRunId = String.valueOf(System.currentTimeMillis());

    // Shared across ordered tests
    private Long testUserId;
    private String testEmail;
    private String currentPassword;

    // -----------------------------------------------------------------------
    // Test 1: changePassword with correct current password updates successfully
    // -----------------------------------------------------------------------
    @Test
    @Order(1)
    @DisplayName("changePassword_withCorrectCurrent_updatesSuccessfully")
    void changePassword_withCorrectCurrent_updatesSuccessfully() {
        testEmail = "pwtest-" + testRunId + "@example.com";
        String initialPassword = "OldPass123!";
        currentPassword = "NewPass456!";

        RegisterRequest reg = new RegisterRequest();
        reg.setEmail(testEmail);
        reg.setPassword(initialPassword);
        reg.setDisplayName("PW Test User");
        authService.register(reg);

        User user = userService.findByEmail(testEmail);
        assertNotNull(user, "Registered user should exist");
        testUserId = user.getId();

        assertDoesNotThrow(
                () -> passwordManagementService.changePassword(testUserId, initialPassword, currentPassword),
                "changePassword with correct current password should not throw");
    }

    // -----------------------------------------------------------------------
    // Test 2: changePassword increments securityVersion, invalidating old tokens
    // -----------------------------------------------------------------------
    @Test
    @Order(2)
    @DisplayName("changePassword_incrementsSecurityVersion_invalidatesOldTokens")
    void changePassword_incrementsSecurityVersion_invalidatesOldTokens() {
        // Ensure test 1 ran; if not, create user
        if (testUserId == null) {
            testEmail = "pwtest-" + testRunId + "@example.com";
            currentPassword = "OldPass123!";
            RegisterRequest reg = new RegisterRequest();
            reg.setEmail(testEmail);
            reg.setPassword("InitialPass123!");
            reg.setDisplayName("PW Test User");
            authService.register(reg);
            User u = userService.findByEmail(testEmail);
            testUserId = u.getId();
            passwordManagementService.changePassword(testUserId, "InitialPass123!", currentPassword);
        }

        User userBefore = userMapper.selectById(testUserId);
        assertNotNull(userBefore, "User should exist before password change");
        int svBefore = userBefore.getSecurityVersion() != null ? userBefore.getSecurityVersion() : 0;

        String nextPassword = "NextPass789!";
        passwordManagementService.changePassword(testUserId, currentPassword, nextPassword);
        currentPassword = nextPassword;

        User userAfter = userMapper.selectById(testUserId);
        assertNotNull(userAfter, "User should exist after password change");
        int svAfter = userAfter.getSecurityVersion() != null ? userAfter.getSecurityVersion() : 0;

        assertEquals(svBefore + 1, svAfter,
                "Security version should increment by 1 after password change");
    }

    // -----------------------------------------------------------------------
    // Test 3: recordLoginFailure after max attempts locks account
    // -----------------------------------------------------------------------
    @Test
    @Order(3)
    @DisplayName("recordLoginFailure_afterMaxAttempts_locksAccount")
    void recordLoginFailure_afterMaxAttempts_locksAccount() {
        String lockEmail = "locktest-" + testRunId + "@example.com";

        RegisterRequest reg = new RegisterRequest();
        reg.setEmail(lockEmail);
        reg.setPassword("LockPass123!");
        reg.setDisplayName("Lock Test User");
        authService.register(reg);

        // Record 5 failures — reload user entity each time so the service reads fresh state
        for (int i = 0; i < 5; i++) {
            User freshUser = userService.findByEmail(lockEmail);
            assertNotNull(freshUser, "User must exist during failure recording (iteration " + i + ")");
            passwordManagementService.recordLoginFailure(freshUser);
        }

        User lockedUser = userService.findByEmail(lockEmail);
        assertNotNull(lockedUser, "User should still exist after lockout");
        assertTrue(passwordManagementService.isAccountLocked(lockedUser),
                "Account should be locked after 5 failed login attempts");
    }

    // -----------------------------------------------------------------------
    // Test 4: initiatePasswordReset stores reset token in DB
    // -----------------------------------------------------------------------
    @Test
    @Order(4)
    @DisplayName("initiatePasswordReset_sendsResetToken")
    void initiatePasswordReset_sendsResetToken() {
        // Ensure we have a test user
        if (testEmail == null) {
            testEmail = "pwtest-" + testRunId + "@example.com";
            RegisterRequest reg = new RegisterRequest();
            reg.setEmail(testEmail);
            reg.setPassword("OldPass123!");
            reg.setDisplayName("PW Test User");
            authService.register(reg);
            testUserId = userService.findByEmail(testEmail).getId();
            currentPassword = "OldPass123!";
        }

        passwordManagementService.initiatePasswordReset(testEmail);

        // Reload user to check token was persisted
        QueryWrapper<User> qw = new QueryWrapper<>();
        qw.eq("email", testEmail);
        User user = userMapper.selectOne(qw);

        assertNotNull(user, "User should exist after initiating reset");
        assertNotNull(user.getResetPasswordToken(),
                "reset_password_token should be set after initiatePasswordReset");
        assertNotNull(user.getResetPasswordSentAt(),
                "reset_password_sent_at should be set after initiatePasswordReset");
    }

    // -----------------------------------------------------------------------
    // Test 5: resetPasswordWithToken with known plaintext token updates password
    // -----------------------------------------------------------------------
    @Test
    @Order(5)
    @DisplayName("resetPasswordWithToken_validToken_updatesPassword")
    void resetPasswordWithToken_validToken_updatesPassword() {
        // Ensure we have a test user
        if (testUserId == null) {
            testEmail = "pwtest-" + testRunId + "@example.com";
            RegisterRequest reg = new RegisterRequest();
            reg.setEmail(testEmail);
            reg.setPassword("OldPass123!");
            reg.setDisplayName("PW Test User");
            authService.register(reg);
            testUserId = userService.findByEmail(testEmail).getId();
        }

        // Set a known reset token directly via UserMapper so we control the plaintext
        String plaintextToken = "known-reset-token-" + testRunId;
        String tokenHash = sha256(plaintextToken);

        User user = userMapper.selectById(testUserId);
        assertNotNull(user, "User should exist");
        user.setResetPasswordToken(tokenHash);
        user.setResetPasswordSentAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        userMapper.updateById(user);

        // Now reset using the plaintext token
        String newPassword = "ResetPass789!";
        assertDoesNotThrow(
                () -> passwordManagementService.resetPasswordWithToken(plaintextToken, newPassword),
                "resetPasswordWithToken with valid token should not throw");

        // Verify token was cleared after use
        User updated = userMapper.selectById(testUserId);
        assertNotNull(updated, "User should still exist after reset");
        assertNull(updated.getResetPasswordToken(),
                "reset_password_token should be cleared after successful password reset");
    }

    // -----------------------------------------------------------------------
    // Helper: SHA-256 hex digest (mirrors PasswordManagementServiceImpl.hashToken)
    // -----------------------------------------------------------------------
    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
