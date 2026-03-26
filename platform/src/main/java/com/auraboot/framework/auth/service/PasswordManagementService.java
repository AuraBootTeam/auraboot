package com.auraboot.framework.auth.service;

import com.auraboot.framework.user.dao.entity.User;

/**
 * Password management service — change password, lockout, security version.
 */
public interface PasswordManagementService {

    /**
     * Change user password after verifying current password.
     * Increments security version to invalidate existing tokens.
     */
    void changePassword(Long userId, String currentPassword, String newPassword);

    /**
     * Record a failed login attempt. Locks account if max attempts exceeded.
     */
    void recordLoginFailure(User user);

    /**
     * Reset failed login counter on successful login.
     */
    void resetLoginFailures(User user);

    /**
     * Check if account is currently locked.
     */
    boolean isAccountLocked(User user);

    /**
     * Increment security version to invalidate all existing tokens.
     */
    void incrementSecurityVersion(Long userId);

    /**
     * Initiate password reset flow — generates token and sends email/logs link.
     */
    void initiatePasswordReset(String email);

    /**
     * Reset password using a valid reset token.
     */
    void resetPasswordWithToken(String token, String newPassword);

    /**
     * Check if password is expired based on configured expiry days.
     */
    boolean isPasswordExpired(com.auraboot.framework.user.dao.entity.User user);
}
