package com.auraboot.framework.crm.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.crm.event.InboundEmailEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Inbound email webhook endpoint.
 * Receives parsed email data from external providers (Gmail, SendGrid, etc.)
 * and publishes {@link InboundEmailEvent} for downstream processing
 * (CS Agent, Lead creation, etc.).
 *
 * @since 7.2.0
 */
@Slf4j
@RestController
@RequestMapping("/api/crm/inbound-email")
@RequiredArgsConstructor
@Tag(name = "CRM Inbound Email", description = "Webhook for inbound email processing")
public class InboundEmailController {

    private final ApplicationEventPublisher eventPublisher;

    @PostMapping("/webhook")
    @Operation(summary = "Process inbound email webhook",
            description = "Receives parsed email data and publishes InboundEmailEvent")
    public ApiResponse<Map<String, String>> processWebhook(@RequestBody InboundEmailRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();

        log.info("Inbound email received: from={}, subject={}, tenant={}",
                request.getSenderEmail(), request.getSubject(), tenantId);

        InboundEmailEvent event = new InboundEmailEvent(
                this,
                tenantId,
                request.getAccountId(),
                request.getContactId(),
                request.getSenderEmail(),
                request.getSubject(),
                request.getBody(),
                request.getInboundMessageId()
        );

        eventPublisher.publishEvent(event);

        log.info("InboundEmailEvent published: eventId={}", event.getEventId());
        return ApiResponse.success("Email processed", Map.of("eventId", event.getEventId()));
    }

    @Data
    public static class InboundEmailRequest {
        private String senderEmail;
        private String subject;
        private String body;
        private Long accountId;
        private Long contactId;
        private Long inboundMessageId;
    }
}
