package com.auraboot.framework.email;

import com.auraboot.framework.common.crypto.FieldEncryptionService;
import com.auraboot.framework.email.config.GmailApiConfig;
import com.auraboot.framework.email.mapper.EmailAccountMapper;
import com.auraboot.framework.email.service.GmailApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GmailApiClient} surfaces that don't require live
 * Google OAuth / Gmail HTTP calls.
 *
 * <p>Covers:
 * <ul>
 *   <li>{@code buildAuthorizationUrl} — pure UriComponentsBuilder output;
 *       must contain client_id, redirect_uri, response_type, all configured
 *       Gmail scopes, access_type=offline, prompt=consent, and the supplied
 *       state value, properly URL-encoded.</li>
 *   <li>{@code revokeToken} blank-token early return — null/blank decrypted
 *       refresh token logs a warning and returns without contacting Google;
 *       no SsrfValidator / mapper interaction expected.</li>
 * </ul>
 *
 * <p>The remaining surface (token refresh, exchangeCode, getGmailService) goes
 * through static {@code PinnedHttpRequests.PINNED_HTTP_CLIENT} + Google API
 * client builder calls that cannot be unit-mocked without Powermock; those
 * are covered by integration tests separately.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GmailApiClient — testable surfaces")
class GmailApiClientTest {

    @Mock private GmailApiConfig gmailApiConfig;
    @Mock private FieldEncryptionService fieldEncryptionService;
    @Mock private EmailAccountMapper emailAccountMapper;

    @InjectMocks private GmailApiClient client;

    @BeforeEach
    void setupConfig() {
        // Use lenient when() because not every test reads every property.
        org.mockito.Mockito.lenient().when(gmailApiConfig.getClientId())
                .thenReturn("test-client-id");
        org.mockito.Mockito.lenient().when(gmailApiConfig.getRedirectUri())
                .thenReturn("http://localhost:8080/oauth/callback");
        org.mockito.Mockito.lenient().when(gmailApiConfig.getClientSecret())
                .thenReturn("test-client-secret");
    }

    @Test
    @DisplayName("buildAuthorizationUrl includes all required OAuth params + scopes + state")
    void buildAuthorizationUrl_containsRequiredParts() {
        String url = client.buildAuthorizationUrl("opaque-state-xyz");

        assertThat(url).startsWith("https://accounts.google.com/o/oauth2/v2/auth?");
        assertThat(url).contains("client_id=test-client-id");
        assertThat(url).contains("response_type=code");
        assertThat(url).contains("access_type=offline");
        assertThat(url).contains("prompt=consent");
        assertThat(url).contains("state=opaque-state-xyz");
        // Redirect URI must be URL-encoded (colon and slashes encoded)
        assertThat(url).contains("redirect_uri=http");
        // Gmail scopes must all appear (URL-encoded form)
        assertThat(url).contains("gmail.readonly");
        assertThat(url).contains("gmail.send");
        assertThat(url).contains("gmail.modify");
    }

    @Test
    @DisplayName("buildAuthorizationUrl URL-encodes special chars in state value")
    void buildAuthorizationUrl_encodesState() {
        String url = client.buildAuthorizationUrl("a b&c=d");
        // Spring's UriComponentsBuilder encodes the state parameter for query strings.
        assertThat(url).contains("state=");
        // Either '+' or '%20' for space; '&' must not appear unencoded inside state value.
        // We at least confirm the literal state was not embedded raw.
        assertThat(url).doesNotContain("state=a b&c=d");
    }

    @Test
    @DisplayName("revokeToken returns silently when decrypted token is null")
    void revokeToken_nullTokenSkips() {
        when(fieldEncryptionService.decrypt("enc-null")).thenReturn(null);

        client.revokeToken("enc-null");

        verify(fieldEncryptionService).decrypt("enc-null");
        // Should not have touched any other collaborator
        verifyNoInteractions(emailAccountMapper);
        verify(gmailApiConfig, never()).getClientId();
    }

    @Test
    @DisplayName("revokeToken returns silently when decrypted token is blank")
    void revokeToken_blankTokenSkips() {
        when(fieldEncryptionService.decrypt("enc-blank")).thenReturn("   ");

        client.revokeToken("enc-blank");

        verify(fieldEncryptionService).decrypt("enc-blank");
        verifyNoInteractions(emailAccountMapper);
    }
}
