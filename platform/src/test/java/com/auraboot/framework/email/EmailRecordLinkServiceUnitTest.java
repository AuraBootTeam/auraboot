package com.auraboot.framework.email;

import com.auraboot.framework.email.mapper.EmailMessageMapper;
import com.auraboot.framework.email.mapper.EmailRecordLinkMapper;
import com.auraboot.framework.email.model.EmailConstants;
import com.auraboot.framework.email.model.EmailMessage;
import com.auraboot.framework.email.model.EmailRecordLink;
import com.auraboot.framework.email.service.EmailRecordLinkService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link EmailRecordLinkService}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmailRecordLinkService Unit Tests")
class EmailRecordLinkServiceUnitTest {

    @Mock private EmailRecordLinkMapper linkMapper;
    @Mock private EmailMessageMapper messageMapper;
    @Mock private JdbcTemplate jdbcTemplate;

    private EmailRecordLinkService service;

    @BeforeEach
    void setUp() {
        service = new EmailRecordLinkService(linkMapper, messageMapper, jdbcTemplate, new ObjectMapper());
    }

    @Test
    @DisplayName("manualLink builds and inserts link with type=manual")
    void manualLink_inserts() {
        EmailRecordLink result = service.manualLink(7L, 100L, "thread-1",
                "crm_contact", "REC-1");
        assertThat(result.getTenantId()).isEqualTo(7L);
        assertThat(result.getMessageId()).isEqualTo(100L);
        assertThat(result.getThreadId()).isEqualTo("thread-1");
        assertThat(result.getModelCode()).isEqualTo("crm_contact");
        assertThat(result.getRecordId()).isEqualTo("REC-1");
        assertThat(result.getLinkType()).isEqualTo(EmailConstants.LINK_TYPE_MANUAL);
        assertThat(result.getCreatedAt()).isNotNull();
        verify(linkMapper).insert(any(EmailRecordLink.class));
    }

    @Test
    @DisplayName("removeLink delegates to mapper.deleteById")
    void removeLink_delegates() {
        service.removeLink(99L);
        verify(linkMapper).deleteById(99L);
    }

    @Test
    @DisplayName("autoLink: inbound message with no fromAddress → no work")
    void autoLink_inboundNoSender() {
        EmailMessage m = new EmailMessage();
        m.setId(1L);
        m.setTenantId(7L);
        m.setDirection(EmailConstants.DIRECTION_INBOUND);
        m.setFromAddress(null);
        service.autoLink(m);
        verify(linkMapper, never()).insert(any(EmailRecordLink.class));
    }

    @Test
    @DisplayName("autoLink: inbound matched contact creates link + finds related opportunity")
    void autoLink_inboundContactMatch_withOpportunity() {
        EmailMessage m = new EmailMessage();
        m.setId(11L);
        m.setTenantId(7L);
        m.setDirection(EmailConstants.DIRECTION_INBOUND);
        m.setFromAddress("Sender@Example.com");
        m.setGmailThreadId("THR1");

        // contact lookup → CONTACT-A
        when(jdbcTemplate.queryForList(
                anyString(), eq(String.class), eq(7L), eq("sender@example.com")))
                .thenReturn(List.of("CONTACT-A"))   // first call: contact
                .thenReturn(List.of());              // third call: lead

        // opportunity junction lookup → OPP-1
        when(jdbcTemplate.queryForList(
                anyString(), eq(String.class), eq(7L), eq("CONTACT-A")))
                .thenReturn(List.of("OPP-1"));

        service.autoLink(m);

        // Expect at least 2 inserts (contact + opportunity)
        verify(linkMapper, org.mockito.Mockito.atLeast(2)).insert(any(EmailRecordLink.class));
    }

    @Test
    @DisplayName("autoLink: outbound parses to/cc and matches lead")
    void autoLink_outboundLeadMatch() {
        EmailMessage m = new EmailMessage();
        m.setId(12L);
        m.setTenantId(7L);
        m.setDirection(EmailConstants.DIRECTION_OUTBOUND);
        m.setToAddresses("[\"alice@x.com\"]");
        m.setCcAddresses("[]");

        // contact: empty; opportunity not called; lead: matches
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), anyLong(), anyString()))
                .thenReturn(List.of())   // contact
                .thenReturn(List.of("LEAD-9")); // lead

        service.autoLink(m);
        verify(linkMapper).insert(any(EmailRecordLink.class));
    }

    @Test
    @DisplayName("autoLink: jdbc throws for contact lookup → swallowed, no insert")
    void autoLink_jdbcException_swallowed() {
        EmailMessage m = new EmailMessage();
        m.setId(13L);
        m.setTenantId(7L);
        m.setDirection(EmailConstants.DIRECTION_INBOUND);
        m.setFromAddress("a@b.com");

        when(jdbcTemplate.queryForList(anyString(), eq(String.class), anyLong(), anyString()))
                .thenThrow(new RuntimeException("table missing"));

        service.autoLink(m);
        verify(linkMapper, never()).insert(any(EmailRecordLink.class));
    }

    @Test
    @DisplayName("autoLink: no direct match → inherits thread-level links from siblings")
    void autoLink_inheritsThreadLinks() {
        EmailMessage m = new EmailMessage();
        m.setId(14L);
        m.setTenantId(7L);
        m.setDirection(EmailConstants.DIRECTION_INBOUND);
        m.setFromAddress("nomatch@x.com");
        m.setGmailThreadId("THR-99");

        when(jdbcTemplate.queryForList(anyString(), eq(String.class), anyLong(), anyString()))
                .thenReturn(List.of());

        EmailRecordLink sibling = new EmailRecordLink();
        sibling.setMessageId(20L); // different message
        sibling.setModelCode("crm_lead");
        sibling.setRecordId("LEAD-1");
        when(linkMapper.findByThread(7L, "THR-99")).thenReturn(List.of(sibling));

        service.autoLink(m);
        verify(linkMapper).insert(any(EmailRecordLink.class));
    }

    @Test
    @DisplayName("autoLink: outbound with malformed JSON to-addresses → empty list, no insert")
    void autoLink_malformedJson() {
        EmailMessage m = new EmailMessage();
        m.setId(15L);
        m.setTenantId(7L);
        m.setDirection(EmailConstants.DIRECTION_OUTBOUND);
        m.setToAddresses("not-json{");
        m.setCcAddresses("[]");

        service.autoLink(m);
        verify(linkMapper, never()).insert(any(EmailRecordLink.class));
    }
}
