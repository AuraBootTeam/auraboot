package com.auraboot.framework.email;

import com.auraboot.framework.email.mapper.EmailAccountMapper;
import com.auraboot.framework.email.mapper.EmailMessageMapper;
import com.auraboot.framework.email.model.EmailAccount;
import com.auraboot.framework.email.model.EmailConstants;
import com.auraboot.framework.email.model.EmailMessage;
import com.auraboot.framework.email.service.EmailSyncService;
import com.auraboot.framework.email.service.GmailApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpResponseException;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.ListMessagesResponse;
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

import java.io.IOException;
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
 * Pure unit tests for {@link EmailSyncService} — initial sync path, error handling,
 * and message persistence.  Uses deep-stub Gmail mock.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmailSyncService Unit Tests")
class EmailSyncServiceUnitTest {

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
        a.setId(1L);
        a.setTenantId(7L);
        a.setEmailAddress("me@example.com");
        a.setSyncMode(EmailConstants.SYNC_MODE_AUTO);
        return a;
    }

    private static GoogleJsonResponseException jsonError(int statusCode) {
        HttpResponseException.Builder b = new HttpResponseException.Builder(
                statusCode, "Err", new HttpHeaders());
        return new GoogleJsonResponseException(b, new GoogleJsonError());
    }

    @Test
    @DisplayName("syncAccount: 401 marks account ERROR")
    void syncAccount_401_marksError() throws Exception {
        EmailAccount a = account();
        when(gmailApiClient.getGmailService(a)).thenThrow(jsonError(401));
        service.syncAccount(a);
        assertThat(a.getStatus()).isEqualTo(EmailConstants.ACCOUNT_STATUS_ERROR);
        verify(accountMapper).updateById(a);
    }

    @Test
    @DisplayName("syncAccount: 404 clears sync state")
    void syncAccount_404_clearsSyncState() throws Exception {
        EmailAccount a = account();
        a.setSyncState("{\"historyId\":\"123\"}");
        when(gmailApiClient.getGmailService(a)).thenThrow(jsonError(404));
        service.syncAccount(a);
        verify(accountMapper).updateSyncState(1L, "{}");
        assertThat(a.getSyncState()).isEqualTo("{}");
    }

    @Test
    @DisplayName("syncAccount: 429 is logged but not propagated; account status untouched")
    void syncAccount_429_swallowed() throws Exception {
        EmailAccount a = account();
        when(gmailApiClient.getGmailService(a)).thenThrow(jsonError(429));
        service.syncAccount(a);
        // No state change asserted; just that no throw and no insert
        verify(messageMapper, never()).insert(any(EmailMessage.class));
    }

    @Test
    @DisplayName("syncAccount: other Google error logged only")
    void syncAccount_otherStatus() throws Exception {
        EmailAccount a = account();
        when(gmailApiClient.getGmailService(a)).thenThrow(jsonError(500));
        service.syncAccount(a);
        verify(messageMapper, never()).insert(any(EmailMessage.class));
    }

    @Test
    @DisplayName("syncAccount: plain IOException swallowed (logged)")
    void syncAccount_ioException() throws Exception {
        EmailAccount a = account();
        when(gmailApiClient.getGmailService(a)).thenThrow(new IOException("io"));
        service.syncAccount(a);
        verify(messageMapper, never()).insert(any(EmailMessage.class));
    }

    @Test
    @DisplayName("syncAccount: initial sync (no historyId) — fetches list, persists new messages, skips existing")
    void syncAccount_initialSync_persists() throws Exception {
        EmailAccount a = account();
        a.setSyncState(null);
        when(gmailApiClient.getGmailService(a)).thenReturn(gmail);

        // List returns 2 messages
        ListMessagesResponse list = new ListMessagesResponse()
                .setMessages(List.of(new Message().setId("m1"), new Message().setId("m2")));
        when(gmail.users().messages().list("me").setMaxResults(any(Long.class)).execute())
                .thenReturn(list);

        // m1 not seen, m2 seen
        when(messageMapper.existsByGmailMessageId(1L, "m1")).thenReturn(false);
        when(messageMapper.existsByGmailMessageId(1L, "m2")).thenReturn(true);

        // get(m1) returns full message with from header and body
        MessagePart payload = new MessagePart()
                .setMimeType("text/plain")
                .setHeaders(List.of(
                        new MessagePartHeader().setName("From").setValue("Someone <s@x.com>"),
                        new MessagePartHeader().setName("Subject").setValue("Hello"),
                        new MessagePartHeader().setName("To").setValue("me@example.com")
                ))
                .setBody(new MessagePartBody().setData(
                        Base64.getUrlEncoder().encodeToString("body text".getBytes(StandardCharsets.UTF_8))));
        Message fullM1 = new Message()
                .setId("m1")
                .setThreadId("t1")
                .setHistoryId(BigInteger.valueOf(500))
                .setInternalDate(1700000000000L)
                .setLabelIds(List.of("INBOX", "UNREAD"))
                .setPayload(payload);
        when(gmail.users().messages().get("me", "m1").setFormat("full").execute())
                .thenReturn(fullM1);

        service.syncAccount(a);

        ArgumentCaptor<EmailMessage> cap = ArgumentCaptor.forClass(EmailMessage.class);
        verify(messageMapper).insert(cap.capture());
        EmailMessage saved = cap.getValue();
        assertThat(saved.getGmailMessageId()).isEqualTo("m1");
        assertThat(saved.getFromAddress()).isEqualTo("s@x.com");
        assertThat(saved.getFromName()).isEqualTo("Someone");
        assertThat(saved.getSubject()).isEqualTo("Hello");
        assertThat(saved.getDirection()).isEqualTo(EmailConstants.DIRECTION_INBOUND);
        assertThat(saved.getIsRead()).isFalse(); // UNREAD label present
        assertThat(saved.getBodyText()).isEqualTo("body text");
        assertThat(saved.getHasAttachments()).isFalse();
        // historyId should be persisted via updateSyncState
        verify(accountMapper).updateSyncState(eq(1L), anyString());
    }

    @Test
    @DisplayName("syncAccount: outbound direction when from == account email")
    void syncAccount_outboundDirection() throws Exception {
        EmailAccount a = account();
        a.setSyncState(null);
        when(gmailApiClient.getGmailService(a)).thenReturn(gmail);

        ListMessagesResponse list = new ListMessagesResponse()
                .setMessages(List.of(new Message().setId("ms")));
        when(gmail.users().messages().list("me").setMaxResults(any(Long.class)).execute())
                .thenReturn(list);
        when(messageMapper.existsByGmailMessageId(1L, "ms")).thenReturn(false);

        MessagePart payload = new MessagePart()
                .setMimeType("text/html")
                .setHeaders(List.of(
                        new MessagePartHeader().setName("From").setValue("me@example.com"),
                        new MessagePartHeader().setName("To").setValue("\"Bob\" <b@x.com>, c@x.com")
                ))
                .setBody(new MessagePartBody().setData(
                        Base64.getUrlEncoder().encodeToString("<p>hi</p>".getBytes(StandardCharsets.UTF_8))));
        Message msg = new Message()
                .setId("ms")
                .setThreadId("ts")
                .setPayload(payload);
        when(gmail.users().messages().get("me", "ms").setFormat("full").execute()).thenReturn(msg);

        service.syncAccount(a);

        ArgumentCaptor<EmailMessage> cap = ArgumentCaptor.forClass(EmailMessage.class);
        verify(messageMapper).insert(cap.capture());
        assertThat(cap.getValue().getDirection()).isEqualTo(EmailConstants.DIRECTION_OUTBOUND);
        assertThat(cap.getValue().getBodyHtml()).isEqualTo("<p>hi</p>");
        assertThat(cap.getValue().getToAddresses()).contains("b@x.com").contains("c@x.com");
    }

    @Test
    @DisplayName("syncAccount: manual sync mode skips body extraction")
    void syncAccount_manualSyncMode_skipsBody() throws Exception {
        EmailAccount a = account();
        a.setSyncMode(EmailConstants.SYNC_MODE_MANUAL);
        a.setSyncState(null);
        when(gmailApiClient.getGmailService(a)).thenReturn(gmail);

        ListMessagesResponse list = new ListMessagesResponse()
                .setMessages(List.of(new Message().setId("mb")));
        when(gmail.users().messages().list("me").setMaxResults(any(Long.class)).execute())
                .thenReturn(list);
        when(messageMapper.existsByGmailMessageId(1L, "mb")).thenReturn(false);

        MessagePart payload = new MessagePart()
                .setMimeType("text/plain")
                .setHeaders(List.of(new MessagePartHeader().setName("From").setValue("a@b.com")))
                .setBody(new MessagePartBody().setData(
                        Base64.getUrlEncoder().encodeToString("text".getBytes(StandardCharsets.UTF_8))));
        when(gmail.users().messages().get("me", "mb").setFormat("full").execute())
                .thenReturn(new Message().setId("mb").setPayload(payload));

        service.syncAccount(a);

        ArgumentCaptor<EmailMessage> cap = ArgumentCaptor.forClass(EmailMessage.class);
        verify(messageMapper).insert(cap.capture());
        // metadataOnly=true → bodyText not set
        assertThat(cap.getValue().getBodyText()).isNull();
    }

    @Test
    @DisplayName("syncAccount: per-message IOException is logged; sync continues")
    void syncAccount_perMessageError_continues() throws Exception {
        EmailAccount a = account();
        a.setSyncState(null);
        when(gmailApiClient.getGmailService(a)).thenReturn(gmail);

        ListMessagesResponse list = new ListMessagesResponse()
                .setMessages(List.of(new Message().setId("mx")));
        when(gmail.users().messages().list("me").setMaxResults(any(Long.class)).execute())
                .thenReturn(list);
        when(messageMapper.existsByGmailMessageId(1L, "mx")).thenReturn(false);
        when(gmail.users().messages().get("me", "mx").setFormat("full").execute())
                .thenThrow(new IOException("nope"));

        service.syncAccount(a);
        verify(messageMapper, never()).insert(any(EmailMessage.class));
    }

    @Test
    @DisplayName("syncAccount: empty list response ends cleanly")
    void syncAccount_emptyList() throws Exception {
        EmailAccount a = account();
        a.setSyncState("");
        when(gmailApiClient.getGmailService(a)).thenReturn(gmail);

        ListMessagesResponse list = new ListMessagesResponse();
        when(gmail.users().messages().list("me").setMaxResults(any(Long.class)).execute())
                .thenReturn(list);

        service.syncAccount(a);
        verify(messageMapper, never()).insert(any(EmailMessage.class));
    }

    @Test
    @DisplayName("syncAccount: sync state with unparseable historyId falls back to initial sync")
    void syncAccount_unparseableSyncState() throws Exception {
        EmailAccount a = account();
        a.setSyncState("{not json");
        when(gmailApiClient.getGmailService(a)).thenReturn(gmail);

        when(gmail.users().messages().list("me").setMaxResults(any(Long.class)).execute())
                .thenReturn(new ListMessagesResponse());

        service.syncAccount(a);
        verify(messageMapper, never()).insert(any(EmailMessage.class));
    }
}
