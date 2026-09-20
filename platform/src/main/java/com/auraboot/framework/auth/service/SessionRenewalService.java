package com.auraboot.framework.auth.service;

import com.auraboot.framework.auth.dto.TokenRenewResponse;

/**
 * Sliding-session renewal: mints a fresh access token for a still-valid,
 * server-side session before its fixed absolute deadline. Every renewal shares
 * the same revocable session row. Previously issued tokens retain their expiry
 * so concurrent requests and dropped cookie responses do not break the session.
 */
public interface SessionRenewalService {

    /**
     * @param bearerToken the current (still valid) access token
     * @param ipAddress   client IP (retained for interface compatibility)
     * @param userAgent   client User-Agent (retained for interface compatibility)
     * @return renewed token + expiry, or throws when the session is revoked,
     *         past its absolute deadline, or the user/tenant is no longer usable
     */
    TokenRenewResponse renew(String bearerToken, String ipAddress, String userAgent);
}
