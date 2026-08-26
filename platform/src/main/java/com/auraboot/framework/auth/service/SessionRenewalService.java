package com.auraboot.framework.auth.service;

import com.auraboot.framework.auth.dto.TokenRenewResponse;

/**
 * Sliding-session renewal: mints a fresh access token for a still-valid,
 * in-window server-side session and rotates the session record so the old
 * token is invalidated immediately.
 */
public interface SessionRenewalService {

    /**
     * @param bearerToken the current (still valid) access token
     * @param ipAddress   client IP for the renewed session record
     * @param userAgent   client User-Agent for the renewed session record
     * @return renewed token + expiry, or throws when the session is revoked,
     *         outside its renewal window, or the user/tenant is no longer usable
     */
    TokenRenewResponse renew(String bearerToken, String ipAddress, String userAgent);
}
