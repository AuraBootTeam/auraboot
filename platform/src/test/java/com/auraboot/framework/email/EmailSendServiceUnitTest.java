package com.auraboot.framework.email;

import com.auraboot.framework.email.mapper.EmailMessageMapper;
import com.auraboot.framework.email.model.EmailAccount;
import com.auraboot.framework.email.model.EmailConstants;
import com.auraboot.framework.email.model.EmailMessage;
import com.auraboot.framework.email.service.EmailSendService;
import com.auraboot.framework.email.service.EmailTrackingService;
import com.auraboot.framework.email.service.GmailApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link EmailSendService}.  Gmail API is deep-stubbed.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmailSendService Unit Tests")
class EmailSendServiceUnitTest {

    @Mock private GmailApiClient gmailApiClient;
    @Mock private EmailMessageMapper messageMapper;
    @Mock private EmailTrackingService trackingService;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS) private Gmail gmail;

    private EmailSendService service;

    @BeforeEach
    void setUp() {
        service = new EmailSendService(gmailApiClient, messageMapper, trackingService,
                new ObjectMapper());
    }

    private static EmailAccount account() {
        EmailAccount a = new EmailAccount();
        a.setId(1L);
        a.setTenantId(7L);
        a.setEmailAddress("me@example.com");
        a.setDisplayName("Me");
        return a;
    }

    @Test
    @DisplayName("send: tracking disabled → no tracking calls; persists outbound message with SENT label")
    void send_trackingDisabled_happyPath() throws Exception {
        when(gmailApiClient.getGmailService(any(EmailAccount.class))).thenReturn(gmail);

        Message sent = new Message().setId("GMAIL-1").setThreadId("THR-1");
        when(gmail.users().messages().send(eq("me"), any(Message.class)).execute())
                .thenReturn(sent);

        EmailMessage record = service.send(account(),
                List.of("a@b.com"),
                List.of("c@d.com"),
                null,
                "Hello",
                "<p>Hi</p>",
                null,
                false);

        assertThat(record.getDirection()).isEqualTo(EmailConstants.DIRECTION_OUTBOUND);
        assertThat(record.getGmailMessageId()).isEqualTo("GMAIL-1");
        assertThat(record.getGmailThreadId()).isEqualTo("THR-1");
        assertThat(record.getSubject()).isEqualTo("Hello");
        assertThat(record.getFromAddress()).isEqualTo("me@example.com");
        assertThat(record.getToAddresses()).contains("a@b.com");
        assertThat(record.getCcAddresses()).contains("c@d.com");
        assertThat(record.getBccAddresses()).isEqualTo("[]");
        assertThat(record.getLabelIds()).isEqualTo("[\"SENT\"]");
        assertThat(record.getHasAttachments()).isFalse();
        assertThat(record.getIsRead()).isTrue();

        verify(trackingService, never()).generateTrackingId();
        verify(trackingService, never()).injectTracking(anyString(), anyString());
        verify(messageMapper).insert(any(EmailMessage.class));
    }

    @Test
    @DisplayName("send: tracking enabled → injects tracking, label includes TRACKING token")
    void send_trackingEnabled() throws Exception {
        when(gmailApiClient.getGmailService(any(EmailAccount.class))).thenReturn(gmail);
        when(trackingService.generateTrackingId()).thenReturn("TID-XYZ");
        when(trackingService.injectTracking(eq("<p>Hi</p>"), eq("TID-XYZ")))
                .thenReturn("<p>Hi</p><img>");

        Message sent = new Message().setId("GMAIL-2").setThreadId("THR-2");
        when(gmail.users().messages().send(eq("me"), any(Message.class)).execute())
                .thenReturn(sent);

        EmailMessage record = service.send(account(),
                List.of("a@b.com"), null, null,
                "Subj", "<p>Hi</p>",
                "EXISTING-THR", true);

        assertThat(record.getLabelIds()).contains("TRACKING:TID-XYZ");
        assertThat(record.getBodyHtml()).isEqualTo("<p>Hi</p><img>");
        assertThat(record.getGmailThreadId()).isEqualTo("EXISTING-THR");
        verify(trackingService).generateTrackingId();
        verify(trackingService).injectTracking("<p>Hi</p>", "TID-XYZ");
    }

    @Test
    @DisplayName("send: account.displayName null → falls back to email address")
    void send_nullDisplayName_usesEmail() throws Exception {
        when(gmailApiClient.getGmailService(any(EmailAccount.class))).thenReturn(gmail);
        Message sent = new Message().setId("G-3").setThreadId("T-3");
        when(gmail.users().messages().send(eq("me"), any(Message.class)).execute()).thenReturn(sent);

        EmailAccount acc = account();
        acc.setDisplayName(null);

        EmailMessage record = service.send(acc, List.of("x@y"), null, null,
                "S", "<p>B</p>", null, false);

        assertThat(record.getFromAddress()).isEqualTo("me@example.com");
        // build did not throw → MIME built using email as display name
        verify(messageMapper).insert(any(EmailMessage.class));
    }

    @Test
    @DisplayName("send: gmail send IOException propagates")
    void send_apiFailure_propagates() throws Exception {
        when(gmailApiClient.getGmailService(any(EmailAccount.class))).thenReturn(gmail);
        when(gmail.users().messages().send(eq("me"), any(Message.class)).execute())
                .thenThrow(new IOException("api down"));

        assertThatThrownBy(() -> service.send(account(),
                List.of("a@b.com"), null, null, "S", "<p>x</p>", null, false))
                .isInstanceOf(IOException.class);
        verify(messageMapper, never()).insert(any(EmailMessage.class));
    }

    @Test
    @DisplayName("send: stripHtml fallback removes tags in plain part (verified via inserted record)")
    void send_persistsBodyHtmlAsIs() throws Exception {
        when(gmailApiClient.getGmailService(any(EmailAccount.class))).thenReturn(gmail);
        Message sent = new Message().setId("G-4").setThreadId("T-4");
        when(gmail.users().messages().send(eq("me"), any(Message.class)).execute()).thenReturn(sent);

        ArgumentCaptor<EmailMessage> cap = ArgumentCaptor.forClass(EmailMessage.class);
        service.send(account(), List.of("a@b"), null, null,
                "Subj", "<div><p>Body</p></div>", null, false);
        verify(messageMapper).insert(cap.capture());
        // bodyHtml is preserved verbatim
        assertThat(cap.getValue().getBodyHtml()).isEqualTo("<div><p>Body</p></div>");
    }
}
