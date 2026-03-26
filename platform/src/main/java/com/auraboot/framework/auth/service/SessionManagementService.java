package com.auraboot.framework.auth.service;

import com.auraboot.framework.auth.entity.UserSession;

import java.util.List;

/**
 * Session management service for multi-device tracking and revocation.
 */
public interface SessionManagementService {

    /**
     * Create a new session record for a login event.
     */
    UserSession createSession(Long userId, String token, String ipAddress, String userAgent);

    /**
     * Check if a session is still valid (not revoked).
     */
    boolean isSessionValid(String token);

    /**
     * Revoke a specific session by PID.
     */
    void revokeSession(Long userId, String sessionPid);

    /**
     * Revoke all sessions for a user (logout everywhere).
     */
    void revokeAllSessions(Long userId);

    /**
     * Get all active sessions for a user.
     */
    List<UserSession> getActiveSessions(Long userId);

    /**
     * Update last active time (with 5-minute throttle to avoid DB pressure).
     */
    void updateLastActive(String token);
}
