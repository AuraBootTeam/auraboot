package com.auraboot.framework.crm;

import com.auraboot.framework.crm.adapter.ChannelAdapter;
import com.auraboot.framework.crm.adapter.ChannelAdapterRegistry;
import com.auraboot.framework.crm.adapter.ChannelContext;
import com.auraboot.framework.crm.adapter.GenericWebhookAdapter;
import com.auraboot.framework.crm.adapter.InboundMessage;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the Channel Adapter Framework.
 * Plain JUnit — no Spring context, no DB, no Redis.
 */
class ChannelAdapterTest {

    private GenericWebhookAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new GenericWebhookAdapter();
    }

    // =========================================================================
    // ga01 — valid HMAC signature → verify returns true
    // =========================================================================
    @Test
    void ga01_verifyValidHmac() throws Exception {
        String secret = "my-super-secret";
        String body = "{\"name\":\"Alice\",\"email\":\"alice@example.com\"}";
        String signature = computeHmac(secret, body);

        ChannelContext ctx = ChannelContext.builder()
                .requestBody(body)
                .headers(Map.of("x-signature", signature))
                .channelConfig(Map.of("hmacSecret", secret))
                .channelPid("ch-001")
                .fieldMapping(Map.of())
                .queryParams(Map.of())
                .build();

        assertThat(adapter.verify(ctx)).isTrue();
    }

    // =========================================================================
    // ga02 — wrong HMAC signature → verify returns false
    // =========================================================================
    @Test
    void ga02_verifyInvalidHmac() {
        String secret = "my-super-secret";
        String body = "{\"name\":\"Alice\"}";

        ChannelContext ctx = ChannelContext.builder()
                .requestBody(body)
                .headers(Map.of("x-signature", "deadbeefdeadbeef"))
                .channelConfig(Map.of("hmacSecret", secret))
                .channelPid("ch-002")
                .fieldMapping(Map.of())
                .queryParams(Map.of())
                .build();

        assertThat(adapter.verify(ctx)).isFalse();
    }

    // =========================================================================
    // ga03 — valid API key → verify returns true
    // =========================================================================
    @Test
    void ga03_verifyApiKey() {
        String apiKey = "tok-abc-123";

        ChannelContext ctx = ChannelContext.builder()
                .requestBody("{}")
                .headers(Map.of("x-api-key", apiKey))
                .channelConfig(Map.of("apiKey", apiKey))
                .channelPid("ch-003")
                .fieldMapping(Map.of())
                .queryParams(Map.of())
                .build();

        assertThat(adapter.verify(ctx)).isTrue();
    }

    @Test
    void ga03b_verifyApiKeyWrong() {
        ChannelContext ctx = ChannelContext.builder()
                .requestBody("{}")
                .headers(Map.of("x-api-key", "wrong-key"))
                .channelConfig(Map.of("apiKey", "tok-abc-123"))
                .channelPid("ch-003b")
                .fieldMapping(Map.of())
                .queryParams(Map.of())
                .build();

        assertThat(adapter.verify(ctx)).isFalse();
    }

    // =========================================================================
    // ga04 — JSON body + field mapping → correct InboundMessage
    // =========================================================================
    @Test
    void ga04_parseJsonBody() {
        String body = "{\"full_name\":\"Bob Smith\",\"email_addr\":\"bob@example.com\",\"phone\":\"555-1234\"}";
        Map<String, String> fieldMapping = Map.of(
                "full_name", "crm_lead_name",
                "email_addr", "crm_lead_email"
                // "phone" is intentionally unmapped → passes through as-is
        );

        ChannelContext ctx = ChannelContext.builder()
                .requestBody(body)
                .headers(Map.of())
                .channelConfig(Map.of())
                .channelPid("ch-004")
                .fieldMapping(fieldMapping)
                .queryParams(Map.of())
                .build();

        InboundMessage msg = adapter.parse(ctx);

        // Basic envelope fields
        assertThat(msg.channelPid()).isEqualTo("ch-004");
        assertThat(msg.channelType()).isEqualTo("generic_webhook");
        assertThat(msg.receivedAt()).isNotNull();

        // Raw payload preserves original keys
        assertThat(msg.rawPayload()).containsKey("full_name");
        assertThat(msg.rawPayload()).containsKey("email_addr");
        assertThat(msg.rawPayload()).containsKey("phone");
        assertThat(msg.rawPayload().get("full_name")).isEqualTo("Bob Smith");

        // Normalized data applies field mapping
        assertThat(msg.normalizedData()).containsKey("crm_lead_name");
        assertThat(msg.normalizedData()).containsKey("crm_lead_email");
        assertThat(msg.normalizedData()).containsKey("phone"); // unmapped → passes through
        assertThat(msg.normalizedData().get("crm_lead_name")).isEqualTo("Bob Smith");
        assertThat(msg.normalizedData().get("crm_lead_email")).isEqualTo("bob@example.com");

        // Raw payload should NOT contain remapped keys
        assertThat(msg.rawPayload()).doesNotContainKey("crm_lead_name");
    }

    // =========================================================================
    // ga05 — registry finds correct adapter by type
    // =========================================================================
    @Test
    void ga05_registryFindsAdapter() {
        // Build registry manually without Spring context
        ChannelAdapterRegistry registry = new ChannelAdapterRegistry();
        injectAdapters(registry, List.of(adapter));
        registry.init();

        ChannelAdapter found = registry.getAdapter("generic_webhook");
        assertThat(found).isSameAs(adapter);
        assertThat(found.channelType()).isEqualTo("generic_webhook");
    }

    @Test
    void ga05b_registryThrowsForUnknownType() {
        ChannelAdapterRegistry registry = new ChannelAdapterRegistry();
        injectAdapters(registry, List.of(adapter));
        registry.init();

        assertThatThrownBy(() -> registry.getAdapter("unknown_type"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown_type");
    }

    // =========================================================================
    // Additional edge-case tests
    // =========================================================================

    @Test
    void ga06_parseEmptyBody() {
        ChannelContext ctx = ChannelContext.builder()
                .requestBody("")
                .headers(Map.of())
                .channelConfig(Map.of())
                .channelPid("ch-006")
                .fieldMapping(Map.of())
                .queryParams(Map.of())
                .build();

        InboundMessage msg = adapter.parse(ctx);
        assertThat(msg.rawPayload()).isEmpty();
        assertThat(msg.normalizedData()).isEmpty();
        assertThat(msg.externalId()).isNull();
    }

    @Test
    void ga07_noAuthConfigAcceptsAll() {
        ChannelContext ctx = ChannelContext.builder()
                .requestBody("{}")
                .headers(Map.of())
                .channelConfig(Map.of()) // no hmacSecret, no apiKey
                .channelPid("ch-007")
                .fieldMapping(Map.of())
                .queryParams(Map.of())
                .build();

        assertThat(adapter.verify(ctx)).isTrue();
    }

    @Test
    void ga08_hmacTakesPrecedenceOverApiKey() throws Exception {
        String secret = "correct-secret";
        String body = "{\"x\":1}";
        String validSig = computeHmac(secret, body);

        ChannelContext ctx = ChannelContext.builder()
                .requestBody(body)
                .headers(Map.of("x-signature", validSig, "x-api-key", "some-key"))
                .channelConfig(Map.of("hmacSecret", secret, "apiKey", "some-key"))
                .channelPid("ch-008")
                .fieldMapping(Map.of())
                .queryParams(Map.of())
                .build();

        // HMAC passes → true
        assertThat(adapter.verify(ctx)).isTrue();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static String computeHmac(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] bytes = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * Injects adapters into the registry via reflection (avoids requiring Spring context in tests).
     */
    private static void injectAdapters(ChannelAdapterRegistry registry, List<ChannelAdapter> adapters) {
        try {
            var field = ChannelAdapterRegistry.class.getDeclaredField("adapters");
            field.setAccessible(true);
            field.set(registry, adapters);
        } catch (Exception e) {
            Assertions.fail("Could not inject adapters into registry: " + e.getMessage());
        }
    }
}
