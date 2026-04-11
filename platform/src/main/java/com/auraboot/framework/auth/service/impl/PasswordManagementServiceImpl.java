package com.auraboot.framework.auth.service.impl;

import com.auraboot.framework.auth.entity.PasswordHistory;
import com.auraboot.framework.auth.mapper.PasswordHistoryMapper;
import com.auraboot.framework.auth.service.PasswordManagementService;
import com.auraboot.framework.auth.service.PasswordPolicyService;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.notification.service.EmailSender;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordManagementServiceImpl implements PasswordManagementService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicyService passwordPolicyService;
    private final PasswordHistoryMapper passwordHistoryMapper;
    private final EmailSender emailSender;

    @Value("${security.lockout.max-attempts:5}")
    private int maxAttempts;

    @Value("${security.lockout.duration-minutes:30}")
    private int lockoutDurationMinutes;

    @Value("${security.password.history-count:5}")
    private int historyCount;

    @Value("${security.password.expiry-days:90}")
    private int expiryDays;

    @Value("${security.password.reset-token-expiry-minutes:30}")
    private int resetTokenExpiryMinutes;

    @Value("${security.frontend-base-url:http://localhost:5173}")
    private String frontendBaseUrl;

    @Override
    @Transactional
    public void changePassword(Long userId, String currentPassword, String newPassword) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new RootUnCheckedException(ResponseCode.NOT_FOUND, "User not found");
        }

        // Verify current password
        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new RootUnCheckedException(ResponseCode.InvalidUserNameOrPassword, "Current password is incorrect");
        }

        // Validate new password strength
        List<String> errors = passwordPolicyService.validate(newPassword);
        if (!errors.isEmpty()) {
            throw new RootUnCheckedException(ResponseCode.PasswordTooWeak, String.join("; ", errors));
        }

        // Check password history
        if (isPasswordReused(userId, newPassword)) {
            throw new RootUnCheckedException(ResponseCode.PasswordReused,
                    "Cannot reuse any of your last " + historyCount + " passwords");
        }

        // Save current password to history before changing
        savePasswordHistory(userId, user.getPassword());

        // Encode and save
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setPasswordChangedAt(Instant.now());
        user.setMustChangePassword(false);
        user.setSecurityVersion((user.getSecurityVersion() != null ? user.getSecurityVersion() : 0) + 1);
        user.setUpdatedAt(Instant.now());
        userMapper.updateById(user);

        log.info("Password changed for user {}, security version incremented to {}", userId, user.getSecurityVersion());
    }

    @Override
    @Transactional
    public void recordLoginFailure(User user) {
        int attempts = (user.getFailedLoginAttempts() != null ? user.getFailedLoginAttempts() : 0) + 1;
        user.setFailedLoginAttempts(attempts);

        if (attempts >= maxAttempts) {
            user.setLockedAt(Instant.now());
            log.warn("Account locked for user {} after {} failed attempts", user.getId(), attempts);
        }

        user.setUpdatedAt(Instant.now());
        userMapper.updateById(user);
    }

    @Override
    @Transactional
    public void resetLoginFailures(User user) {
        if (user.getFailedLoginAttempts() != null && user.getFailedLoginAttempts() > 0) {
            user.setFailedLoginAttempts(0);
            user.setLockedAt(null);
            user.setUpdatedAt(Instant.now());
            userMapper.updateById(user);
        }
    }

    @Override
    public boolean isAccountLocked(User user) {
        if (user.getLockedAt() == null) {
            return false;
        }
        Instant lockExpiry = user.getLockedAt().plus(Duration.ofMinutes(lockoutDurationMinutes));
        return !Instant.now().isAfter(lockExpiry);
    }

    @Override
    @Transactional
    public void incrementSecurityVersion(Long userId) {
        User user = userMapper.selectById(userId);
        if (user != null) {
            user.setSecurityVersion((user.getSecurityVersion() != null ? user.getSecurityVersion() : 0) + 1);
            user.setUpdatedAt(Instant.now());
            userMapper.updateById(user);
        }
    }

    @Override
    @Transactional
    public void initiatePasswordReset(String email) {
        QueryWrapper<User> qw = new QueryWrapper<>();
        qw.eq("email", email);
        User user = userMapper.selectOne(qw);

        if (user == null) {
            // Don't reveal whether email exists — always return success
            log.info("Password reset requested for unknown email: {}***", email.length() > 3 ? email.substring(0, 3) : "***");
            return;
        }

        sendResetEmail(user);
    }

    @Override
    @Transactional
    public void sendPasswordResetEmail(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new RootUnCheckedException(ResponseCode.NOT_FOUND, "User not found");
        }
        sendResetEmail(user);
    }

    @Override
    @Transactional
    public void resetPasswordWithToken(String token, String newPassword) {
        // Compare by hashed token — plaintext token is never stored
        String tokenHash = hashToken(token);
        QueryWrapper<User> qw = new QueryWrapper<>();
        qw.eq("reset_password_token", tokenHash);
        User user = userMapper.selectOne(qw);

        if (user == null) {
            throw new RootUnCheckedException(ResponseCode.BadParam, "Invalid or expired reset token");
        }

        // Check token expiry
        if (user.getResetPasswordSentAt() == null ||
            user.getResetPasswordSentAt().plus(Duration.ofMinutes(resetTokenExpiryMinutes)).isBefore(Instant.now())) {
            throw new RootUnCheckedException(ResponseCode.BadParam, "Reset token has expired");
        }

        // Validate new password
        List<String> errors = passwordPolicyService.validate(newPassword);
        if (!errors.isEmpty()) {
            throw new RootUnCheckedException(ResponseCode.PasswordTooWeak, String.join("; ", errors));
        }

        // Check password history
        if (isPasswordReused(user.getId(), newPassword)) {
            throw new RootUnCheckedException(ResponseCode.PasswordReused,
                    "Cannot reuse any of your last " + historyCount + " passwords");
        }

        // Save current password to history
        savePasswordHistory(user.getId(), user.getPassword());

        // Update password and non-null fields
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setPasswordChangedAt(Instant.now());
        user.setMustChangePassword(false);
        user.setSecurityVersion((user.getSecurityVersion() != null ? user.getSecurityVersion() : 0) + 1);
        user.setUpdatedAt(Instant.now());
        userMapper.updateById(user);
        // Explicitly clear token fields (updateById skips null values)
        userMapper.clearResetToken(user.getId());

        log.info("Password reset successfully for user {}", user.getId());
    }

    @Override
    public boolean isPasswordExpired(User user) {
        if (expiryDays <= 0) return false;
        // Backward compatible: null passwordChangedAt means not expired
        if (user.getPasswordChangedAt() == null) return false;
        return user.getPasswordChangedAt().plus(Duration.ofDays(expiryDays)).isBefore(Instant.now());
    }

    private boolean isPasswordReused(Long userId, String newPassword) {
        if (historyCount <= 0) return false;
        List<String> recentHashes = passwordHistoryMapper.findRecentHashes(userId, historyCount);
        return recentHashes.stream().anyMatch(hash -> passwordEncoder.matches(newPassword, hash));
    }

    private void savePasswordHistory(Long userId, String passwordHash) {
        PasswordHistory history = new PasswordHistory();
        history.setUserId(userId);
        history.setPasswordHash(passwordHash);
        history.setCreatedAt(Instant.now());
        passwordHistoryMapper.insert(history);
    }

    private static String generateSecureToken() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private void sendResetEmail(User user) {
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            throw new RootUnCheckedException(ResponseCode.BadParam, "User email is required for password reset");
        }

        String token = generateSecureToken();
        String tokenHash = hashToken(token);
        user.setResetPasswordToken(tokenHash);
        user.setResetPasswordSentAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        userMapper.updateById(user);

        String resetLink = frontendBaseUrl + "/reset-password?token=" + token;
        String subject = "Reset your AuraBoot password";
        String displayName = user.getNickName() != null && !user.getNickName().isBlank()
            ? user.getNickName()
            : (user.getUserName() != null && !user.getUserName().isBlank() ? user.getUserName() : user.getEmail());
        String htmlBody = """
            <p>Hello %s,</p>
            <p>We received a request to reset your AuraBoot password.</p>
            <p><a href="%s">Click here to reset your password</a></p>
            <p>This link will expire in %d minutes.</p>
            <p>If you did not request this change, you can ignore this email.</p>
            """.formatted(displayName, resetLink, resetTokenExpiryMinutes);

        emailSender.send(user.getEmail(), subject, htmlBody);
        log.info("Password reset email sent to user {}", user.getId());
        log.debug("Password reset initiated for {} — token hash: {}", user.getEmail(), tokenHash);
    }

    private static String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
