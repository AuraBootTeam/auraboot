package com.auraboot.framework.plugin.extension;

import java.util.Map;

/**
 * Plugin-safe facade for dispatching outbound webhook events through the platform's
 * webhook framework (subscriptions + async delivery + retry + signing + delivery log).
 *
 * <p>Plugins (e.g. the crawler control plane emitting a "crawler.job.completed" event when
 * a crawl finishes — G2) use this instead of depending on the internal WebhookDispatcher.
 * Operators manage which URLs receive an eventType via {@code /api/webhooks}; this accessor
 * only triggers the dispatch.
 *
 * <p>Injected into plugin {@code BackgroundComponentExtension} beans BY TYPE by the host
 * (same mechanism as {@code BackgroundDataAccessor}); declare {@code @Autowired WebhookAccessor}.
 * Dispatch is async + best-effort on the platform side — it never blocks the caller.
 */
public interface WebhookAccessor {

    /**
     * Dispatch {@code eventType} with {@code payload} to all of this tenant's subscriptions
     * for that event type. Returns immediately; delivery is async.
     */
    void dispatch(long tenantId, String eventType, Map<String, Object> payload);
}
