package com.auraboot.framework.connector.saas;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Configuration envelope for a SaaS connector adapter
 * (PRD 18 §B.3.2 — Salesforce / HubSpot / Stripe / Shopify / DingTalk scaffold).
 *
 * <p>Secrets ({@code clientSecret}, {@code refreshToken}) are expected to be
 * encrypted at rest by {@code ConnectorCredentialResolver}; this record only
 * shuttles the decrypted values through one in-process call.
 *
 * @param vendor             stable connector key (e.g. "saas-salesforce")
 * @param authType           one of "oauth2", "apikey", "basic"
 * @param clientId           OAuth client id (or API key)
 * @param clientSecret       OAuth client secret (or API secret); decrypted
 * @param refreshToken       OAuth refresh token; decrypted; may be null for apikey
 * @param scopes             OAuth scope list; empty list when not applicable
 * @param apiBaseUrl         API base URL (per-tenant for Shopify/Salesforce, global for others)
 * @param rateLimitPerMinute soft rate-limit ceiling; null = vendor default
 * @param extras             vendor-specific extras (corpId, agentId, shopDomain, ...)
 * @since 5.3.0
 */
public record SaasConnectorConfig(
        String vendor,
        String authType,
        String clientId,
        String clientSecret,
        String refreshToken,
        List<String> scopes,
        String apiBaseUrl,
        Integer rateLimitPerMinute,
        Map<String, Object> extras
) {
    public SaasConnectorConfig {
        Objects.requireNonNull(vendor, "vendor");
        Objects.requireNonNull(authType, "authType");
        if (!List.of("oauth2", "apikey", "basic").contains(authType)) {
            throw new IllegalArgumentException("authType must be oauth2/apikey/basic, got " + authType);
        }
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
        extras = extras == null ? Map.of() : Map.copyOf(extras);
    }
}
