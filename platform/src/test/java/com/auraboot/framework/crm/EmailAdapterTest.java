package com.auraboot.framework.crm;

import com.auraboot.framework.crm.adapter.ChannelContext;
import com.auraboot.framework.crm.adapter.EmailImapAdapter;
import com.auraboot.framework.crm.adapter.EmailWebhookAdapter;
import com.auraboot.framework.crm.adapter.InboundMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for EmailImapAdapter and EmailWebhookAdapter.
 * Plain JUnit — no Spring context, no DB, no Redis.
 */
class EmailAdapterTest {

    private EmailImapAdapter imapAdapter;
    private EmailWebhookAdapter webhookAdapter;

    @BeforeEach
    void setUp() {
        imapAdapter = new EmailImapAdapter();
        webhookAdapter = new EmailWebhookAdapter();
    }

    // =========================================================================
    // imap01 — IMAP adapter extracts sender info from ChannelContext config
    // =========================================================================
    @Test
    void imap01_parseExtractsSenderInfo() {
        Map<String, Object> mimeData = Map.of(
                EmailImapAdapter.KEY_FROM, "alice@example.com",
                EmailImapAdapter.KEY_FROM_NAME, "Alice Smith",
                EmailImapAdapter.KEY_SUBJECT, "Inquiry about your product",
                EmailImapAdapter.KEY_BODY, "Hello, I am interested in purchasing your software. " +
                        "Please send me more details about pricing and features.",
                EmailImapAdapter.KEY_MESSAGE_ID, "<abc123@mail.example.com>"
        );

        ChannelContext ctx = ChannelContext.builder()
                .channelPid("ch-imap-001")
                .channelConfig(mimeData)
                .fieldMapping(Map.of())
                .headers(Map.of())
                .requestBody("")
                .queryParams(Map.of())
                .build();

        InboundMessage msg = imapAdapter.parse(ctx);

        // Envelope fields
        assertThat(msg.channelPid()).isEqualTo("ch-imap-001");
        assertThat(msg.channelType()).isEqualTo("email_imap");
        assertThat(msg.externalId()).isEqualTo("<abc123@mail.example.com>");
        assertThat(msg.receivedAt()).isNotNull();

        // Raw payload preserves MIME field names
        assertThat(msg.rawPayload()).containsKey(EmailImapAdapter.KEY_FROM);
        assertThat(msg.rawPayload().get(EmailImapAdapter.KEY_FROM)).isEqualTo("alice@example.com");
        assertThat(msg.rawPayload().get(EmailImapAdapter.KEY_FROM_NAME)).isEqualTo("Alice Smith");

        // Normalized data maps to CRM lead fields
        assertThat(msg.normalizedData()).containsKey("crm_lead_contact_email");
        assertThat(msg.normalizedData()).containsKey("crm_lead_contact_name");
        assertThat(msg.normalizedData()).containsKey("crm_lead_company");
        assertThat(msg.normalizedData()).containsKey("crm_lead_requirement");
        assertThat(msg.normalizedData()).containsKey("crm_lead_source");

        assertThat(msg.normalizedData().get("crm_lead_contact_email")).isEqualTo("alice@example.com");
        assertThat(msg.normalizedData().get("crm_lead_contact_name")).isEqualTo("Alice Smith");
        assertThat(msg.normalizedData().get("crm_lead_company")).isEqualTo("Inquiry about your product");
        assertThat(msg.normalizedData().get("crm_lead_source")).isEqualTo("email_inbound");

        // Requirement field should contain the body snippet
        String requirement = (String) msg.normalizedData().get("crm_lead_requirement");
        assertThat(requirement).contains("pricing and features");
    }

    // =========================================================================
    // imap01b — Body longer than 500 chars is truncated
    // =========================================================================
    @Test
    void imap01b_bodyTruncatedAt500Chars() {
        String longBody = "X".repeat(600);

        Map<String, Object> mimeData = Map.of(
                EmailImapAdapter.KEY_FROM, "user@test.com",
                EmailImapAdapter.KEY_FROM_NAME, "User",
                EmailImapAdapter.KEY_SUBJECT, "Long email",
                EmailImapAdapter.KEY_BODY, longBody
        );

        ChannelContext ctx = ChannelContext.builder()
                .channelPid("ch-imap-002")
                .channelConfig(mimeData)
                .fieldMapping(Map.of())
                .headers(Map.of())
                .requestBody("")
                .queryParams(Map.of())
                .build();

        InboundMessage msg = imapAdapter.parse(ctx);
        String requirement = (String) msg.normalizedData().get("crm_lead_requirement");
        assertThat(requirement).hasSize(500);
    }

    // =========================================================================
    // imap01c — IMAP verify always returns true
    // =========================================================================
    @Test
    void imap01c_verifyAlwaysTrue() {
        ChannelContext ctx = ChannelContext.builder()
                .channelPid("ch-imap-003")
                .channelConfig(Map.of())
                .fieldMapping(Map.of())
                .headers(Map.of())
                .requestBody("")
                .queryParams(Map.of())
                .build();

        assertThat(imapAdapter.verify(ctx)).isTrue();
    }

    // =========================================================================
    // imap02 — EmailWebhookAdapter parses SendGrid Inbound Parse JSON
    // =========================================================================
    @Test
    void imap02_emailWebhookParseSendgrid() {
        String sendgridJson = """
                {
                  "from": "bob@acme.com",
                  "fromName": "Bob Jones",
                  "to": "inbound@company.com",
                  "subject": "Partnership opportunity",
                  "text": "Hi, I would like to discuss a potential partnership.",
                  "html": "<p>Hi, I would like to discuss a potential partnership.</p>",
                  "Message-Id": "<msg-xyz@sendgrid.net>"
                }
                """;

        ChannelContext ctx = ChannelContext.builder()
                .channelPid("ch-webhook-001")
                .channelConfig(Map.of())
                .fieldMapping(Map.of())
                .headers(Map.of())
                .requestBody(sendgridJson)
                .queryParams(Map.of())
                .build();

        InboundMessage msg = webhookAdapter.parse(ctx);

        assertThat(msg.channelPid()).isEqualTo("ch-webhook-001");
        assertThat(msg.channelType()).isEqualTo("email_inbound_webhook");
        assertThat(msg.externalId()).isEqualTo("<msg-xyz@sendgrid.net>");

        // Raw payload should have original SendGrid keys
        assertThat(msg.rawPayload()).containsKey("from");
        assertThat(msg.rawPayload().get("from")).isEqualTo("bob@acme.com");

        // Normalized data maps to CRM lead fields
        assertThat(msg.normalizedData().get("crm_lead_contact_email")).isEqualTo("bob@acme.com");
        assertThat(msg.normalizedData().get("crm_lead_contact_name")).isEqualTo("Bob Jones");
        assertThat(msg.normalizedData().get("crm_lead_company")).isEqualTo("Partnership opportunity");
        assertThat(msg.normalizedData().get("crm_lead_source")).isEqualTo("email_inbound");

        String requirement = (String) msg.normalizedData().get("crm_lead_requirement");
        assertThat(requirement).contains("potential partnership");
    }

    // =========================================================================
    // imap02b — EmailWebhookAdapter falls back to HTML body when text is absent
    // =========================================================================
    @Test
    void imap02b_emailWebhookFallsBackToHtmlBody() {
        String jsonWithHtmlOnly = """
                {
                  "from": "carol@test.com",
                  "subject": "HTML only email",
                  "html": "<p>This is an <b>HTML</b> body &amp; nothing else.</p>"
                }
                """;

        ChannelContext ctx = ChannelContext.builder()
                .channelPid("ch-webhook-002")
                .channelConfig(Map.of())
                .fieldMapping(Map.of())
                .headers(Map.of())
                .requestBody(jsonWithHtmlOnly)
                .queryParams(Map.of())
                .build();

        InboundMessage msg = webhookAdapter.parse(ctx);
        String requirement = (String) msg.normalizedData().get("crm_lead_requirement");
        // HTML tags should be stripped
        assertThat(requirement).doesNotContain("<p>").doesNotContain("<b>");
        assertThat(requirement).containsIgnoringCase("html");
        assertThat(requirement).contains("&"); // &amp; decoded
    }

    // =========================================================================
    // imap02c — Empty / invalid JSON body does not throw
    // =========================================================================
    @Test
    void imap02c_emailWebhookHandlesEmptyBody() {
        ChannelContext ctx = ChannelContext.builder()
                .channelPid("ch-webhook-003")
                .channelConfig(Map.of())
                .fieldMapping(Map.of())
                .headers(Map.of())
                .requestBody("")
                .queryParams(Map.of())
                .build();

        InboundMessage msg = webhookAdapter.parse(ctx);
        assertThat(msg).isNotNull();
        assertThat(msg.rawPayload()).isEmpty();
        assertThat(msg.normalizedData().get("crm_lead_source")).isEqualTo("email_inbound");
    }

    // =========================================================================
    // imap03 — EmailWebhookAdapter verifies HMAC-SHA256 signature
    // =========================================================================
    @Test
    void imap03_emailWebhookVerifySignature() throws Exception {
        String secret = "webhook-secret-key";
        String body = "{\"from\":\"dave@example.com\",\"subject\":\"Test\"}";
        String validSignature = computeHmac(secret, body);

        ChannelContext ctx = ChannelContext.builder()
                .channelPid("ch-webhook-004")
                .channelConfig(Map.of("hmacSecret", secret))
                .fieldMapping(Map.of())
                .headers(Map.of("x-signature", validSignature))
                .requestBody(body)
                .queryParams(Map.of())
                .build();

        assertThat(webhookAdapter.verify(ctx)).isTrue();
    }

    // =========================================================================
    // imap03b — Invalid HMAC signature → verify returns false
    // =========================================================================
    @Test
    void imap03b_emailWebhookRejectsInvalidSignature() {
        String secret = "webhook-secret-key";
        String body = "{\"from\":\"dave@example.com\"}";

        ChannelContext ctx = ChannelContext.builder()
                .channelPid("ch-webhook-005")
                .channelConfig(Map.of("hmacSecret", secret))
                .fieldMapping(Map.of())
                .headers(Map.of("x-signature", "deadbeefdeadbeef"))
                .requestBody(body)
                .queryParams(Map.of())
                .build();

        assertThat(webhookAdapter.verify(ctx)).isFalse();
    }

    // =========================================================================
    // imap03c — No secret configured → accept all (open channel)
    // =========================================================================
    @Test
    void imap03c_emailWebhookAcceptsWithNoSecret() {
        ChannelContext ctx = ChannelContext.builder()
                .channelPid("ch-webhook-006")
                .channelConfig(Map.of()) // no hmacSecret
                .fieldMapping(Map.of())
                .headers(Map.of())
                .requestBody("{\"from\":\"eve@test.com\"}")
                .queryParams(Map.of())
                .build();

        assertThat(webhookAdapter.verify(ctx)).isTrue();
    }

    // =========================================================================
    // imap03d — Missing signature header with secret configured → reject
    // =========================================================================
    @Test
    void imap03d_emailWebhookRejectsMissingSignatureHeader() {
        ChannelContext ctx = ChannelContext.builder()
                .channelPid("ch-webhook-007")
                .channelConfig(Map.of("hmacSecret", "some-secret"))
                .fieldMapping(Map.of())
                .headers(Map.of()) // no x-signature header
                .requestBody("{\"from\":\"frank@test.com\"}")
                .queryParams(Map.of())
                .build();

        assertThat(webhookAdapter.verify(ctx)).isFalse();
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
}
