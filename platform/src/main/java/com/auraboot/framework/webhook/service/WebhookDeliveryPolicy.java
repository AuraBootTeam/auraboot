package com.auraboot.framework.webhook.service;

import com.auraboot.framework.webhook.entity.WebhookDeliveryLog;
import com.auraboot.framework.webhook.entity.WebhookSubscription;
import java.util.Map;

/** Re-evaluates persisted source authority before queueing and every transport attempt. */
public interface WebhookDeliveryPolicy {
    boolean supports(String eventType);
    Map<String, Object> prepare(WebhookSubscription subscription, WebhookDeliveryLog delivery);
}
