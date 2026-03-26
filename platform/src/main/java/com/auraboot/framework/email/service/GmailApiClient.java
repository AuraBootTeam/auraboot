package com.auraboot.framework.email.service;

import com.auraboot.framework.common.crypto.FieldEncryptionService;
import com.auraboot.framework.email.config.GmailApiConfig;
import com.auraboot.framework.email.mapper.EmailAccountMapper;
import com.auraboot.framework.email.model.EmailAccount;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeTokenRequest;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;
import com.google.api.services.gmail.model.Profile;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.UserCredentials;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Low-level Gmail API wrapper.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Build authenticated {@link Gmail} service instances with auto token-refresh.</li>
 *   <li>Exchange authorization codes for OAuth2 tokens.</li>
 *   <li>Build Google authorization URLs.</li>
 *   <li>Revoke tokens at Google.</li>
 * </ul>
 *
 * <p>Tokens stored in {@link EmailAccount} are always encrypted via
 * {@link FieldEncryptionService}. This client decrypts before use and
 * encrypts when persisting refreshed tokens.
 *
 * @since 6.5.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GmailApiClient {

    private static final String APPLICATION_NAME = "AuraBoot Email CRM";
    private static final String GOOGLE_AUTH_URL  = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String GOOGLE_TOKEN_URL  = "https://oauth2.googleapis.com/token";
    private static final String GOOGLE_REVOKE_URL = "https://oauth2.googleapis.com/revoke";

    /** Minimum remaining token lifetime before a proactive refresh is triggered. */
    private static final long REFRESH_BUFFER_SECONDS = 300; // 5 minutes

    private static final List<String> GMAIL_SCOPES = List.of(
            GmailScopes.GMAIL_READONLY,
            GmailScopes.GMAIL_SEND,
            GmailScopes.GMAIL_MODIFY
    );

    private final GmailApiConfig gmailApiConfig;
    private final FieldEncryptionService fieldEncryptionService;
    private final EmailAccountMapper emailAccountMapper;
    private final RestTemplate restTemplate = new RestTemplate();

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Builds an authenticated {@link Gmail} service client for the given account.
     *
     * <p>If the access token is within {@value #REFRESH_BUFFER_SECONDS} seconds of expiry,
     * a refresh is performed automatically and the new token is persisted.
     *
     * @param account the connected Gmail account (must have encrypted tokens)
     * @return authenticated Gmail service
     * @throws IOException if token refresh or service construction fails
     */
    public Gmail getGmailService(EmailAccount account) throws IOException {
        String accessToken  = fieldEncryptionService.decrypt(account.getAccessToken());
        String refreshToken = fieldEncryptionService.decrypt(account.getRefreshToken());

        // Proactively refresh if close to expiry
        if (isTokenExpiringSoon(account.getTokenExpiresAt())) {
            log.info("Access token expiring soon for account {}, refreshing…", account.getId());
            accessToken = refreshAccessToken(account, refreshToken);
        }

        Date expiryDate = account.getTokenExpiresAt() != null
                ? Date.from(account.getTokenExpiresAt())
                : null;

        AccessToken googleAccessToken = new AccessToken(accessToken, expiryDate);

        GoogleCredentials credentials = UserCredentials.newBuilder()
                .setClientId(gmailApiConfig.getClientId())
                .setClientSecret(gmailApiConfig.getClientSecret())
                .setRefreshToken(refreshToken)
                .setAccessToken(googleAccessToken)
                .build();

        return new Gmail.Builder(
                new NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(credentials))
                .setApplicationName(APPLICATION_NAME)
                .build();
    }

    /**
     * Exchanges a Google authorization code for OAuth2 tokens and fetches the Gmail
     * profile email address.
     *
     * @param code the authorization code received from the OAuth2 callback
     * @return map containing {@code access_token}, {@code refresh_token}, {@code expires_in},
     *         and {@code email}
     * @throws IOException if the token exchange or profile fetch fails
     */
    public Map<String, String> exchangeCode(String code) throws IOException {
        GoogleTokenResponse tokenResponse = new GoogleAuthorizationCodeTokenRequest(
                new NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                gmailApiConfig.getClientId(),
                gmailApiConfig.getClientSecret(),
                code,
                gmailApiConfig.getRedirectUri())
                .execute();

        String accessToken  = tokenResponse.getAccessToken();
        String refreshToken = tokenResponse.getRefreshToken();
        Long   expiresIn    = tokenResponse.getExpiresInSeconds();

        // Fetch authenticated user's Gmail address
        AccessToken googleAccessToken = new AccessToken(accessToken, null);
        GoogleCredentials credentials = UserCredentials.newBuilder()
                .setClientId(gmailApiConfig.getClientId())
                .setClientSecret(gmailApiConfig.getClientSecret())
                .setRefreshToken(refreshToken != null ? refreshToken : "")
                .setAccessToken(googleAccessToken)
                .build();

        Gmail gmail = new Gmail.Builder(
                new NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(credentials))
                .setApplicationName(APPLICATION_NAME)
                .build();

        Profile profile = gmail.users().getProfile("me").execute();

        Map<String, String> result = new HashMap<>();
        result.put("access_token",  accessToken);
        result.put("refresh_token", refreshToken != null ? refreshToken : "");
        result.put("expires_in",    expiresIn != null ? String.valueOf(expiresIn) : "3600");
        result.put("email",         profile.getEmailAddress());
        return result;
    }

    /**
     * Builds the Google OAuth2 authorization URL.
     *
     * @param state opaque state value that Google will echo back on callback
     * @return full authorization URL including all required scopes
     */
    public String buildAuthorizationUrl(String state) {
        return UriComponentsBuilder.fromHttpUrl(GOOGLE_AUTH_URL)
                .queryParam("client_id",     gmailApiConfig.getClientId())
                .queryParam("redirect_uri",  gmailApiConfig.getRedirectUri())
                .queryParam("response_type", "code")
                .queryParam("scope",         String.join(" ", GMAIL_SCOPES))
                .queryParam("access_type",   "offline")
                .queryParam("prompt",        "consent")
                .queryParam("state",         state)
                .build()
                .toUriString();
    }

    /**
     * Revokes the given refresh token at Google.
     *
     * @param encryptedRefreshToken the encrypted refresh token stored in the DB
     */
    public void revokeToken(String encryptedRefreshToken) {
        String refreshToken = fieldEncryptionService.decrypt(encryptedRefreshToken);
        if (refreshToken == null || refreshToken.isBlank()) {
            log.warn("revokeToken called with blank token, skipping");
            return;
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("token", refreshToken);

            restTemplate.postForObject(
                    GOOGLE_REVOKE_URL,
                    new HttpEntity<>(body, headers),
                    Void.class);

            log.info("Token revoked at Google successfully");
        } catch (Exception e) {
            // Token revocation is best-effort; do not block disconnect flow
            log.warn("Failed to revoke token at Google (best-effort): {}", e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────────────────

    private boolean isTokenExpiringSoon(Instant tokenExpiresAt) {
        if (tokenExpiresAt == null) {
            return true; // unknown expiry → refresh to be safe
        }
        return Instant.now().plusSeconds(REFRESH_BUFFER_SECONDS).isAfter(tokenExpiresAt);
    }

    /**
     * Calls Google's token endpoint to get a new access token using the refresh token.
     * Persists the new access token back to the database.
     */
    @SuppressWarnings("unchecked")
    private String refreshAccessToken(EmailAccount account, String plainRefreshToken) throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("client_id",     gmailApiConfig.getClientId());
        body.add("client_secret", gmailApiConfig.getClientSecret());
        body.add("refresh_token", plainRefreshToken);
        body.add("grant_type",    "refresh_token");

        Map<String, Object> response = restTemplate.postForObject(
                GOOGLE_TOKEN_URL,
                new HttpEntity<>(body, headers),
                Map.class);

        if (response == null) {
            throw new IOException("Empty response from Google token refresh endpoint");
        }

        String newAccessToken = String.valueOf(response.getOrDefault("access_token", ""));
        long   expiresIn      = Long.parseLong(String.valueOf(response.getOrDefault("expires_in", "3600")));
        Instant newExpiry     = Instant.now().plusSeconds(expiresIn);

        // Persist refreshed token
        String encryptedNewToken = fieldEncryptionService.encrypt(newAccessToken);
        emailAccountMapper.updateToken(account.getId(), encryptedNewToken, newExpiry);

        // Update in-memory so caller sees fresh data
        account.setAccessToken(encryptedNewToken);
        account.setTokenExpiresAt(newExpiry);

        log.info("Access token refreshed for account {}, expires at {}", account.getId(), newExpiry);
        return newAccessToken;
    }
}
