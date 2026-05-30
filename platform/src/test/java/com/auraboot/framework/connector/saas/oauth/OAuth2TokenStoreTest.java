package com.auraboot.framework.connector.saas.oauth;

import com.auraboot.framework.common.crypto.FieldEncryptionService;
import com.auraboot.framework.connector.saas.SaasConnectorConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OAuth2TokenStoreTest {

    private ConnectorOAuthTokenMapper mapper;
    private FieldEncryptionService encryption;
    private TokenRefresher hubspotRefresher;
    private Clock clock;
    private OAuth2TokenStore store;

    private final Instant NOW = Instant.parse("2026-05-30T00:00:00Z");

    @BeforeEach
    void setup() {
        mapper = mock(ConnectorOAuthTokenMapper.class);
        encryption = mock(FieldEncryptionService.class);
        hubspotRefresher = mock(TokenRefresher.class);
        clock = Clock.fixed(NOW, ZoneOffset.UTC);

        when(encryption.encrypt(anyString())).thenAnswer(inv -> "ENC:" + inv.getArgument(0));
        when(encryption.decrypt(anyString())).thenAnswer(inv -> {
            String s = inv.getArgument(0);
            return s != null && s.startsWith("ENC:") ? s.substring(4) : s;
        });
        when(hubspotRefresher.vendor()).thenReturn("saas-hubspot");

        store = new OAuth2TokenStore(mapper, encryption, List.of(hubspotRefresher), clock);
    }

    private SaasConnectorConfig hubspotConfig() {
        return new SaasConnectorConfig("saas-hubspot", "oauth2",
                "client-id", "client-secret", "rt", List.of("crm.read"),
                "https://api.hubapi.com", null, Map.of());
    }

    private ConnectorOAuthToken row(String access, String refresh, Instant expiresAt) {
        ConnectorOAuthToken r = new ConnectorOAuthToken();
        r.setPid("PID");
        r.setTenantId(1L);
        r.setVendor("saas-hubspot");
        r.setAccessToken(access);
        r.setRefreshToken(refresh);
        r.setExpiresAt(expiresAt);
        r.setScopes("crm.read");
        return r;
    }

    // -- read path ------------------------------------------------------

    @Test
    void getValidAccessTokenReturnsDecryptedWhenFresh() {
        when(mapper.findByTenantAndVendor(1L, "saas-hubspot"))
                .thenReturn(row("ENC:access-1", "ENC:refresh-1", NOW.plusSeconds(3600)));

        String t = store.getValidAccessToken(1L, hubspotConfig());

        assertThat(t).isEqualTo("access-1");
        verify(hubspotRefresher, never()).refresh(any(), anyString());
    }

    @Test
    void getValidAccessTokenRefreshesWhenWithinLeadTime() {
        // 30s left → within 60s lead → refresh
        when(mapper.findByTenantAndVendor(1L, "saas-hubspot"))
                .thenReturn(row("ENC:stale", "ENC:refresh-1", NOW.plusSeconds(30)));
        when(hubspotRefresher.refresh(any(), eq("refresh-1")))
                .thenReturn(new TokenRefresher.RefreshedToken(
                        "fresh-access", "rotated-refresh",
                        NOW.plusSeconds(3600), List.of("crm.read")));

        String t = store.getValidAccessToken(1L, hubspotConfig());

        assertThat(t).isEqualTo("fresh-access");
        ArgumentCaptor<String> accessCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> refreshCap = ArgumentCaptor.forClass(String.class);
        verify(mapper).updateTokens(eq(1L), eq("saas-hubspot"),
                accessCap.capture(), refreshCap.capture(),
                any(), anyString());
        assertThat(accessCap.getValue()).isEqualTo("ENC:fresh-access");
        assertThat(refreshCap.getValue()).isEqualTo("ENC:rotated-refresh");
    }

    @Test
    void getValidAccessTokenRefreshesWhenAlreadyExpired() {
        when(mapper.findByTenantAndVendor(1L, "saas-hubspot"))
                .thenReturn(row("ENC:stale", "ENC:refresh-1", NOW.minusSeconds(10)));
        when(hubspotRefresher.refresh(any(), anyString()))
                .thenReturn(new TokenRefresher.RefreshedToken(
                        "fresh", "rt", NOW.plusSeconds(3600), List.of()));

        String t = store.getValidAccessToken(1L, hubspotConfig());

        assertThat(t).isEqualTo("fresh");
    }

    @Test
    void getValidAccessTokenThrowsWhenNoRow() {
        when(mapper.findByTenantAndVendor(1L, "saas-hubspot")).thenReturn(null);

        assertThatThrownBy(() -> store.getValidAccessToken(1L, hubspotConfig()))
                .isInstanceOf(TokenRefreshException.class)
                .hasMessageContaining("No OAuth row");
    }

    @Test
    void getValidAccessTokenThrowsWhenNoRefresherRegistered() {
        OAuth2TokenStore noRefresher = new OAuth2TokenStore(
                mapper, encryption, List.of(), clock);
        when(mapper.findByTenantAndVendor(1L, "saas-hubspot"))
                .thenReturn(row("ENC:stale", "ENC:rt", NOW.minusSeconds(10)));

        assertThatThrownBy(() -> noRefresher.getValidAccessToken(1L, hubspotConfig()))
                .isInstanceOf(TokenRefreshException.class)
                .hasMessageContaining("No TokenRefresher registered");
    }

    @Test
    void getValidAccessTokenSurfacesRefresherFailure() {
        when(mapper.findByTenantAndVendor(1L, "saas-hubspot"))
                .thenReturn(row("ENC:stale", "ENC:rt", NOW.minusSeconds(10)));
        when(hubspotRefresher.refresh(any(), anyString()))
                .thenThrow(new TokenRefreshException("revoked"));

        assertThatThrownBy(() -> store.getValidAccessToken(1L, hubspotConfig()))
                .isInstanceOf(TokenRefreshException.class)
                .hasMessageContaining("revoked");
        verify(mapper, never()).updateTokens(anyLong(), anyString(),
                anyString(), anyString(), any(), anyString());
    }

    // -- write path -----------------------------------------------------

    @Test
    void persistInitialInsertsWhenNoRow() {
        when(mapper.findByTenantAndVendor(1L, "saas-hubspot")).thenReturn(null);
        var token = new TokenRefresher.RefreshedToken(
                "access", "refresh", NOW.plusSeconds(3600), List.of("crm.read"));

        ConnectorOAuthToken saved = store.persistInitial(1L, "saas-hubspot", token);

        assertThat(saved.getAccessToken()).isEqualTo("ENC:access");
        assertThat(saved.getRefreshToken()).isEqualTo("ENC:refresh");
        assertThat(saved.getScopes()).isEqualTo("crm.read");
        assertThat(saved.getPid()).hasSize(26);
        verify(mapper).insert(any(ConnectorOAuthToken.class));
        verify(mapper, never()).updateTokens(anyLong(), anyString(),
                anyString(), anyString(), any(), anyString());
    }

    @Test
    void persistInitialUpdatesWhenRowExists() {
        when(mapper.findByTenantAndVendor(1L, "saas-hubspot"))
                .thenReturn(row("ENC:old", "ENC:old-rt", NOW.minusSeconds(60)));
        var token = new TokenRefresher.RefreshedToken(
                "new-access", null, NOW.plusSeconds(3600), List.of());

        store.persistInitial(1L, "saas-hubspot", token);

        verify(mapper).updateTokens(eq(1L), eq("saas-hubspot"),
                eq("ENC:new-access"), eq(null), eq(NOW.plusSeconds(3600)), eq(""));
        verify(mapper, never()).insert(any(ConnectorOAuthToken.class));
    }

    @Test
    void revokeDelegatesToMapper() {
        when(mapper.deleteByTenantAndVendor(1L, "saas-hubspot")).thenReturn(1);
        assertThat(store.revoke(1L, "saas-hubspot")).isTrue();
        when(mapper.deleteByTenantAndVendor(1L, "saas-hubspot")).thenReturn(0);
        assertThat(store.revoke(1L, "saas-hubspot")).isFalse();
    }

    // -- concurrency ----------------------------------------------------

    @Test
    void concurrentRefreshFiresExactlyOnce() throws Exception {
        // 8 concurrent callers all see an expired row; only one refresher call should fire.
        ConnectorOAuthToken expired = row("ENC:stale", "ENC:rt", NOW.minusSeconds(10));
        ConnectorOAuthToken fresh = row("ENC:fresh", "ENC:rt-new", NOW.plusSeconds(3600));

        AtomicInteger reads = new AtomicInteger(0);
        when(mapper.findByTenantAndVendor(1L, "saas-hubspot")).thenAnswer(inv -> {
            // First few reads return expired; once the (one) refresher fires and updateTokens
            // is called, subsequent reads (under the lock) should see the fresh row.
            int n = reads.incrementAndGet();
            return n <= 1 ? expired : (refresherFired.get() ? fresh : expired);
        });

        when(hubspotRefresher.refresh(any(), anyString())).thenAnswer(inv -> {
            // Simulate non-zero wire latency so concurrent callers actually queue up.
            Thread.sleep(50);
            refresherFired.set(true);
            return new TokenRefresher.RefreshedToken(
                    "fresh", "rt-new", NOW.plusSeconds(3600), List.of());
        });

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(8);
        AtomicInteger ok = new AtomicInteger();
        for (int i = 0; i < 8; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    String t = store.getValidAccessToken(1L, hubspotConfig());
                    if ("fresh".equals(t)) ok.incrementAndGet();
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(ok.get()).isEqualTo(8);
        verify(hubspotRefresher, times(1)).refresh(any(), anyString());
        verify(mapper, atLeastOnce()).updateTokens(anyLong(), anyString(),
                anyString(), anyString(), any(), anyString());
    }

    private final java.util.concurrent.atomic.AtomicBoolean refresherFired =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    // -- find -----------------------------------------------------------

    @Test
    void findReturnsOptional() {
        when(mapper.findByTenantAndVendor(1L, "x")).thenReturn(null);
        assertThat(store.find(1L, "x")).isEmpty();
        ConnectorOAuthToken r = row("ENC:a", "ENC:r", NOW.plusSeconds(60));
        when(mapper.findByTenantAndVendor(1L, "saas-hubspot")).thenReturn(r);
        assertThat(store.find(1L, "saas-hubspot")).contains(r);
    }
}
