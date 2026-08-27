package com.auraboot.framework.auth.service;

import com.auraboot.framework.auth.dto.TokenRenewResponse;

/**
 * Sliding-session renewal: mints a fresh access token for a still-valid,
 * in-window server-side session. The old token's session row is deliberately
 * kept active until the token's natural JWT expiry, because the renewed cookie
 * is a best-effort response header that an intermediate redirect can drop.
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
