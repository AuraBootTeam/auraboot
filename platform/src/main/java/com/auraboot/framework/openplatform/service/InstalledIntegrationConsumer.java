package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.plugin.extension.integration.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.Set;

/** Runs the registered process command inside the reliable worker's existing transaction. */
@Component
@RequiredArgsConstructor
public class InstalledIntegrationConsumer implements ReliableEventConsumerExtension {
    private final InstalledIntegrationService integrations;
    @Override public String consumerCode() { return "open-platform-installed-integration-v1"; }
    @Override public Set<String> subscribedEventTypes() { return integrations.eventTypes(); }
    @Override public void consume(IntegrationEventEnvelope event) { integrations.consume(event); }
}
