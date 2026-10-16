package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope;
import com.auraboot.framework.plugin.extension.integration.ReliableEventConsumerExtension;
import com.auraboot.framework.webhook.mapper.WebhookSubscriptionMapper;
import com.auraboot.framework.webhook.service.WebhookDispatcher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.Set;

/** Durable webhook inserts and the reliable consumer receipt commit in the same transaction. */
@Component
@RequiredArgsConstructor
public class OpenApiModelEventConsumer implements ReliableEventConsumerExtension {
    private final WebhookSubscriptionMapper subscriptions;
    private final WebhookDispatcher dispatcher;
    private final ObjectMapper json;
    private final OpenApiModelEventDeliveryPolicy policy;

    @Override public String consumerCode() { return "open-platform-model-webhook-v1"; }
    @Override public Set<String> subscribedEventTypes() { return Set.of(); }
    @Override public boolean supports(String type) { return OpenApiEventCatalog.isModelEvent(type); }

    @Override public void consume(IntegrationEventEnvelope event) {
        policy.validateSource(event);
        final String context;
        try { context = json.writeValueAsString(event); }
        catch (JsonProcessingException error) { throw new IllegalArgumentException("Model source event cannot be persisted", error); }
        String previous = MDC.get("requestId");
        MDC.put("requestId", event.correlationId());
        try {
            for (var subscription : subscriptions.findByEventType(event.tenantId(), event.eventType())) {
                // Legacy tenant subscriptions never receive a model source snapshot.
                if (subscription.getInstallationPid() == null) continue;
                try {
                    dispatcher.dispatchPublished(subscription, Map.of(), context);
                } catch (AccessDeniedException denied) {
                    // Revocation/invisible rows are an expected per-installation terminal decision.
                    // Other failures propagate so queue inserts and the consumer receipt roll back.
                }
            }
        } finally {
            if (previous == null) MDC.remove("requestId"); else MDC.put("requestId", previous);
        }
    }
}
