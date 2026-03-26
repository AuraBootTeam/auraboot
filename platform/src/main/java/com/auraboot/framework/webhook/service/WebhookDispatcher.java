package com.auraboot.framework.webhook.service;

import java.util.Map;

/**
 * Dispatches events to matching webhook subscriptions.
 *
 * @since 5.1.0
 */
public interface WebhookDispatcher {

    /**
     * Dispatch an event to all matching webhook subscriptions.
     */
    void dispatch(String eventType, Map<String, Object> payload, Long tenantId);
}
