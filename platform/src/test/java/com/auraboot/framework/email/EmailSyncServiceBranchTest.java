package com.auraboot.framework.email;

import com.auraboot.framework.email.mapper.EmailAccountMapper;
import com.auraboot.framework.email.mapper.EmailMessageMapper;
import com.auraboot.framework.email.model.EmailAccount;
import com.auraboot.framework.email.model.EmailConstants;
import com.auraboot.framework.email.model.EmailMessage;
import com.auraboot.framework.email.service.EmailSyncService;
import com.auraboot.framework.email.service.GmailApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.History;
import com.google.api.services.gmail.model.HistoryMessageAdded;
import com.google.api.services.gmail.model.ListHistoryResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartBody;
import com.google.api.services.gmail.model.MessagePartHeader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Additional branch coverage for {@link EmailSyncService} —
 * exercises incremental History-API sync, multipart body recursion,
 * attachment collection, decodeBody catch path, and parseHeaders edge cases
 * (cc, bcc, no payload, no labels) not covered by {@code EmailSyncServiceUnitTest}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmailSyncService branch coverage")
class EmailSyncServiceBranchTest {

    @Mock private GmailApiClient gmailApiClient;
    @Mock private EmailAccountMapper accountMapper;
    @Mock private EmailMessageMapper messageMapper;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS) private Gmail gmail;

    private EmailSyncService service;

    @BeforeEach
    void setUp() {
        service = new EmailSyncService(gmailApiClient, accountMapper, messageMapper, new ObjectMapper());
    }

    private static EmailAccount account() {
        EmailAccount a = new EmailAccount();
        a.setId(11L);
        a.setTenantId(7L);
        a.setEmailAddress("me@example.com");
        a.setSyncMode(EmailConstants.SYNC_MODE_AUTO);
        return a;
    }

    @Test
    @DisplayName("syncAccount: incremental sync via History API picks up messageAdded entries")
    void syncAccount_incremental_historyApi() throws Exception {
        EmailAccount a = account();
        a.setSyncState("{\"historyId\":\"100\"}");
        when(gmailApiClient.getGmailService(a)).thenReturn(gmail);

        // History API returns one record with one messageAdded entry
        HistoryMessageAdded added = new HistoryMessageAdded()
                .setMessage(new Message().setId("inc1"));
        History h = new History()
                .setId(BigInteger.valueOf(150))
                .setMessagesAdded(List.of(added));

        ListHistoryResponse hist = new ListHistoryResponse()
                .setHistory(List.of(h))
                .setHistoryId(BigInteger.valueOf(200));
        when(gmail.users().history().list("me")
                .setStartHistoryId(any(BigInteger.class))
                .setHistoryTypes(any())
                .execute()).thenReturn(hist);

        when(messageMapper.existsByGmailMessageId(11L, "inc1")).thenReturn(false);

        // Full message fetch
        MessagePart payload = new MessagePart()
                .setMimeType("text/plain")
                .setHeaders(List.of(new MessagePartHeader().setName("From").setValue("a@b.com")))
                .setBody(new MessagePartBody().setData(
                        Base64.getUrlEncoder().encodeToString("hi".getBytes(StandardCharsets.UTF_8))));
        Message full = new Message().setId("inc1").setPayload(payload);
        when(gmail.users().messages().get("me", "inc1").setFormat("full").execute())
                .thenReturn(full);

        service.syncAccount(a);

        verify(messageMapper).insert(any(EmailMessage.class));
        verify(accountMapper).updateSyncState(eq(11L), anyString());
    }

    @Test
    @DisplayName("syncAccount: incremental sync with empty history list ends cleanly")
    void syncAccount_incremental_emptyHistory() throws Exception {
        EmailAccount a = account();
        a.setSyncState("{\"historyId\":\"100\"}");
        when(gmailApiClient.getGmailService(a)).thenReturn(gmail);

        ListHistoryResponse hist = new ListHistoryResponse(); // empty
        when(gmail.users().history().list("me")
                .setStartHistoryId(any(BigInteger.class))
                .setHistoryTypes(any())
                .execute()).thenReturn(hist);

        service.syncAccount(a);

        verify(messageMapper, never()).insert(any(EmailMessage.class));
    }

    @Test
    @DisplayName("syncAccount: skips message that already exists in DB")
    void syncAccount_skipsExisting() throws Exception {
        EmailAccount a = account();
        a.setSyncState("");
        when(gmailApiClient.getGmailService(a)).thenReturn(gmail);

        when(gmail.users().messages().list("me").setMaxResults(any(Long.class)).execute())
                .thenReturn(new com.google.api.services.gmail.model.ListMessagesResponse()
                        .setMessages(List.of(new Message().setId("dup"))));
        when(messageMapper.existsByGmailMessageId(11L, "dup")).thenReturn(true);

        service.syncAccount(a);
        verify(messageMapper, never()).insert(any(EmailMessage.class));
    }

    @Test
    @DisplayName("persistMessage: multipart with text/plain + text/html children populates both bodies")
    void multipartBothBodies() throws Exception {
        EmailAccount a = account();
        a.setSyncState("");
        when(gmailApiClient.getGmailService(a)).thenReturn(gmail);

        when(gmail.users().messages().list("me").setMaxResults(any(Long.class)).execute())
                .thenReturn(new com.google.api.services.gmail.model.ListMessagesResponse()
                        .setMessages(List.of(new Message().setId("mm"))));
        when(messageMapper.existsByGmailMessageId(11L, "mm")).thenReturn(false);

        MessagePart textPart = new MessagePart()
                .setMimeType("text/plain")
                .setBody(new MessagePartBody().setData(
                        Base64.getUrlEncoder().encodeToString("plain".getBytes(StandardCharsets.UTF_8))));
        MessagePart htmlPart = new MessagePart()
                .setMimeType("text/html")
                .setBody(new MessagePartBody().setData(
                        Base64.getUrlEncoder().encodeToString("<b>html</b>".getBytes(StandardCharsets.UTF_8))));
        MessagePart payload = new MessagePart()
                .setMimeType("multipart/alternative")
                .setHeaders(List.of(
                        new MessagePartHeader().setName("Cc").setValue("c1@x.com, \"Bob\" <b@x.com>"),
                        new MessagePartHeader().setName("Bcc").setValue("bcc@x.com"),
                        new MessagePartHeader().setName("X-Other").setValue("ignored")
                ))
                .setParts(List.of(textPart, htmlPart));

        when(gmail.users().messages().get("me", "mm").setFormat("full").execute())
                .thenReturn(new Message().setId("mm").setPayload(payload));

        service.syncAccount(a);

        ArgumentCaptor<EmailMessage> cap = ArgumentCaptor.forClass(EmailMessage.class);
        verify(messageMapper).insert(cap.capture());
        EmailMessage saved = cap.getValue();
        assertThat(saved.getBodyText()).isEqualTo("plain");
        assertThat(saved.getBodyHtml()).isEqualTo("<b>html</b>");
        assertThat(saved.getCcAddresses()).contains("c1@x.com").contains("b@x.com");
        assertThat(saved.getBccAddresses()).contains("bcc@x.com");
        // No labels: isRead defaults to true
        assertThat(saved.getIsRead()).isTrue();
    }

    @Test
    @DisplayName("persistMessage: attachments with attachmentId are collected; hasAttachments=true")
    void messageWithAttachment() throws Exception {
        EmailAccount a = account();
        a.setSyncState("");
        when(gmailApiClient.getGmailService(a)).thenReturn(gmail);

        when(gmail.users().messages().list("me").setMaxResults(any(Long.class)).execute())
                .thenReturn(new com.google.api.services.gmail.model.ListMessagesResponse()
                        .setMessages(List.of(new Message().setId("att"))));
        when(messageMapper.existsByGmailMessageId(11L, "att")).thenReturn(false);

        MessagePart attachment = new MessagePart()
                .setMimeType("application/pdf")
                .setFilename("doc.pdf")
                .setBody(new MessagePartBody().setAttachmentId("ATT_1").setSize(1024));
        // attachment without filename → ignored
        MessagePart noFile = new MessagePart()
                .setMimeType("application/octet-stream")
                .setBody(new MessagePartBody().setAttachmentId("ATT_2").setSize(10));
        MessagePart payload = new MessagePart()
                .setMimeType("multipart/mixed")
                .setHeaders(List.of(new MessagePartHeader().setName("From").setValue("a@b.com")))
                .setParts(List.of(attachment, noFile));

        when(gmail.users().messages().get("me", "att").setFormat("full").execute())
                .thenReturn(new Message().setId("att").setPayload(payload));

        service.syncAccount(a);

        ArgumentCaptor<EmailMessage> cap = ArgumentCaptor.forClass(EmailMessage.class);
        verify(messageMapper).insert(cap.capture());
        assertThat(cap.getValue().getHasAttachments()).isTrue();
        assertThat(cap.getValue().getAttachments()).contains("doc.pdf").contains("ATT_1");
    }

    @Test
    @DisplayName("persistMessage: invalid base64 body data triggers decodeBody catch path → null body")
    void decodeBodyCatchPath() throws Exception {
        EmailAccount a = account();
        a.setSyncState("");
        when(gmailApiClient.getGmailService(a)).thenReturn(gmail);

        when(gmail.users().messages().list("me").setMaxResults(any(Long.class)).execute())
                .thenReturn(new com.google.api.services.gmail.model.ListMessagesResponse()
                        .setMessages(List.of(new Message().setId("bad"))));
        when(messageMapper.existsByGmailMessageId(11L, "bad")).thenReturn(false);

        MessagePart payload = new MessagePart()
                .setMimeType("text/plain")
                // Invalid base64 (contains '!' which is not in URL-safe alphabet)
                .setBody(new MessagePartBody().setData("!!!!not-valid-base64@@"));

        when(gmail.users().messages().get("me", "bad").setFormat("full").execute())
                .thenReturn(new Message().setId("bad").setPayload(payload));

        service.syncAccount(a);

        ArgumentCaptor<EmailMessage> cap = ArgumentCaptor.forClass(EmailMessage.class);
        verify(messageMapper).insert(cap.capture());
        // decodeBody catch returned null → bodyText not set
        assertThat(cap.getValue().getBodyText()).isNull();
    }

    @Test
    @DisplayName("persistMessage: body part with no data yields null body without throwing")
    void bodyPartWithoutData() throws Exception {
        EmailAccount a = account();
        a.setSyncState("");
        when(gmailApiClient.getGmailService(a)).thenReturn(gmail);

        when(gmail.users().messages().list("me").setMaxResults(any(Long.class)).execute())
                .thenReturn(new com.google.api.services.gmail.model.ListMessagesResponse()
                        .setMessages(List.of(new Message().setId("nodata"))));
        when(messageMapper.existsByGmailMessageId(11L, "nodata")).thenReturn(false);

        MessagePart payload = new MessagePart()
                .setMimeType("text/plain")
                .setBody(new MessagePartBody()); // body present, data null

        when(gmail.users().messages().get("me", "nodata").setFormat("full").execute())
                .thenReturn(new Message().setId("nodata").setPayload(payload));

        service.syncAccount(a);
        ArgumentCaptor<EmailMessage> cap = ArgumentCaptor.forClass(EmailMessage.class);
        verify(messageMapper).insert(cap.capture());
        assertThat(cap.getValue().getBodyText()).isNull();
    }

    @Test
    @DisplayName("persistMessage: payload with no headers and no parts persists without crashing")
    void noHeadersNoParts() throws Exception {
        EmailAccount a = account();
        a.setSyncState("");
        when(gmailApiClient.getGmailService(a)).thenReturn(gmail);

        when(gmail.users().messages().list("me").setMaxResults(any(Long.class)).execute())
                .thenReturn(new com.google.api.services.gmail.model.ListMessagesResponse()
                        .setMessages(List.of(new Message().setId("bare"))));
        when(messageMapper.existsByGmailMessageId(11L, "bare")).thenReturn(false);

        MessagePart payload = new MessagePart().setMimeType("text/plain");
        when(gmail.users().messages().get("me", "bare").setFormat("full").execute())
                .thenReturn(new Message().setId("bare").setLabelIds(List.of("INBOX")).setPayload(payload));

        service.syncAccount(a);
        ArgumentCaptor<EmailMessage> cap = ArgumentCaptor.forClass(EmailMessage.class);
        verify(messageMapper).insert(cap.capture());
        // Has INBOX (no UNREAD) → isRead=true
        assertThat(cap.getValue().getIsRead()).isTrue();
    }

    @Test
    @DisplayName("persistMessage: message without payload still persists with default direction")
    void messageNoPayload() throws Exception {
        EmailAccount a = account();
        a.setSyncState("");
        when(gmailApiClient.getGmailService(a)).thenReturn(gmail);

        when(gmail.users().messages().list("me").setMaxResults(any(Long.class)).execute())
                .thenReturn(new com.google.api.services.gmail.model.ListMessagesResponse()
                        .setMessages(List.of(new Message().setId("np"))));
        when(messageMapper.existsByGmailMessageId(11L, "np")).thenReturn(false);

        when(gmail.users().messages().get("me", "np").setFormat("full").execute())
                .thenReturn(new Message().setId("np")); // no payload

        service.syncAccount(a);
        ArgumentCaptor<EmailMessage> cap = ArgumentCaptor.forClass(EmailMessage.class);
        verify(messageMapper).insert(cap.capture());
        // No from address → direction=inbound (since account.email != null but from null)
        assertThat(cap.getValue().getDirection()).isEqualTo(EmailConstants.DIRECTION_INBOUND);
    }

    @Test
    @DisplayName("extractEmailAddress / extractDisplayName: no '<' bracket returns trimmed input / null name")
    void parseHeaderNoBracket() {
        // Already covered in parsing tests, but exercise the static helpers explicitly
        assertThat(EmailSyncService.extractEmailAddress("plain@example.com"))
                .isEqualTo("plain@example.com");
        assertThat(EmailSyncService.extractDisplayName("plain@example.com")).isNull();
    }

    @Test
    @DisplayName("syncAccount: sync state '{}' is treated as no historyId → initial sync")
    void syncAccount_emptyJsonStateIsInitial() throws Exception {
        EmailAccount a = account();
        a.setSyncState("{}");
        when(gmailApiClient.getGmailService(a)).thenReturn(gmail);

        when(gmail.users().messages().list("me").setMaxResults(any(Long.class)).execute())
                .thenReturn(new com.google.api.services.gmail.model.ListMessagesResponse());

        service.syncAccount(a);
        verify(messageMapper, never()).insert(any(EmailMessage.class));
    }

    @Test
    @DisplayName("syncAccount: sync state without historyId field falls back to initial sync")
    void syncAccount_stateMissingHistoryIdField() throws Exception {
        EmailAccount a = account();
        a.setSyncState("{\"other\":\"x\"}");
        when(gmailApiClient.getGmailService(a)).thenReturn(gmail);

        when(gmail.users().messages().list("me").setMaxResults(any(Long.class)).execute())
                .thenReturn(new com.google.api.services.gmail.model.ListMessagesResponse());

        service.syncAccount(a);
        verify(messageMapper, never()).insert(any(EmailMessage.class));
    }
}
