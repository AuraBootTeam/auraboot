package com.auraboot.framework.webhook.service;

import com.auraboot.framework.webhook.dto.WebhookCreateRequest;
import com.auraboot.framework.webhook.entity.WebhookDeliveryLog;
import com.auraboot.framework.webhook.entity.WebhookSubscription;

import java.util.List;
import java.util.Map;

/**
 * Service for managing webhook subscriptions.
 *
 * @since 5.1.0
 */
public interface WebhookService {

    WebhookSubscription create(WebhookCreateRequest request);

    WebhookSubscription getByPid(String pid);

    List<WebhookSubscription> listByEventType(String eventType);

    List<WebhookSubscription> listAll();

    WebhookSubscription update(String pid, WebhookCreateRequest request);

    void delete(String pid);

    void enable(String pid);

    void disable(String pid);

    /**
     * Test a webhook by sending a test payload.
     * Dispatches asynchronously; does not return delivery result.
     */
    void testWebhook(String pid, Map<String, Object> testPayload);
}
