package com.auraboot.framework.auth.dto;

import lombok.Data;

/**
 * Payload returned by the sliding-session renewal endpoint.
 *
 * @param jwt       the renewed access token (same claims/context as the login token)
 * @param expiresAt epoch seconds at which the renewed token expires
 */
@Data
public class TokenRenewResponse {
    private final String jwt;
    private final Long expiresAt;

    public TokenRenewResponse(String jwt, Long expiresAt) {
        this.jwt = jwt;
        this.expiresAt = expiresAt;
    }
}
