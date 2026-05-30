package com.auraboot.framework.connector.airflow.secret;

import com.auraboot.framework.common.crypto.FieldEncryptionService;
import com.auraboot.framework.connector.airflow.secret.mapper.ConnectorWebhookSecretMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookSecretServiceTest {

    private ConnectorWebhookSecretMapper mapper;
    private FieldEncryptionService encryption;
    private Clock clock;
    private WebhookSecretService service;

    private final Instant NOW = Instant.parse("2026-05-30T10:00:00Z");

    @BeforeEach
    void setup() {
        mapper = mock(ConnectorWebhookSecretMapper.class);
        encryption = mock(FieldEncryptionService.class);
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        when(encryption.encrypt(anyString())).thenAnswer(inv -> "ENC:" + inv.getArgument(0));
        when(encryption.decrypt(anyString())).thenAnswer(inv -> {
            String s = inv.getArgument(0);
            return s != null && s.startsWith("ENC:") ? s.substring(4) : s;
        });
        service = new WebhookSecretService(mapper, encryption, clock);
    }

    private static ConnectorWebhookSecret row(Long tenantId, String conn, String encSecret,
                                              boolean active, Instant rotatedAt) {
        ConnectorWebhookSecret r = new ConnectorWebhookSecret();
        r.setId(1L);
        r.setPid("PID");
        r.setTenantId(tenantId);
        r.setConnectionName(conn);
        r.setSharedSecret(encSecret);
        r.setAlgorithm("HMAC-SHA256");
        r.setActive(active);
        r.setRotatedAt(rotatedAt);
        return r;
    }

    // -- candidateSecrets -----------------------------------------------

    @Test
    void candidateSecretsReturnsEmptyForBlankConnection() {
        assertThat(service.candidateSecrets(null).hasMatch()).isFalse();
        assertThat(service.candidateSecrets("").hasMatch()).isFalse();
        assertThat(service.candidateSecrets("  ").hasMatch()).isFalse();
    }

    @Test
    void candidateSecretsReturnsEmptyWhenNoRow() {
        when(mapper.findActiveOrGracePeriod(anyString(), any())).thenReturn(List.of());
        WebhookSecretService.Resolution res = service.candidateSecrets("airflow-prod");
        assertThat(res.hasMatch()).isFalse();
        assertThat(res.tenantId()).isNull();
        assertThat(res.secrets()).isEmpty();
    }

    @Test
    void candidateSecretsReturnsActiveSecretDecrypted() {
        when(mapper.findActiveOrGracePeriod(anyString(), any()))
                .thenReturn(List.of(row(7L, "airflow-prod", "ENC:s1", true, null)));
        WebhookSecretService.Resolution res = service.candidateSecrets("airflow-prod");
        assertThat(res.tenantId()).isEqualTo(7L);
        assertThat(res.secrets()).containsExactly("s1");
    }

    @Test
    void candidateSecretsReturnsActiveBeforeGracePeriodSecret() {
        // Mapper SQL orders ACTIVE first; we keep that order in candidate list.
        when(mapper.findActiveOrGracePeriod(anyString(), any()))
                .thenReturn(List.of(
                        row(7L, "airflow-prod", "ENC:new", true, null),
                        row(7L, "airflow-prod", "ENC:old", false, NOW.minusSeconds(60))));
        WebhookSecretService.Resolution res = service.candidateSecrets("airflow-prod");
        assertThat(res.tenantId()).isEqualTo(7L);
        assertThat(res.secrets()).containsExactly("new", "old");
    }

    @Test
    void candidateSecretsQueriesWithGraceCutoff() {
        when(mapper.findActiveOrGracePeriod(anyString(), any())).thenReturn(List.of());
        service.candidateSecrets("c");
        ArgumentCaptor<Instant> cap = ArgumentCaptor.forClass(Instant.class);
        verify(mapper).findActiveOrGracePeriod(eq("c"), cap.capture());
        assertThat(cap.getValue()).isEqualTo(NOW.minusSeconds(300));
    }

    @Test
    void candidateSecretsSkipsDecryptFailureButReturnsOthers() {
        when(encryption.decrypt("ENC:bad")).thenThrow(new RuntimeException("corrupt"));
        when(mapper.findActiveOrGracePeriod(anyString(), any()))
                .thenReturn(List.of(
                        row(7L, "c", "ENC:bad", true, null),
                        row(7L, "c", "ENC:ok", false, NOW.minusSeconds(60))));
        WebhookSecretService.Resolution res = service.candidateSecrets("c");
        assertThat(res.secrets()).containsExactly("ok");
        assertThat(res.tenantId()).isEqualTo(7L);
    }

    // -- upsertActiveSecret --------------------------------------------

    @Test
    void upsertInsertsWhenNoExisting() {
        when(mapper.findActive("c")).thenReturn(null);
        ConnectorWebhookSecret saved = service.upsertActiveSecret(7L, "c", "plain");
        assertThat(saved.getSharedSecret()).isEqualTo("ENC:plain");
        assertThat(saved.getActive()).isTrue();
        assertThat(saved.getAlgorithm()).isEqualTo("HMAC-SHA256");
        assertThat(saved.getTenantId()).isEqualTo(7L);
        assertThat(saved.getPid()).hasSize(26);
        verify(mapper).insert(any(ConnectorWebhookSecret.class));
        verify(mapper, never()).deactivate(any());
    }

    @Test
    void upsertDemotesExistingActiveBeforeInsertingNew() {
        ConnectorWebhookSecret existing = row(7L, "c", "ENC:old", true, null);
        when(mapper.findActive("c")).thenReturn(existing);
        service.upsertActiveSecret(7L, "c", "new-plain");
        verify(mapper).deactivate(existing.getId());
        ArgumentCaptor<ConnectorWebhookSecret> cap = ArgumentCaptor.forClass(ConnectorWebhookSecret.class);
        verify(mapper).insert(cap.capture());
        assertThat(cap.getValue().getSharedSecret()).isEqualTo("ENC:new-plain");
        assertThat(cap.getValue().getActive()).isTrue();
    }

    @Test
    void upsertRejectsCrossTenantTakeover() {
        ConnectorWebhookSecret existing = row(7L, "c", "ENC:s", true, null);
        when(mapper.findActive("c")).thenReturn(existing);
        assertThatThrownBy(() -> service.upsertActiveSecret(99L, "c", "new"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already owned by another tenant");
        verify(mapper, never()).deactivate(any());
        verify(mapper, never()).insert(any(ConnectorWebhookSecret.class));
    }

    @Test
    void upsertRejectsBlankArgs() {
        assertThatThrownBy(() -> service.upsertActiveSecret(7L, "", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.upsertActiveSecret(7L, "c", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.upsertActiveSecret(null, "c", "x"))
                .isInstanceOf(NullPointerException.class);
    }

    // -- revoke / findActive --------------------------------------------

    @Test
    void revokeDelegatesToMapper() {
        when(mapper.deleteByConnection("c")).thenReturn(1);
        assertThat(service.revoke("c")).isTrue();
        when(mapper.deleteByConnection("c")).thenReturn(0);
        assertThat(service.revoke("c")).isFalse();
    }

    @Test
    void findActiveWrapsOptional() {
        when(mapper.findActive("c")).thenReturn(null);
        assertThat(service.findActive("c")).isEmpty();
        ConnectorWebhookSecret r = row(7L, "c", "ENC:s", true, null);
        when(mapper.findActive("c")).thenReturn(r);
        assertThat(service.findActive("c")).contains(r);
    }

    private static String eq(String s) {
        return org.mockito.ArgumentMatchers.eq(s);
    }
}
