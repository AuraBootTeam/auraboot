package com.auraboot.framework.connector.saas.oauth;

import com.auraboot.framework.connector.saas.SaasConnectorConfig;

import java.time.Instant;
import java.util.List;

/**
 * Vendor-specific token-refresh strategy. The {@link OAuth2TokenStore} owns
 * persistence + locking; each vendor connector provides the wire shape via a
 * {@code @Component} implementing this SPI.
 *
 * <p>Implementations MUST:
 * <ul>
 *   <li>Return a {@link RefreshedToken} with an {@code expiresAt} computed
 *       from the provider's {@code expires_in} response field (UTC).</li>
 *   <li>Return the new refresh token when the provider rotates it (e.g.
 *       Salesforce does NOT rotate; HubSpot does), or echo the current one
 *       back when it doesn't rotate.</li>
 *   <li>Throw {@link TokenRefreshException} on any wire / parse failure; the
 *       store will mark the row and surface the error to callers.</li>
 * </ul>
 */
public interface TokenRefresher {

    /** Vendor key matching {@link SaasConnectorConfig#vendor()}. */
    String vendor();

    /**
     * Exchange {@code currentRefreshToken} for a new access token.
     *
     * @param config            decrypted SaaS config (client id/secret/baseUrl/scopes)
     * @param currentRefreshToken decrypted refresh token in plaintext
     */
    RefreshedToken refresh(SaasConnectorConfig config, String currentRefreshToken);

    record RefreshedToken(
            String accessToken,
            String refreshToken,
            Instant expiresAt,
            List<String> scopes
    ) {
        public RefreshedToken {
            if (accessToken == null || accessToken.isBlank()) {
                throw new IllegalArgumentException("accessToken required");
            }
            if (expiresAt == null) throw new IllegalArgumentException("expiresAt required");
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
        }
    }
}
