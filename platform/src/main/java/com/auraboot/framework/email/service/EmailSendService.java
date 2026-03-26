package com.auraboot.framework.email.service;

import com.auraboot.framework.email.mapper.EmailMessageMapper;
import com.auraboot.framework.email.model.EmailAccount;
import com.auraboot.framework.email.model.EmailConstants;
import com.auraboot.framework.email.model.EmailMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;

/**
 * Service for sending outbound emails via the Gmail API.
 *
 * <p>Builds RFC 2822 MIME messages using Jakarta Mail, optionally injects tracking,
 * base64url-encodes them, and submits via {@code gmail.users().messages().send()}.
 * The sent message is saved to {@code ab_email_message} with direction=outbound.
 *
 * @since 6.5.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailSendService {

    private final GmailApiClient      gmailApiClient;
    private final EmailMessageMapper   emailMessageMapper;
    private final EmailTrackingService emailTrackingService;
    private final ObjectMapper         objectMapper;

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Sends an email via the Gmail API and saves the outbound message to the database.
     *
     * @param account         the authenticated Gmail account to send from
     * @param to              list of To recipient email addresses
     * @param cc              list of CC recipients (may be empty or null)
     * @param bcc             list of BCC recipients (may be empty or null)
     * @param subject         email subject
     * @param bodyHtml        HTML body (plain-text alternative is stripped from HTML if not provided)
     * @param threadId        Gmail thread ID for threading a reply; {@code null} for a new thread
     * @param trackingEnabled whether to inject tracking pixel and rewrite links
     * @return the persisted outbound {@link EmailMessage}
     * @throws IOException      if the Gmail API call fails
     * @throws MessagingException if building the MIME message fails
     */
    public EmailMessage send(EmailAccount account,
                             List<String> to,
                             List<String> cc,
                             List<String> bcc,
                             String subject,
                             String bodyHtml,
                             String threadId,
                             boolean trackingEnabled) throws IOException, MessagingException {

        // 1. Optionally inject tracking
        String trackingId = null;
        String finalBodyHtml = bodyHtml;
        if (trackingEnabled) {
            trackingId    = emailTrackingService.generateTrackingId();
            finalBodyHtml = emailTrackingService.injectTracking(bodyHtml, trackingId);
        }

        // 2. Build RFC 2822 MIME message
        byte[] rawBytes = buildMimeMessage(account, to, cc, bcc, subject, finalBodyHtml);
        String base64Raw = Base64.getUrlEncoder().encodeToString(rawBytes);

        // 3. Send via Gmail API
        Gmail gmail = gmailApiClient.getGmailService(account);

        Message gmailMessage = new Message();
        gmailMessage.setRaw(base64Raw);
        if (threadId != null && !threadId.isBlank()) {
            gmailMessage.setThreadId(threadId);
        }

        Message sent = gmail.users().messages().send("me", gmailMessage).execute();
        log.info("Email sent via Gmail API: accountId={}, gmailMsgId={}, subject={}",
                account.getId(), sent.getId(), subject);

        // 4. Persist to ab_email_message
        EmailMessage record = buildOutboundRecord(account, to, cc, bcc, subject,
                finalBodyHtml, trackingId, sent, threadId);
        emailMessageMapper.insert(record);

        log.info("Outbound message persisted: id={}, gmailMsgId={}", record.getId(), sent.getId());
        return record;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────────────────

    private byte[] buildMimeMessage(EmailAccount account,
                                    List<String> to,
                                    List<String> cc,
                                    List<String> bcc,
                                    String subject,
                                    String bodyHtml) throws MessagingException, IOException {

        Properties props = new Properties();
        Session session = Session.getDefaultInstance(props, null);

        MimeMessage mime = new MimeMessage(session);

        // From
        String displayName = account.getDisplayName() != null
                ? account.getDisplayName()
                : account.getEmailAddress();
        mime.setFrom(new InternetAddress(account.getEmailAddress(), displayName, "UTF-8"));

        // To
        if (to != null && !to.isEmpty()) {
            mime.setRecipients(jakarta.mail.Message.RecipientType.TO,
                    parseAddresses(to));
        }

        // CC
        if (cc != null && !cc.isEmpty()) {
            mime.setRecipients(jakarta.mail.Message.RecipientType.CC,
                    parseAddresses(cc));
        }

        // BCC
        if (bcc != null && !bcc.isEmpty()) {
            mime.setRecipients(jakarta.mail.Message.RecipientType.BCC,
                    parseAddresses(bcc));
        }

        // Subject
        mime.setSubject(subject, "UTF-8");

        // Body — multipart/alternative with text/plain + text/html
        MimeMultipart multipart = new MimeMultipart("alternative");

        // Plain-text fallback (simple HTML strip)
        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText(stripHtml(bodyHtml), "UTF-8");
        multipart.addBodyPart(textPart);

        // HTML part
        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent(bodyHtml, "text/html; charset=UTF-8");
        multipart.addBodyPart(htmlPart);

        mime.setContent(multipart);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        mime.writeTo(baos);
        return baos.toByteArray();
    }

    private InternetAddress[] parseAddresses(List<String> addresses) throws MessagingException {
        InternetAddress[] result = new InternetAddress[addresses.size()];
        for (int i = 0; i < addresses.size(); i++) {
            result[i] = new InternetAddress(addresses.get(i));
        }
        return result;
    }

    private EmailMessage buildOutboundRecord(EmailAccount account,
                                             List<String> to,
                                             List<String> cc,
                                             List<String> bcc,
                                             String subject,
                                             String bodyHtml,
                                             String trackingId,
                                             Message sent,
                                             String threadId) {
        EmailMessage record = new EmailMessage();
        record.setTenantId(account.getTenantId());
        record.setAccountId(account.getId());
        record.setGmailMessageId(sent.getId());
        record.setGmailThreadId(threadId != null ? threadId : sent.getThreadId());
        record.setDirection(EmailConstants.DIRECTION_OUTBOUND);
        record.setFromAddress(account.getEmailAddress());
        record.setFromName(account.getDisplayName());
        record.setToAddresses(toJson(to));
        record.setCcAddresses(cc != null ? toJson(cc) : "[]");
        record.setBccAddresses(bcc != null ? toJson(bcc) : "[]");
        record.setSubject(subject);
        record.setBodyHtml(bodyHtml);
        record.setHasAttachments(false);
        record.setIsRead(true); // sender has "read" their own email
        record.setGmailDate(Instant.now());
        record.setSyncedAt(Instant.now());

        // Store trackingId in label_ids slot for now (no dedicated column needed)
        // Use a small JSON metadata string; empty if no tracking
        if (trackingId != null) {
            record.setLabelIds("[\"SENT\",\"TRACKING:" + trackingId + "\"]");
        } else {
            record.setLabelIds("[\"SENT\"]");
        }
        return record;
    }

    /** Very simple HTML→plaintext stripper for the text/plain fallback part. */
    private String stripHtml(String html) {
        if (html == null) {
            return "";
        }
        return html.replaceAll("<[^>]+>", "").trim();
    }

    private String toJson(List<String> list) {
        try {
            return objectMapper.writeValueAsString(list != null ? list : new ArrayList<>());
        } catch (Exception e) {
            return "[]";
        }
    }
}
