package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.webhook.service.WebhookDispatcher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

/** Creates a classified public envelope before it reaches the durable Webhook queue. */
@Service
@RequiredArgsConstructor
public class OpenApiEventPublisher {
    private final OpenApiEventCatalog catalog;
    private final WebhookDispatcher webhookDispatcher;

    public void publish(String eventType, int version, String subjectPid,
                        Map<String, Object> publicResource, Long tenantId) {
        OpenApiEventCatalog.EventDescriptor descriptor = catalog.requireExternal(eventType, version);
        webhookDispatcher.dispatchTracked(eventType,
                catalog.envelope(descriptor, version, subjectPid, publicResource), tenantId);
    }
}
