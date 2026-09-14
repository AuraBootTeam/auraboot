package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.automation.trigger.AutomationTriggerService;
import com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope;
import com.auraboot.framework.plugin.extension.integration.ReliableEventConsumerExtension;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@RequiredArgsConstructor
public class ExternalEventAutomationConsumer implements ReliableEventConsumerExtension {
    private final AutomationTriggerService automationTriggerService;

    @Override
    public String consumerCode() {
        return "open-platform-automation-external-event-v1";
    }

    @Override
    public Set<String> subscribedEventTypes() {
        return Set.of();
    }

    @Override
    public boolean supports(String eventType) {
        return eventType != null && eventType.startsWith("external.");
    }

    @Override
    public void consume(IntegrationEventEnvelope envelope) {
        String sourceCode = envelope.headers().get("sourceCode");
        String externalEventId = envelope.headers().get("externalEventId");
        automationTriggerService.onExternalEvent(sourceCode, envelope.eventType(),
                externalEventId, envelope.subject(), envelope.payload());
    }
}
