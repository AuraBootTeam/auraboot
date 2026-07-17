package com.auraboot.framework.webhook.service;

import java.util.List;

/**
 * Synchronous webhook dispatch receipts for callers that need to correlate a
 * business action with concrete delivery-log rows.
 */
public record WebhookDispatchResult(List<Receipt> receipts) {

    public WebhookDispatchResult {
        receipts = receipts == null ? List.of() : List.copyOf(receipts);
    }

    public record Receipt(
            String subscriptionPid,
            String deliveryLogPid,
            String eventId,
            String deliveryStatus,
            boolean delivered,
            String errorMessage
    ) {
    }
}
