package com.auraboot.framework.connector.saas.oauth;

/**
 * Raised when a {@link TokenRefresher} cannot exchange the refresh token for
 * a fresh access token. Common causes: refresh token revoked by the user at
 * the vendor, network outage, vendor 5xx.
 */
public class TokenRefreshException extends RuntimeException {
    public TokenRefreshException(String message) { super(message); }
    public TokenRefreshException(String message, Throwable cause) { super(message, cause); }
}
