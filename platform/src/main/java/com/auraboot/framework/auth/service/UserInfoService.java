package com.auraboot.framework.auth.service;

import com.auraboot.framework.auth.dto.UserInfoResponse;

/**
 * Assembles the current user's profile, permissions, and preferences
 * for the /api/auth/me endpoint.
 *
 * @since 7.1.0
 */
public interface UserInfoService {

    /**
     * Build the full user info response for the currently authenticated user.
     *
     * @param userId   current user ID
     * @param userPid  current user PID
     * @param tenantId current tenant ID (may be null)
     * @return assembled user info
     */
    UserInfoResponse buildCurrentUserInfo(Long userId, String userPid, Long tenantId);
}
