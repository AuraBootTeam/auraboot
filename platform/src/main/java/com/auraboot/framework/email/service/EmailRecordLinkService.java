package com.auraboot.framework.email.service;

import com.auraboot.framework.email.mapper.EmailRecordLinkMapper;
import com.auraboot.framework.email.model.EmailConstants;
import com.auraboot.framework.email.model.EmailMessage;
import com.auraboot.framework.email.model.EmailRecordLink;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for linking email messages to arbitrary product records.
 *
 * <p>The platform owns generic manual links and thread inheritance. Product-specific participant
 * matching is contributed by the product and is deliberately not coupled to a product schema here.
 *
 * @since 6.5.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailRecordLinkService {

    private final EmailRecordLinkMapper emailRecordLinkMapper;

    // ──────────────────────────────────────────────────────────────────────────
    // Auto-link
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Inherits generic thread-level links from sibling messages. Product participant matching runs
     * in product-owned handlers so the platform never queries a product table.
     *
     * @param message the inbound or outbound message to link
     */
    public void autoLink(EmailMessage message) {
        List<EmailRecordLink> links = new ArrayList<>();
        Long tenantId  = message.getTenantId();
        Long messageId = message.getId();
        String threadId = message.getGmailThreadId();

        if (threadId != null && !threadId.isBlank()) {
            List<EmailRecordLink> threadLinks = emailRecordLinkMapper.findByThread(tenantId, threadId);
            for (EmailRecordLink tl : threadLinks) {
                if (tl.getMessageId() != null && !tl.getMessageId().equals(messageId)) {
                    links.add(buildLink(tenantId, messageId, threadId, tl.getModelCode(),
                            tl.getRecordPid(), EmailConstants.LINK_TYPE_AUTO));
                }
            }
        }

        for (EmailRecordLink link : links) {
            try {
                emailRecordLinkMapper.insert(link);
            } catch (Exception e) {
                log.warn("Failed to insert auto-link messageId={} modelCode={} recordPid={}: {}",
                        messageId, link.getModelCode(), link.getRecordPid(), e.getMessage());
            }
        }

        log.info("autoLink: messageId={} → {} links created", messageId, links.size());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Manual link
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Creates a manual CRM record link for a message (or thread).
     *
     * @param tenantId  owning tenant
     * @param messageId message to link (may be null for thread-level links)
     * @param threadId  Gmail thread ID (may be null if message-level only)
     * @param modelCode DSL model code (e.g. {@code crm_contact_common})
     * @param recordPid public pid of the CRM record
     * @return the persisted link
     */
    public EmailRecordLink manualLink(Long tenantId, Long messageId, String threadId,
                                      String modelCode, String recordPid) {
        EmailRecordLink link = buildLink(tenantId, messageId, threadId, modelCode, recordPid,
                EmailConstants.LINK_TYPE_MANUAL);
        emailRecordLinkMapper.insert(link);
        log.info("manualLink: messageId={} → modelCode={} recordPid={}", messageId, modelCode, recordPid);
        return link;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Remove link
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Deletes a CRM record link by its primary key.
     *
     * @param linkId the link ID to delete
     */
    public void removeLink(Long linkId) {
        emailRecordLinkMapper.deleteById(linkId);
        log.info("removeLink: linkId={} deleted", linkId);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────────────────

    private EmailRecordLink buildLink(Long tenantId, Long messageId, String threadId,
                                      String modelCode, String recordPid, String linkType) {
        EmailRecordLink link = new EmailRecordLink();
        link.setTenantId(tenantId);
        link.setMessageId(messageId);
        link.setThreadId(threadId);
        link.setModelCode(modelCode);
        link.setRecordPid(recordPid);
        link.setLinkType(linkType);
        link.setCreatedAt(Instant.now());
        return link;
    }
}
