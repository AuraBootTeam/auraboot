package com.auraboot.framework.email.service;

import com.auraboot.framework.email.mapper.EmailMessageMapper;
import com.auraboot.framework.email.mapper.EmailRecordLinkMapper;
import com.auraboot.framework.email.model.EmailConstants;
import com.auraboot.framework.email.model.EmailMessage;
import com.auraboot.framework.email.model.EmailRecordLink;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for linking email messages to CRM records.
 *
 * <p>Auto-linking scans the email's participant addresses against known CRM contacts and leads.
 * Manual linking allows users to explicitly associate a message with any CRM record.
 *
 * @since 6.5.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailRecordLinkService {

    private final EmailRecordLinkMapper emailRecordLinkMapper;
    private final EmailMessageMapper    emailMessageMapper;
    private final JdbcTemplate          jdbcTemplate;
    private final ObjectMapper          objectMapper;

    // ──────────────────────────────────────────────────────────────────────────
    // Auto-link
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Attempts to automatically link the given message to CRM records by matching
     * participant email addresses against {@code mt_crm_contact} and {@code mt_crm_lead}.
     *
     * <p>Strategy:
     * <ol>
     *   <li>Collect participant addresses: inbound → from_address; outbound → to/cc.</li>
     *   <li>Match each address against CRM Contact (crm_ct_email) and Lead (crm_lead_contact_email).</li>
     *   <li>For matched Contacts, also find related Opportunities via opp_contact junction.</li>
     *   <li>If no direct match, inherit thread-level links from sibling messages.</li>
     *   <li>Create {@code ab_email_record_link} entries (auto type).</li>
     * </ol>
     *
     * @param message the inbound or outbound message to link
     */
    public void autoLink(EmailMessage message) {
        List<String> addresses = extractParticipantAddresses(message);
        if (addresses.isEmpty()) {
            log.debug("autoLink: no participant addresses for messageId={}", message.getId());
            return;
        }

        List<EmailRecordLink> links = new ArrayList<>();
        Long tenantId  = message.getTenantId();
        Long messageId = message.getId();
        String threadId = message.getGmailThreadId();

        for (String email : addresses) {
            // Match against CRM Contact
            List<String> contactIds = findCrmContactIdsByEmail(tenantId, email);
            for (String contactId : contactIds) {
                links.add(buildLink(tenantId, messageId, threadId, "crm_contact", contactId,
                        EmailConstants.LINK_TYPE_AUTO));

                // Also link to related Opportunities via opp_contact junction
                List<String> oppIds = findRelatedOpportunityIds(tenantId, contactId);
                for (String oppId : oppIds) {
                    links.add(buildLink(tenantId, messageId, threadId, "crm_opportunity", oppId,
                            EmailConstants.LINK_TYPE_AUTO));
                }
            }

            // Match against CRM Lead
            List<String> leadIds = findCrmLeadIdsByEmail(tenantId, email);
            for (String leadId : leadIds) {
                links.add(buildLink(tenantId, messageId, threadId, "crm_lead", leadId,
                        EmailConstants.LINK_TYPE_AUTO));
            }
        }

        // If no direct matches, inherit thread-level links from sibling messages
        if (links.isEmpty() && threadId != null && !threadId.isBlank()) {
            List<EmailRecordLink> threadLinks = emailRecordLinkMapper.findByThread(tenantId, threadId);
            for (EmailRecordLink tl : threadLinks) {
                if (tl.getMessageId() != null && !tl.getMessageId().equals(messageId)) {
                    links.add(buildLink(tenantId, messageId, threadId, tl.getModelCode(),
                            tl.getRecordId(), EmailConstants.LINK_TYPE_AUTO));
                }
            }
        }

        for (EmailRecordLink link : links) {
            try {
                emailRecordLinkMapper.insert(link);
            } catch (Exception e) {
                log.warn("Failed to insert auto-link messageId={} modelCode={} recordId={}: {}",
                        messageId, link.getModelCode(), link.getRecordId(), e.getMessage());
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
     * @param modelCode DSL model code (e.g. {@code crm_contact})
     * @param recordId  primary key of the CRM record
     * @return the persisted link
     */
    public EmailRecordLink manualLink(Long tenantId, Long messageId, String threadId,
                                      String modelCode, String recordId) {
        EmailRecordLink link = buildLink(tenantId, messageId, threadId, modelCode, recordId,
                EmailConstants.LINK_TYPE_MANUAL);
        emailRecordLinkMapper.insert(link);
        log.info("manualLink: messageId={} → modelCode={} recordId={}", messageId, modelCode, recordId);
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

    /** Extracts the relevant email addresses to match from this message. */
    private List<String> extractParticipantAddresses(EmailMessage message) {
        List<String> result = new ArrayList<>();

        if (EmailConstants.DIRECTION_INBOUND.equals(message.getDirection())) {
            // Inbound: the sender is the CRM contact candidate
            if (message.getFromAddress() != null && !message.getFromAddress().isBlank()) {
                result.add(message.getFromAddress().trim().toLowerCase());
            }
        } else {
            // Outbound: recipients are the CRM contact candidates
            result.addAll(parseJsonAddresses(message.getToAddresses()));
            result.addAll(parseJsonAddresses(message.getCcAddresses()));
        }

        return result;
    }

    /** Parses a JSON array of email strings (e.g. {@code ["a@b.com","c@d.com"]}). */
    private List<String> parseJsonAddresses(String json) {
        if (json == null || json.isBlank() || json.equals("[]")) {
            return List.of();
        }
        try {
            List<String> parsed = objectMapper.readValue(json, new TypeReference<>() {});
            return parsed.stream()
                    .filter(s -> s != null && !s.isBlank())
                    .map(s -> s.trim().toLowerCase())
                    .toList();
        } catch (Exception e) {
            log.debug("Failed to parse address JSON '{}': {}", json, e.getMessage());
            return List.of();
        }
    }

    /** Queries mt_crm_contact for records with the given email address. */
    private List<String> findCrmContactIdsByEmail(Long tenantId, String email) {
        try {
            return jdbcTemplate.queryForList(
                    "SELECT id::text FROM mt_crm_contact WHERE tenant_id = ? AND lower(crm_ct_email) = ? AND (deleted_flag = FALSE OR deleted_flag IS NULL)",
                    String.class, tenantId, email);
        } catch (Exception e) {
            log.debug("CRM contact lookup skipped (table may not exist): {}", e.getMessage());
            return List.of();
        }
    }

    /** Queries mt_crm_lead for records with the given email address. */
    private List<String> findCrmLeadIdsByEmail(Long tenantId, String email) {
        try {
            return jdbcTemplate.queryForList(
                    "SELECT id::text FROM mt_crm_lead WHERE tenant_id = ? AND lower(crm_lead_contact_email) = ? AND (deleted_flag = FALSE OR deleted_flag IS NULL)",
                    String.class, tenantId, email);
        } catch (Exception e) {
            log.debug("CRM lead lookup skipped (table may not exist): {}", e.getMessage());
            return List.of();
        }
    }

    /** Finds Opportunity IDs linked to the given contact via the opp_contact junction table. */
    private List<String> findRelatedOpportunityIds(Long tenantId, String contactId) {
        try {
            return jdbcTemplate.queryForList(
                    "SELECT crm_opc_opp_id::text FROM mt_crm_opp_contact WHERE tenant_id = ? AND crm_opc_contact_id::text = ? AND (deleted_flag = FALSE OR deleted_flag IS NULL)",
                    String.class, tenantId, contactId);
        } catch (Exception e) {
            log.debug("Opportunity junction lookup skipped (table may not exist): {}", e.getMessage());
            return List.of();
        }
    }

    private EmailRecordLink buildLink(Long tenantId, Long messageId, String threadId,
                                      String modelCode, String recordId, String linkType) {
        EmailRecordLink link = new EmailRecordLink();
        link.setTenantId(tenantId);
        link.setMessageId(messageId);
        link.setThreadId(threadId);
        link.setModelCode(modelCode);
        link.setRecordId(recordId);
        link.setLinkType(linkType);
        link.setCreatedAt(Instant.now());
        return link;
    }
}
