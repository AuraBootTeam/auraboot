package com.auraboot.framework.email;

import com.auraboot.framework.email.mapper.EmailRecordLinkMapper;
import com.auraboot.framework.email.model.EmailConstants;
import com.auraboot.framework.email.model.EmailMessage;
import com.auraboot.framework.email.model.EmailRecordLink;
import com.auraboot.framework.email.service.EmailRecordLinkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

    private EmailRecordLinkService service;

    @BeforeEach
    void setUp() {
        service = new EmailRecordLinkService(linkMapper);
    }

    @Test
    @DisplayName("manualLink builds and inserts link with type=manual")
    void manualLink_inserts() {
        EmailRecordLink result = service.manualLink(7L, 100L, "thread-1",
                "customer_record", "REC-1");
        assertThat(result.getTenantId()).isEqualTo(7L);
        assertThat(result.getMessageId()).isEqualTo(100L);
        assertThat(result.getThreadId()).isEqualTo("thread-1");
        assertThat(result.getModelCode()).isEqualTo("customer_record");
        assertThat(result.getRecordPid()).isEqualTo("REC-1");
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
    @DisplayName("autoLink: no direct match → inherits thread-level links from siblings")
    void autoLink_inheritsThreadLinks() {
        EmailMessage m = new EmailMessage();
        m.setId(14L);
        m.setTenantId(7L);
        m.setDirection(EmailConstants.DIRECTION_INBOUND);
        m.setFromAddress("nomatch@x.com");
        m.setGmailThreadId("THR-99");

        EmailRecordLink sibling = new EmailRecordLink();
        sibling.setMessageId(20L); // different message
        sibling.setModelCode("customer_record");
        sibling.setRecordPid("RECORD-1");
        when(linkMapper.findByThread(7L, "THR-99")).thenReturn(List.of(sibling));

        service.autoLink(m);
        verify(linkMapper).insert(any(EmailRecordLink.class));
    }

}
