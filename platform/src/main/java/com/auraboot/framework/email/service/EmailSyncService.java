package com.auraboot.framework.email.service;

import com.auraboot.framework.email.mapper.EmailAccountMapper;
import com.auraboot.framework.email.mapper.EmailMessageMapper;
import com.auraboot.framework.email.model.EmailAccount;
import com.auraboot.framework.email.model.EmailConstants;
import com.auraboot.framework.email.model.EmailMessage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.History;
import com.google.api.services.gmail.model.HistoryMessageAdded;
import com.google.api.services.gmail.model.ListHistoryResponse;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartHeader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Core Gmail sync logic: fetches messages via the Gmail API and persists them to
 * {@code ab_email_message}.
 *
 * <p>Supports two sync modes:
 * <ul>
 *   <li><b>Initial sync</b> — no historyId in {@code sync_state}: fetches the last 500 messages.</li>
 *   <li><b>Incremental sync</b> — historyId present: uses the History API to fetch only deltas.</li>
 * </ul>
 *
 * <p>Deduplication is enforced by {@link EmailMessageMapper#existsByGmailMessageId}.
 *
 * <p>The static helper methods {@link #extractEmailAddress} and {@link #extractDisplayName}
 * are package-private so they can be exercised by unit tests without a Spring context.
 *
 * @since 6.5.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailSyncService {

    private static final int INITIAL_SYNC_MAX_RESULTS = 500;
    private static final int HTTP_401 = 401;
    private static final int HTTP_404 = 404;
    private static final int HTTP_429 = 429;

    private final GmailApiClient       gmailApiClient;
    private final EmailAccountMapper   emailAccountMapper;
    private final EmailMessageMapper   emailMessageMapper;
    private final ObjectMapper         objectMapper;

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Syncs all new messages for the given account.
     *
     * <p>On 401 the account is marked as {@link EmailConstants#ACCOUNT_STATUS_ERROR} and the
     * method returns without throwing. On 404 the sync state is cleared for a full re-sync.
     * On rate-limit (429) the error is logged and the method returns without throwing.
     *
     * @param account the email account to sync (must have valid encrypted tokens)
     */
    public void syncAccount(EmailAccount account) {
        log.info("Starting sync for accountId={}, email={}", account.getId(), account.getEmailAddress());
        try {
            Gmail gmail = gmailApiClient.getGmailService(account);
            BigInteger historyId = readHistoryId(account);

            List<String> messageIds;
            BigInteger newHistoryId;

            if (historyId == null) {
                // Initial full sync
                log.info("No historyId found, starting initial sync for accountId={}", account.getId());
                messageIds   = fetchInitialMessageIds(gmail);
                newHistoryId = null; // will be set from message internalDate after fetch
            } else {
                // Incremental sync via History API
                log.info("Incremental sync from historyId={} for accountId={}", historyId, account.getId());
                IncrementalResult result = fetchIncrementalMessageIds(gmail, historyId);
                messageIds   = result.messageIds;
                newHistoryId = result.historyId;
            }

            // Fetch and persist each message
            BigInteger latestHistoryId = historyId;
            for (String msgId : messageIds) {
                if (emailMessageMapper.existsByGmailMessageId(account.getId(), msgId)) {
                    log.debug("Skipping already-synced message: accountId={}, msgId={}", account.getId(), msgId);
                    continue;
                }
                try {
                    Message message = gmail.users().messages().get("me", msgId)
                            .setFormat("full")
                            .execute();
                    EmailMessage saved = persistMessage(account, message);
                    if (message.getHistoryId() != null) {
                        if (latestHistoryId == null || message.getHistoryId().compareTo(latestHistoryId) > 0) {
                            latestHistoryId = message.getHistoryId();
                        }
                    }
                    log.debug("Synced message: accountId={}, msgId={}, subject={}",
                            account.getId(), msgId, saved.getSubject());
                } catch (IOException e) {
                    log.warn("Failed to fetch message {} for account {}: {}", msgId, account.getId(), e.getMessage());
                }
            }

            // Update sync state with latest historyId
            BigInteger updatedHistoryId = newHistoryId != null ? newHistoryId : latestHistoryId;
            if (updatedHistoryId != null) {
                updateSyncState(account, updatedHistoryId);
            }

            log.info("Sync complete for accountId={}: processed {} messages, historyId={}",
                    account.getId(), messageIds.size(), updatedHistoryId);

        } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
            handleGoogleApiError(account, e);
        } catch (IOException e) {
            log.error("IO error syncing accountId={}: {}", account.getId(), e.getMessage(), e);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Package-private helpers (testable without Spring context)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Extracts the email address from an RFC 5322 header value such as
     * {@code "John Doe <john@example.com>"} or a plain address {@code "john@example.com"}.
     *
     * @param headerValue raw header value; may be {@code null}
     * @return extracted email address, or {@code null} if input is null/blank
     */
    public static String extractEmailAddress(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return null;
        }
        String trimmed = headerValue.trim();
        int lt = trimmed.lastIndexOf('<');
        int gt = trimmed.lastIndexOf('>');
        if (lt >= 0 && gt > lt) {
            return trimmed.substring(lt + 1, gt).trim();
        }
        return trimmed;
    }

    /**
     * Extracts the display name from an RFC 5322 header value such as
     * {@code "John Doe <john@example.com>"}.
     *
     * @param headerValue raw header value; may be {@code null}
     * @return display name (unquoted), or {@code null} if no name is present
     */
    public static String extractDisplayName(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return null;
        }
        String trimmed = headerValue.trim();
        int lt = trimmed.lastIndexOf('<');
        if (lt > 0) {
            String name = trimmed.substring(0, lt).trim();
            // Strip surrounding quotes if present
            if (name.startsWith("\"") && name.endsWith("\"") && name.length() > 1) {
                name = name.substring(1, name.length() - 1).trim();
            }
            return name.isEmpty() ? null : name;
        }
        return null;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private: fetch
    // ──────────────────────────────────────────────────────────────────────────

    private List<String> fetchInitialMessageIds(Gmail gmail) throws IOException {
        List<String> ids = new ArrayList<>();
        ListMessagesResponse response = gmail.users().messages().list("me")
                .setMaxResults((long) INITIAL_SYNC_MAX_RESULTS)
                .execute();

        if (response.getMessages() != null) {
            for (Message m : response.getMessages()) {
                ids.add(m.getId());
            }
        }
        return ids;
    }

    private static class IncrementalResult {
        final List<String> messageIds;
        final BigInteger   historyId;

        IncrementalResult(List<String> messageIds, BigInteger historyId) {
            this.messageIds = messageIds;
            this.historyId  = historyId;
        }
    }

    private IncrementalResult fetchIncrementalMessageIds(Gmail gmail, BigInteger startHistoryId)
            throws IOException {
        List<String> ids = new ArrayList<>();
        BigInteger latestHistoryId = startHistoryId;

        ListHistoryResponse response = gmail.users().history().list("me")
                .setStartHistoryId(startHistoryId)
                .setHistoryTypes(List.of("messageAdded", "labelAdded", "labelRemoved"))
                .execute();

        if (response.getHistory() != null) {
            for (History history : response.getHistory()) {
                if (history.getId() != null
                        && history.getId().compareTo(latestHistoryId) > 0) {
                    latestHistoryId = history.getId();
                }
                if (history.getMessagesAdded() != null) {
                    for (HistoryMessageAdded added : history.getMessagesAdded()) {
                        if (added.getMessage() != null) {
                            ids.add(added.getMessage().getId());
                        }
                    }
                }
            }
        }

        BigInteger finalHistoryId = response.getHistoryId() != null
                ? response.getHistoryId()
                : latestHistoryId;
        return new IncrementalResult(ids, finalHistoryId);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private: persist
    // ──────────────────────────────────────────────────────────────────────────

    private EmailMessage persistMessage(EmailAccount account, Message message) {
        EmailMessage email = new EmailMessage();
        email.setTenantId(account.getTenantId());
        email.setAccountId(account.getId());
        email.setGmailMessageId(message.getId());
        email.setGmailThreadId(message.getThreadId());
        email.setSyncedAt(Instant.now());

        // Label IDs
        if (message.getLabelIds() != null) {
            email.setLabelIds(toJson(message.getLabelIds()));
            email.setIsRead(!message.getLabelIds().contains("UNREAD"));
        } else {
            email.setIsRead(true);
        }

        // Internal date (millis since epoch)
        if (message.getInternalDate() != null) {
            email.setGmailDate(Instant.ofEpochMilli(message.getInternalDate()));
        }

        // Parse MIME headers and body
        if (message.getPayload() != null) {
            parseHeaders(email, message.getPayload());
            boolean metadataOnly = EmailConstants.SYNC_MODE_MANUAL.equals(account.getSyncMode());
            if (!metadataOnly) {
                extractBody(email, message.getPayload());
            }
            extractAttachments(email, message.getPayload());
        }

        // Direction: from == account email → outbound
        if (account.getEmailAddress() != null
                && account.getEmailAddress().equalsIgnoreCase(email.getFromAddress())) {
            email.setDirection(EmailConstants.DIRECTION_OUTBOUND);
        } else {
            email.setDirection(EmailConstants.DIRECTION_INBOUND);
        }

        emailMessageMapper.insert(email);
        return email;
    }

    private void parseHeaders(EmailMessage email, MessagePart payload) {
        if (payload.getHeaders() == null) {
            return;
        }
        for (MessagePartHeader h : payload.getHeaders()) {
            switch (h.getName().toLowerCase()) {
                case "from" -> {
                    email.setFromAddress(extractEmailAddress(h.getValue()));
                    email.setFromName(extractDisplayName(h.getValue()));
                }
                case "to"      -> email.setToAddresses(parseAddressList(h.getValue()));
                case "cc"      -> email.setCcAddresses(parseAddressList(h.getValue()));
                case "bcc"     -> email.setBccAddresses(parseAddressList(h.getValue()));
                case "subject" -> email.setSubject(h.getValue());
                default        -> { /* ignore */ }
            }
        }
    }

    /** Parses a comma-separated address header into a JSON array of email addresses. */
    private String parseAddressList(String header) {
        if (header == null || header.isBlank()) {
            return "[]";
        }
        List<String> addresses = new ArrayList<>();
        // Split by comma but respect quoted strings
        for (String part : header.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")) {
            String addr = extractEmailAddress(part.trim());
            if (addr != null && !addr.isBlank()) {
                addresses.add(addr);
            }
        }
        return toJson(addresses);
    }

    private void extractBody(EmailMessage email, MessagePart part) {
        extractBodyRecursive(email, part);
    }

    private void extractBodyRecursive(EmailMessage email, MessagePart part) {
        String mimeType = part.getMimeType();
        if (mimeType == null) {
            mimeType = "";
        }

        if ("text/plain".equals(mimeType) && email.getBodyText() == null) {
            String decoded = decodeBody(part);
            if (decoded != null) {
                email.setBodyText(decoded);
            }
        } else if ("text/html".equals(mimeType) && email.getBodyHtml() == null) {
            String decoded = decodeBody(part);
            if (decoded != null) {
                email.setBodyHtml(decoded);
            }
        }

        // Recurse into multipart children
        if (mimeType.startsWith("multipart/") && part.getParts() != null) {
            for (MessagePart child : part.getParts()) {
                extractBodyRecursive(email, child);
            }
        }
    }

    private String decodeBody(MessagePart part) {
        if (part.getBody() == null || part.getBody().getData() == null) {
            return null;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(part.getBody().getData());
            return new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Failed to decode message body: {}", e.getMessage());
            return null;
        }
    }

    private void extractAttachments(EmailMessage email, MessagePart part) {
        List<Map<String, Object>> attachments = new ArrayList<>();
        collectAttachments(part, attachments);
        email.setHasAttachments(!attachments.isEmpty());
        if (!attachments.isEmpty()) {
            email.setAttachments(toJson(attachments));
        }
    }

    private void collectAttachments(MessagePart part, List<Map<String, Object>> result) {
        if (part.getFilename() != null && !part.getFilename().isBlank()
                && part.getBody() != null && part.getBody().getAttachmentId() != null) {
            Map<String, Object> attachment = new HashMap<>();
            attachment.put("filename",     part.getFilename());
            attachment.put("mimeType",     part.getMimeType());
            attachment.put("size",         part.getBody().getSize());
            attachment.put("attachmentId", part.getBody().getAttachmentId());
            result.add(attachment);
        }
        if (part.getParts() != null) {
            for (MessagePart child : part.getParts()) {
                collectAttachments(child, result);
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private: sync state
    // ──────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private BigInteger readHistoryId(EmailAccount account) {
        String syncState = account.getSyncState();
        if (syncState == null || syncState.isBlank() || "{}".equals(syncState)) {
            return null;
        }
        try {
            Map<String, Object> state = objectMapper.readValue(syncState,
                    new TypeReference<Map<String, Object>>() {});
            Object hid = state.get("historyId");
            if (hid == null) {
                return null;
            }
            return new BigInteger(hid.toString());
        } catch (Exception e) {
            log.warn("Failed to parse sync_state for accountId={}: {}", account.getId(), e.getMessage());
            return null;
        }
    }

    private void updateSyncState(EmailAccount account, BigInteger historyId) {
        try {
            Map<String, Object> state = new HashMap<>();
            state.put("historyId", historyId.toString());
            state.put("lastSyncAt", Instant.now().toString());
            String json = objectMapper.writeValueAsString(state);
            emailAccountMapper.updateSyncState(account.getId(), json);
            account.setSyncState(json);
        } catch (Exception e) {
            log.warn("Failed to update sync_state for accountId={}: {}", account.getId(), e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private: error handling
    // ──────────────────────────────────────────────────────────────────────────

    private void handleGoogleApiError(EmailAccount account,
            com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
        int statusCode = e.getStatusCode();
        if (statusCode == HTTP_401) {
            log.error("Auth failure for accountId={}, marking disconnected: {}",
                    account.getId(), e.getMessage());
            markAccountError(account);
        } else if (statusCode == HTTP_404) {
            log.warn("History not found for accountId={}, clearing sync state for full re-sync",
                    account.getId());
            emailAccountMapper.updateSyncState(account.getId(), "{}");
            account.setSyncState("{}");
        } else if (statusCode == HTTP_429) {
            log.warn("Rate limited by Gmail API for accountId={}, skipping this cycle", account.getId());
        } else {
            log.error("Google API error {} for accountId={}: {}",
                    statusCode, account.getId(), e.getMessage());
        }
    }

    private void markAccountError(EmailAccount account) {
        account.setStatus(EmailConstants.ACCOUNT_STATUS_ERROR);
        emailAccountMapper.updateById(account);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private: JSON helpers
    // ──────────────────────────────────────────────────────────────────────────

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Failed to serialize to JSON: {}", e.getMessage());
            return "[]";
        }
    }
}
