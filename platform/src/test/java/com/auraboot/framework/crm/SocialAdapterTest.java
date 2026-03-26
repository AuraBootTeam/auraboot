package com.auraboot.framework.crm;

import com.auraboot.framework.crm.adapter.ChannelContext;
import com.auraboot.framework.crm.adapter.FacebookLeadAdsAdapter;
import com.auraboot.framework.crm.adapter.InboundMessage;
import com.auraboot.framework.crm.adapter.WeChatWorkAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for WeChatWorkAdapter and FacebookLeadAdsAdapter.
 * Plain JUnit — no Spring context, no DB, no Redis.
 */
class SocialAdapterTest {

    private WeChatWorkAdapter wechatAdapter;
    private FacebookLeadAdsAdapter fbAdapter;

    @BeforeEach
    void setUp() {
        wechatAdapter = new WeChatWorkAdapter();
        fbAdapter = new FacebookLeadAdsAdapter();
    }

    // =========================================================================
    // WeChat Work Adapter Tests
    // =========================================================================

    /**
     * wechat01 — valid SHA1 signature matches → verify returns true
     */
    @Test
    void wechat01_verifyValidSignature() throws Exception {
        String token = "my-wechat-token";
        String timestamp = "1711900000";
        String nonce = "abc123";
        String signature = computeWechatSignature(token, timestamp, nonce);

        ChannelContext ctx = ChannelContext.builder()
                .requestBody("{}")
                .headers(Map.of())
                .channelConfig(Map.of("token", token))
                .channelPid("wechat-ch-001")
                .fieldMapping(Map.of())
                .queryParams(Map.of(
                        "msg_signature", signature,
                        "timestamp", timestamp,
                        "nonce", nonce
                ))
                .build();

        assertThat(wechatAdapter.verify(ctx)).isTrue();
    }

    /**
     * wechat02 — wrong signature → verify returns false
     */
    @Test
    void wechat02_verifyInvalidSignature() {
        ChannelContext ctx = ChannelContext.builder()
                .requestBody("{}")
                .headers(Map.of())
                .channelConfig(Map.of("token", "my-wechat-token"))
                .channelPid("wechat-ch-002")
                .fieldMapping(Map.of())
                .queryParams(Map.of(
                        "msg_signature", "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef",
                        "timestamp", "1711900000",
                        "nonce", "abc123"
                ))
                .build();

        assertThat(wechatAdapter.verify(ctx)).isFalse();
    }

    /**
     * wechat03 — JSON body with contact event → correct InboundMessage
     */
    @Test
    void wechat03_parseContactEvent() {
        String body = """
                {
                  "externalContactName": "张三",
                  "corpName": "Acme Corp",
                  "mobile": "13800138000",
                  "remark": "需要定制化方案",
                  "externalUserId": "wm-ext-user-001"
                }
                """;

        ChannelContext ctx = ChannelContext.builder()
                .requestBody(body)
                .headers(Map.of())
                .channelConfig(Map.of("token", "tok"))
                .channelPid("wechat-ch-003")
                .fieldMapping(Map.of())
                .queryParams(Map.of())
                .build();

        InboundMessage msg = wechatAdapter.parse(ctx);

        assertThat(msg.channelPid()).isEqualTo("wechat-ch-003");
        assertThat(msg.channelType()).isEqualTo("wechat_work");
        assertThat(msg.externalId()).isEqualTo("wm-ext-user-001");
        assertThat(msg.receivedAt()).isNotNull();

        // Raw payload preserves original keys
        assertThat(msg.rawPayload()).containsKey("externalContactName");
        assertThat(msg.rawPayload().get("corpName")).isEqualTo("Acme Corp");

        // Normalized data uses CRM field names
        assertThat(msg.normalizedData().get("crm_lead_contact_name")).isEqualTo("张三");
        assertThat(msg.normalizedData().get("crm_lead_company")).isEqualTo("Acme Corp");
        assertThat(msg.normalizedData().get("crm_lead_contact_phone")).isEqualTo("13800138000");
        assertThat(msg.normalizedData().get("crm_lead_requirement")).isEqualTo("需要定制化方案");
        assertThat(msg.normalizedData().get("crm_lead_source")).isEqualTo("wechat_work");

        // externalUserId is NOT remapped into normalizedData as a CRM field
        assertThat(msg.normalizedData()).doesNotContainKey("externalContactName");
    }

    /**
     * wechat04 — missing token in config → verify returns false
     */
    @Test
    void wechat04_missingToken_returnsFalse() {
        ChannelContext ctx = ChannelContext.builder()
                .requestBody("{}")
                .headers(Map.of())
                .channelConfig(Map.of()) // no token
                .channelPid("wechat-ch-004")
                .fieldMapping(Map.of())
                .queryParams(Map.of(
                        "msg_signature", "anysig",
                        "timestamp", "12345",
                        "nonce", "nonce"
                ))
                .build();

        assertThat(wechatAdapter.verify(ctx)).isFalse();
    }

    /**
     * wechat05 — empty body → normalizedData contains only source field
     */
    @Test
    void wechat05_parseEmptyBody() {
        ChannelContext ctx = ChannelContext.builder()
                .requestBody("")
                .headers(Map.of())
                .channelConfig(Map.of("token", "tok"))
                .channelPid("wechat-ch-005")
                .fieldMapping(Map.of())
                .queryParams(Map.of())
                .build();

        InboundMessage msg = wechatAdapter.parse(ctx);
        assertThat(msg.rawPayload()).isEmpty();
        assertThat(msg.normalizedData()).isEmpty();
        assertThat(msg.externalId()).isNull();
    }

    // =========================================================================
    // Facebook Lead Ads Adapter Tests
    // =========================================================================

    /**
     * fb01 — valid X-Hub-Signature-256 header → verify returns true
     */
    @Test
    void fb01_verifyWebhookSignature() throws Exception {
        String appSecret = "fb-app-secret-xyz";
        String body = """
                {"entry":[{"changes":[{"value":{"leadgen_id":"12345"}}]}]}
                """.strip();
        String hmac = computeHmacSha256(appSecret, body);
        String sigHeader = "sha256=" + hmac;

        ChannelContext ctx = ChannelContext.builder()
                .requestBody(body)
                .headers(Map.of("x-hub-signature-256", sigHeader))
                .channelConfig(Map.of("appSecret", appSecret))
                .channelPid("fb-ch-001")
                .fieldMapping(Map.of())
                .queryParams(Map.of())
                .build();

        assertThat(fbAdapter.verify(ctx)).isTrue();
    }

    /**
     * fb02 — wrong signature → verify returns false
     */
    @Test
    void fb02_verifyInvalidSignature() {
        ChannelContext ctx = ChannelContext.builder()
                .requestBody("{\"entry\":[]}")
                .headers(Map.of("x-hub-signature-256", "sha256=badhashbadhash"))
                .channelConfig(Map.of("appSecret", "fb-app-secret-xyz"))
                .channelPid("fb-ch-002")
                .fieldMapping(Map.of())
                .queryParams(Map.of())
                .build();

        assertThat(fbAdapter.verify(ctx)).isFalse();
    }

    /**
     * fb03 — webhook event with lead_data → correct InboundMessage
     */
    @Test
    void fb03_parseLeadgenEvent() {
        String body = """
                {
                  "entry": [{
                    "changes": [{
                      "value": {
                        "leadgen_id": "lead-789",
                        "page_id": "page-456",
                        "form_id": "form-123",
                        "lead_data": {
                          "full_name": "John Doe",
                          "email": "john@example.com",
                          "phone_number": "+1234567890",
                          "company_name": "Acme Corp"
                        }
                      }
                    }]
                  }]
                }
                """;

        ChannelContext ctx = ChannelContext.builder()
                .requestBody(body)
                .headers(Map.of())
                .channelConfig(Map.of("appSecret", "secret"))
                .channelPid("fb-ch-003")
                .fieldMapping(Map.of())
                .queryParams(Map.of())
                .build();

        InboundMessage msg = fbAdapter.parse(ctx);

        assertThat(msg.channelPid()).isEqualTo("fb-ch-003");
        assertThat(msg.channelType()).isEqualTo("facebook_lead_ads");
        assertThat(msg.externalId()).isEqualTo("lead-789");
        assertThat(msg.receivedAt()).isNotNull();

        // Raw payload preserved
        assertThat(msg.rawPayload()).containsKey("entry");

        // Normalized data
        assertThat(msg.normalizedData().get("crm_lead_contact_name")).isEqualTo("John Doe");
        assertThat(msg.normalizedData().get("crm_lead_email")).isEqualTo("john@example.com");
        assertThat(msg.normalizedData().get("crm_lead_contact_phone")).isEqualTo("+1234567890");
        assertThat(msg.normalizedData().get("crm_lead_company")).isEqualTo("Acme Corp");
        assertThat(msg.normalizedData().get("crm_lead_source")).isEqualTo("facebook_lead_ads");
        assertThat(msg.normalizedData().get("crm_lead_external_id")).isEqualTo("lead-789");
    }

    /**
     * fb04 — webhook event without lead_data → InboundMessage with externalId only
     */
    @Test
    void fb04_parseLeadgenIdOnly() {
        String body = """
                {
                  "entry": [{
                    "changes": [{
                      "value": {
                        "leadgen_id": "lead-only-999",
                        "page_id": "page-456",
                        "form_id": "form-123"
                      }
                    }]
                  }]
                }
                """;

        ChannelContext ctx = ChannelContext.builder()
                .requestBody(body)
                .headers(Map.of())
                .channelConfig(Map.of("appSecret", "secret"))
                .channelPid("fb-ch-004")
                .fieldMapping(Map.of())
                .queryParams(Map.of())
                .build();

        InboundMessage msg = fbAdapter.parse(ctx);

        assertThat(msg.externalId()).isEqualTo("lead-only-999");
        assertThat(msg.channelType()).isEqualTo("facebook_lead_ads");

        // Without lead_data, normalized data only has source and external id
        assertThat(msg.normalizedData().get("crm_lead_source")).isEqualTo("facebook_lead_ads");
        assertThat(msg.normalizedData().get("crm_lead_external_id")).isEqualTo("lead-only-999");
        assertThat(msg.normalizedData()).doesNotContainKey("crm_lead_contact_name");
        assertThat(msg.normalizedData()).doesNotContainKey("crm_lead_email");
    }

    /**
     * fb05 — missing appSecret in config → verify returns false
     */
    @Test
    void fb05_missingAppSecret_returnsFalse() {
        ChannelContext ctx = ChannelContext.builder()
                .requestBody("{}")
                .headers(Map.of("x-hub-signature-256", "sha256=abc"))
                .channelConfig(Map.of()) // no appSecret
                .channelPid("fb-ch-005")
                .fieldMapping(Map.of())
                .queryParams(Map.of())
                .build();

        assertThat(fbAdapter.verify(ctx)).isFalse();
    }

    /**
     * fb06 — header without sha256= prefix → verify returns false
     */
    @Test
    void fb06_missingSignaturePrefix_returnsFalse() {
        ChannelContext ctx = ChannelContext.builder()
                .requestBody("{}")
                .headers(Map.of("x-hub-signature-256", "abcdefabcdef"))
                .channelConfig(Map.of("appSecret", "secret"))
                .channelPid("fb-ch-006")
                .fieldMapping(Map.of())
                .queryParams(Map.of())
                .build();

        assertThat(fbAdapter.verify(ctx)).isFalse();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static String computeWechatSignature(String token, String timestamp, String nonce)
            throws Exception {
        String[] parts = {token, timestamp, nonce};
        Arrays.sort(parts);
        String combined = parts[0] + parts[1] + parts[2];
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] digest = sha1.digest(combined.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    private static String computeHmacSha256(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] bytes = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(bytes);
    }
}
