package com.auraboot.framework.connector.saas.hubspot;

import com.auraboot.framework.connector.saas.SaasConnectorConfig;
import com.auraboot.framework.connector.saas.http.SaasHttpClient;
import com.auraboot.framework.connector.saas.http.SaasHttpException;
import com.auraboot.framework.connector.saas.http.SaasHttpRequest;
import com.auraboot.framework.connector.saas.oauth.TokenRefreshException;
import com.auraboot.framework.connector.saas.oauth.TokenRefresher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HubspotTokenRefresherTest {

    private SaasHttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private HubspotTokenRefresher refresher;

    @BeforeEach
    void setup() {
        http = mock(SaasHttpClient.class);
        refresher = new HubspotTokenRefresher(http, json);
    }

    private SaasConnectorConfig config() {
        return new SaasConnectorConfig("saas-hubspot", "oauth2",
                "client/id+space", "secret&plus",
                null, List.of("crm.objects.contacts.read"),
                "https://api.hubapi.com", null, Map.of());
    }

    private static com.fasterxml.jackson.databind.JsonNode jsonOf(String s) {
        try {
            return new ObjectMapper().readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void refreshHappyPathReturnsRotatedToken() {
        when(http.executeForJson(any(), any(), any())).thenReturn(jsonOf("""
            {"access_token":"new-access",
             "refresh_token":"rotated-refresh",
             "expires_in":1800,
             "scope":"crm.objects.contacts.read crm.objects.companies.read",
             "token_type":"bearer"}
            """));
        Instant before = Instant.now();

        TokenRefresher.RefreshedToken token = refresher.refresh(config(), "old-rt");

        assertThat(token.accessToken()).isEqualTo("new-access");
        assertThat(token.refreshToken()).isEqualTo("rotated-refresh");
        assertThat(token.scopes()).containsExactly(
                "crm.objects.contacts.read", "crm.objects.companies.read");
        assertThat(token.expiresAt())
                .isAfterOrEqualTo(before.plus(Duration.ofSeconds(1799)))
                .isBeforeOrEqualTo(Instant.now().plus(Duration.ofSeconds(1801)));
    }

    @Test
    void refreshSendsFormEncodedBodyToTokenEndpoint() {
        when(http.executeForJson(any(), any(), any())).thenReturn(jsonOf("""
            {"access_token":"a","refresh_token":"r","expires_in":3600}
            """));
        refresher.refresh(config(), "rt-value");

        ArgumentCaptor<SaasHttpRequest> cap = ArgumentCaptor.forClass(SaasHttpRequest.class);
        verify(http).executeForJson(cap.capture(), any(), any());
        SaasHttpRequest req = cap.getValue();
        assertThat(req.method()).isEqualTo("POST");
        assertThat(req.url()).isEqualTo(HubspotTokenRefresher.TOKEN_URL);
        assertThat(req.vendor()).isEqualTo("saas-hubspot");
        assertThat(req.headers()).containsEntry("Content-Type",
                "application/x-www-form-urlencoded");
        String body = (String) req.body();
        assertThat(body)
                .contains("grant_type=refresh_token")
                // URL-encoded special characters (?/+)
                .contains("client_id=client%2Fid%2Bspace")
                .contains("client_secret=secret%26plus")
                .contains("refresh_token=rt-value");
    }

    @Test
    void refreshMissingRefreshTokenThrows() {
        assertThatThrownBy(() -> refresher.refresh(config(), null))
                .isInstanceOf(TokenRefreshException.class)
                .hasMessageContaining("non-blank");
        assertThatThrownBy(() -> refresher.refresh(config(), "  "))
                .isInstanceOf(TokenRefreshException.class);
    }

    @Test
    void refreshMissingFieldsThrows() {
        when(http.executeForJson(any(), any(), any())).thenReturn(jsonOf("""
            {"access_token":"a","expires_in":3600}
            """));
        assertThatThrownBy(() -> refresher.refresh(config(), "rt"))
                .isInstanceOf(TokenRefreshException.class)
                .hasMessageContaining("refresh_token");
    }

    @Test
    void refreshMissingExpiresInThrows() {
        when(http.executeForJson(any(), any(), any())).thenReturn(jsonOf("""
            {"access_token":"a","refresh_token":"r"}
            """));
        assertThatThrownBy(() -> refresher.refresh(config(), "rt"))
                .isInstanceOf(TokenRefreshException.class)
                .hasMessageContaining("expires_in");
    }

    @Test
    void wireFailureWraps() {
        when(http.executeForJson(any(), any(), any()))
                .thenThrow(new SaasHttpException("401 unauthorized", 401));
        assertThatThrownBy(() -> refresher.refresh(config(), "rt"))
                .isInstanceOf(TokenRefreshException.class)
                .hasMessageContaining("wire failure")
                .hasMessageContaining("401");
    }

    @Test
    void emptyScopeReturnsEmptyList() {
        when(http.executeForJson(any(), any(), any())).thenReturn(jsonOf("""
            {"access_token":"a","refresh_token":"r","expires_in":3600}
            """));
        TokenRefresher.RefreshedToken token = refresher.refresh(config(), "rt");
        assertThat(token.scopes()).isEmpty();
    }
}
