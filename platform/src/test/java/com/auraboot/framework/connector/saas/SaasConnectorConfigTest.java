package com.auraboot.framework.connector.saas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SaasConnectorConfigTest {

    @Test
    void buildsOauth2Config() {
        SaasConnectorConfig cfg = new SaasConnectorConfig(
                "saas-salesforce", "oauth2", "client-id", "client-secret",
                "refresh-token", List.of("api", "refresh_token"),
                "https://acme.my.salesforce.com", 1000, Map.of("instanceUrl", "x"));
        assertThat(cfg.vendor()).isEqualTo("saas-salesforce");
        assertThat(cfg.scopes()).containsExactly("api", "refresh_token");
        assertThat(cfg.extras()).containsEntry("instanceUrl", "x");
    }

    @Test
    void buildsApiKeyConfig() {
        SaasConnectorConfig cfg = new SaasConnectorConfig(
                "saas-stripe", "apikey", "rk_live_x", "sk_live_y", null,
                null, "https://api.stripe.com", null, null);
        assertThat(cfg.scopes()).isEmpty();
        assertThat(cfg.extras()).isEmpty();
        assertThat(cfg.rateLimitPerMinute()).isNull();
    }

    @Test
    void rejectsInvalidAuthType() {
        assertThatThrownBy(() -> new SaasConnectorConfig(
                "x", "saml", null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("authType");
    }

    @Test
    void readCursorEmpty() {
        ReadCursor c = ReadCursor.empty();
        assertThat(c.since()).isNull();
        assertThat(c.pageToken()).isNull();
        assertThat(c.customState()).isEmpty();
    }

    @Test
    void readCursorWithState() {
        Instant t = Instant.parse("2026-05-28T00:00:00Z");
        ReadCursor c = new ReadCursor(t, "page-2", Map.of("dingNextCursor", "100"));
        assertThat(c.since()).isEqualTo(t);
        assertThat(c.pageToken()).isEqualTo("page-2");
        assertThat(c.customState()).containsEntry("dingNextCursor", "100");
    }
}
