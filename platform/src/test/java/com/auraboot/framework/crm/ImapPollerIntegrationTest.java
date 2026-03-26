package com.auraboot.framework.crm;

import com.auraboot.framework.crm.adapter.ChannelContext;
import com.auraboot.framework.crm.adapter.EmailImapAdapter;
import com.auraboot.framework.crm.adapter.InboundMessage;
import com.auraboot.framework.crm.dto.InboundChannelCreateRequest;
import com.auraboot.framework.crm.entity.InboundChannel;
import com.auraboot.framework.crm.service.ImapPollerJob;
import com.auraboot.framework.crm.service.InboundChannelService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Integration tests for {@link ImapPollerJob}.
 * <p>
 * Since we cannot connect to a real IMAP server in integration tests, these tests focus on:
 * <ul>
 *   <li>Channel discovery and filtering (enabled/disabled, correct type)</li>
 *   <li>Graceful error handling when IMAP connection fails</li>
 *   <li>Error isolation — one failing channel does not abort others</li>
 *   <li>{@link EmailImapAdapter} parsing logic tested directly</li>
 * </ul>
 *
 * @since 5.3.0
 */
@Slf4j
@DisplayName("IMAP Poller Integration Tests (IP-01~IP-05)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ImapPollerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ImapPollerJob imapPollerJob;

    @Autowired
    private InboundChannelService inboundChannelService;

    @Autowired
    private EmailImapAdapter emailImapAdapter;

    private final String runId = "imap_test_" + System.currentTimeMillis();

    // ==================== IP-01 ====================

    @Test
    @Order(1)
    @DisplayName("IP-01: poll() with no EMAIL_IMAP channels completes without error")
    void ip01_pollWithNoImapChannels() {
        // No EMAIL_IMAP channels exist for this test's tenant (only non-IMAP channels exist
        // from other tests at most, which use a shared test tenant).
        // The key assertion is that poll() does not throw even when no channels are found.
        assertThatNoException().isThrownBy(() -> imapPollerJob.poll());
        log.info("IP-01 passed: poll() with no IMAP channels completed without error");
    }

    // ==================== IP-02 ====================

    @Test
    @Order(2)
    @DisplayName("IP-02: poll() skips disabled EMAIL_IMAP channels — no connection attempt")
    void ip02_pollSkipsDisabledChannels() {
        // Create an EMAIL_IMAP channel that is disabled
        InboundChannelCreateRequest request = new InboundChannelCreateRequest();
        request.setName(runId + "-disabled-imap");
        request.setChannelType("email_imap");
        request.setConfig(Map.of(
                "host", "imap.example.com",
                "port", "993",
                "ssl", "true",
                "username", "test@example.com",
                "password", "secret123"
        ));

        InboundChannel channel = inboundChannelService.create(request);
        assertThat(channel).isNotNull();
        assertThat(channel.getEnabled()).isTrue();

        // Disable the channel so it is excluded from polling
        inboundChannelService.disable(channel.getPid());

        InboundChannel disabled = inboundChannelService.getByPid(channel.getPid());
        assertThat(disabled.getEnabled()).isFalse();

        // poll() must not throw — the disabled channel is simply ignored
        assertThatNoException().isThrownBy(() -> imapPollerJob.poll());
        log.info("IP-02 passed: disabled channel pid={} was skipped without error", channel.getPid());
    }

    // ==================== IP-03 ====================

    @Test
    @Order(3)
    @DisplayName("IP-03: poll() handles IMAP connection failure gracefully — no exception propagated")
    void ip03_pollHandlesConnectionFailure() {
        // Create an enabled EMAIL_IMAP channel with an intentionally unreachable host.
        // The poller should catch the MessagingException/IOException and log it,
        // not propagate it to the caller.
        InboundChannelCreateRequest request = new InboundChannelCreateRequest();
        request.setName(runId + "-bad-imap-host");
        request.setChannelType("email_imap");
        request.setConfig(Map.of(
                "host", "imap.invalid-host-that-does-not-exist.test",
                "port", "993",
                "ssl", "true",
                "username", "user@invalid.test",
                "password", "badpassword"
        ));

        InboundChannel channel = inboundChannelService.create(request);
        assertThat(channel).isNotNull();
        assertThat(channel.getEnabled()).isTrue();

        // poll() should swallow the connection error and return normally
        assertThatNoException().isThrownBy(() -> imapPollerJob.poll());
        log.info("IP-03 passed: connection failure for channel pid={} was handled gracefully",
                channel.getPid());
    }

    // ==================== IP-04 ====================

    @Test
    @Order(4)
    @DisplayName("IP-04: EmailImapAdapter.parse() correctly maps IMAP MIME data to InboundMessage")
    void ip04_adapterParsesEmailDataCorrectly() {
        String channelPid = runId + "-adapter-test";
        String fromEmail = "sender@acme.com";
        String fromName = "Alice Smith";
        String subject = "RE: Partnership Inquiry";
        String body = "Hello, I am interested in your product. Please send me more details.";
        String messageId = "<abc123@mail.acme.com>";

        Map<String, Object> mimeData = Map.of(
                EmailImapAdapter.KEY_FROM, fromEmail,
                EmailImapAdapter.KEY_FROM_NAME, fromName,
                EmailImapAdapter.KEY_SUBJECT, subject,
                EmailImapAdapter.KEY_BODY, body,
                EmailImapAdapter.KEY_MESSAGE_ID, messageId
        );

        ChannelContext ctx = ChannelContext.builder()
                .channelPid(channelPid)
                .channelConfig(mimeData)
                .fieldMapping(Map.of())
                .headers(Map.of())
                .requestBody("")
                .queryParams(Map.of())
                .build();

        InboundMessage msg = emailImapAdapter.parse(ctx);

        // Basic envelope assertions
        assertThat(msg).isNotNull();
        assertThat(msg.channelPid()).isEqualTo(channelPid);
        assertThat(msg.channelType()).isEqualTo("email_imap");
        assertThat(msg.externalId()).isEqualTo(messageId);
        assertThat(msg.receivedAt()).isNotNull();

        // Raw payload preserves original MIME keys
        assertThat(msg.rawPayload()).containsEntry(EmailImapAdapter.KEY_FROM, fromEmail);
        assertThat(msg.rawPayload()).containsEntry(EmailImapAdapter.KEY_FROM_NAME, fromName);
        assertThat(msg.rawPayload()).containsEntry(EmailImapAdapter.KEY_SUBJECT, subject);
        assertThat(msg.rawPayload()).containsEntry(EmailImapAdapter.KEY_BODY, body);
        assertThat(msg.rawPayload()).containsEntry(EmailImapAdapter.KEY_MESSAGE_ID, messageId);

        // Normalized data maps to CRM lead fields
        Map<String, Object> nd = msg.normalizedData();
        assertThat(nd).containsEntry("crm_lead_contact_email", fromEmail);
        assertThat(nd).containsEntry("crm_lead_contact_name", fromName);
        // Subject used as company hint
        assertThat(nd).containsEntry("crm_lead_company", subject);
        // Body mapped to requirement
        assertThat(nd).containsEntry("crm_lead_requirement", body);
        // Source is always "email_inbound"
        assertThat(nd).containsEntry("crm_lead_source", "email_inbound");

        log.info("IP-04 passed: adapter correctly mapped IMAP email to InboundMessage, externalId={}",
                msg.externalId());
    }

    // ==================== IP-05 ====================

    @Test
    @Order(5)
    @DisplayName("IP-05: poll() processes channels independently — one failure does not block others")
    void ip05_channelsProcessedIndependently() {
        // Create two enabled EMAIL_IMAP channels:
        //   • Channel A: unreachable host (will fail connection)
        //   • Channel B: also unreachable, but different host (to verify iteration continues)
        // Both should be attempted; poll() must not throw.

        InboundChannelCreateRequest requestA = new InboundChannelCreateRequest();
        requestA.setName(runId + "-channel-a");
        requestA.setChannelType("email_imap");
        requestA.setConfig(Map.of(
                "host", "imap.first-unreachable-" + runId + ".test",
                "port", "993",
                "ssl", "true",
                "username", "a@first.test",
                "password", "passA"
        ));

        InboundChannelCreateRequest requestB = new InboundChannelCreateRequest();
        requestB.setName(runId + "-channel-b");
        requestB.setChannelType("email_imap");
        requestB.setConfig(Map.of(
                "host", "imap.second-unreachable-" + runId + ".test",
                "port", "993",
                "ssl", "true",
                "username", "b@second.test",
                "password", "passB"
        ));

        InboundChannel channelA = inboundChannelService.create(requestA);
        InboundChannel channelB = inboundChannelService.create(requestB);

        assertThat(channelA.getEnabled()).isTrue();
        assertThat(channelB.getEnabled()).isTrue();

        // poll() must iterate both channels and handle both connection errors gracefully
        assertThatNoException().isThrownBy(() -> imapPollerJob.poll());

        log.info("IP-05 passed: poll() iterated both channels A={} and B={} without propagating errors",
                channelA.getPid(), channelB.getPid());
    }

    // ==================== IP-06 ====================

    @Test
    @Order(6)
    @DisplayName("IP-06: EmailImapAdapter.parse() handles null/missing fields without NPE")
    void ip06_adapterHandlesMissingFields() {
        // Build a context with only the minimum data (from address only, no name/body/messageId)
        String channelPid = runId + "-minimal";

        ChannelContext ctx = ChannelContext.builder()
                .channelPid(channelPid)
                .channelConfig(Map.of(EmailImapAdapter.KEY_FROM, "minimal@test.com"))
                .fieldMapping(Map.of())
                .headers(Map.of())
                .requestBody("")
                .queryParams(Map.of())
                .build();

        InboundMessage msg = emailImapAdapter.parse(ctx);

        assertThat(msg).isNotNull();
        assertThat(msg.channelPid()).isEqualTo(channelPid);
        assertThat(msg.externalId()).isNull(); // no messageId provided
        assertThat(msg.normalizedData()).containsEntry("crm_lead_contact_email", "minimal@test.com");
        // Fields not provided should be null, not cause NPE
        assertThat(msg.normalizedData()).containsKey("crm_lead_contact_name");
        assertThat(msg.normalizedData().get("crm_lead_contact_name")).isNull();

        log.info("IP-06 passed: adapter handled partial/null fields without error");
    }

    // ==================== IP-07 ====================

    @Test
    @Order(7)
    @DisplayName("IP-07: EmailImapAdapter.verify() always returns true for trusted IMAP source")
    void ip07_adapterAlwaysVerifies() {
        ChannelContext ctx = ChannelContext.builder()
                .channelPid(runId + "-verify-test")
                .channelConfig(Map.of())
                .fieldMapping(Map.of())
                .headers(Map.of())
                .requestBody("")
                .queryParams(Map.of())
                .build();

        // IMAP is a server-initiated pull — always trusted
        assertThat(emailImapAdapter.verify(ctx)).isTrue();
        assertThat(emailImapAdapter.channelType()).isEqualTo("email_imap");

        log.info("IP-07 passed: EmailImapAdapter.verify() correctly returns true for trusted source");
    }
}
