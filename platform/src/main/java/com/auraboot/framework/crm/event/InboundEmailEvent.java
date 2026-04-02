package com.auraboot.framework.crm.event;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Published when an inbound email is successfully processed by the ingestion pipeline.
 * Downstream listeners (e.g. CustomerServiceAgentListener) use this to trigger automated processing.
 */
@Getter
public class InboundEmailEvent extends ApplicationEvent {

    private final String eventId;
    private final Long tenantId;
    private final Long accountId;       // resolved customer account (nullable if unrecognized sender)
    private final Long contactId;       // resolved contact (nullable)
    private final String senderEmail;
    private final String emailSubject;
    private final String emailBody;
    private final Long inboundMessageId; // FK to ab_inbound_message.id

    public InboundEmailEvent(Object source, Long tenantId, Long accountId, Long contactId,
                             String senderEmail, String emailSubject, String emailBody,
                             Long inboundMessageId) {
        super(source);
        this.eventId = UniqueIdGenerator.generate();
        this.tenantId = tenantId;
        this.accountId = accountId;
        this.contactId = contactId;
        this.senderEmail = senderEmail;
        this.emailSubject = emailSubject;
        this.emailBody = emailBody;
        this.inboundMessageId = inboundMessageId;
    }
}
