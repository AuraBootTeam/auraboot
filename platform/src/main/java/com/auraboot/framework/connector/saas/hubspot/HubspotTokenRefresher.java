package com.auraboot.framework.connector.saas.hubspot;

import com.auraboot.framework.connector.saas.SaasConnectorConfig;
import com.auraboot.framework.connector.saas.http.SaasHttpClient;
import com.auraboot.framework.connector.saas.http.SaasHttpException;
import com.auraboot.framework.connector.saas.http.SaasHttpRequest;
import com.auraboot.framework.connector.saas.oauth.TokenRefreshException;
import com.auraboot.framework.connector.saas.oauth.TokenRefresher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * HubSpot OAuth2 refresh-token exchange. PRD 18 §B.3.2.
 *
 * <p>POST {@code https://api.hubapi.com/oauth/v1/token} with
 * {@code application/x-www-form-urlencoded} body:
 *
 * <pre>
 *   grant_type=refresh_token
 *   client_id=&lt;client_id&gt;
 *   client_secret=&lt;client_secret&gt;
 *   refresh_token=&lt;current_refresh_token&gt;
 * </pre>
 *
 * <p>Response shape:
 *
 * <pre>{@code
 * {
 *   "access_token":  "<new>",
 *   "refresh_token": "<rotated>",   // HubSpot DOES rotate
 *   "expires_in":    1800,           // seconds
 *   "token_type":    "bearer"
 * }
 * }</pre>
 *
 * <p>Errors (400 / 401) → {@link TokenRefreshException} so the store marks
 * the row and surfaces the failure to the caller.
 */
@Slf4j
@Component
public class HubspotTokenRefresher implements TokenRefresher {

    public static final String VENDOR = "saas-hubspot";
    public static final String TOKEN_URL = "https://api.hubapi.com/oauth/v1/token";

    private final SaasHttpClient http;
    private final ObjectMapper jsonMapper;

    public HubspotTokenRefresher(SaasHttpClient http, ObjectMapper jsonMapper) {
        this.http = http;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public String vendor() { return VENDOR; }

    @Override
    public RefreshedToken refresh(SaasConnectorConfig config, String currentRefreshToken) {
        if (currentRefreshToken == null || currentRefreshToken.isBlank()) {
            throw new TokenRefreshException("HubSpot refresh requires a non-blank refresh_token");
        }
        String body = "grant_type=refresh_token"
                + "&client_id=" + enc(config.clientId())
                + "&client_secret=" + enc(config.clientSecret())
                + "&refresh_token=" + enc(currentRefreshToken);
        SaasHttpRequest req = SaasHttpRequest.builder()
                .vendor(VENDOR)
                .method("POST")
                .url(TOKEN_URL)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .body(body)
                .build();
        try {
            JsonNode root = http.executeForJson(req,
                    SaasHttpClient.RetryPolicy.DEFAULT,
                    SaasHttpClient.RateLimit.HUBSPOT);
            String access = textOrThrow(root, "access_token");
            String rotated = textOrThrow(root, "refresh_token");
            long expiresIn = root.path("expires_in").asLong(0);
            if (expiresIn <= 0) {
                throw new TokenRefreshException("HubSpot refresh missing expires_in");
            }
            // List scope back from the response is informational only; HubSpot
            // returns it whitespace-separated.
            List<String> scopes = root.has("scope") && !root.get("scope").isNull()
                    ? List.of(root.get("scope").asText().split("\\s+"))
                    : List.of();
            return new RefreshedToken(access, rotated,
                    Instant.now().plus(Duration.ofSeconds(expiresIn)),
                    scopes);
        } catch (SaasHttpException e) {
            throw new TokenRefreshException("HubSpot refresh wire failure: " + e.getMessage(), e);
        }
    }

    private static String textOrThrow(JsonNode root, String field) {
        JsonNode v = root.get(field);
        if (v == null || v.isNull() || v.asText().isBlank()) {
            throw new TokenRefreshException("HubSpot refresh missing " + field);
        }
        return v.asText();
    }

    private static String enc(String s) {
        if (s == null) return "";
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
