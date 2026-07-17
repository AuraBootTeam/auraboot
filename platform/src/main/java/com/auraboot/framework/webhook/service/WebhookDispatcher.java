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

    /**
     * Dispatch an event and return the first-attempt delivery log receipts.
     *
     * <p>Use this only when the caller needs a concrete correlation point, such
     * as rule/action test runs. The regular {@link #dispatch(String, Map, Long)}
     * method remains asynchronous for platform events that should not block the
     * caller.
     */
    WebhookDispatchResult dispatchTracked(String eventType, Map<String, Object> payload, Long tenantId);
}
