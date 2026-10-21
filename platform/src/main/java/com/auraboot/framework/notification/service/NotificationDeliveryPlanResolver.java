package com.auraboot.framework.notification.service;

import java.time.Instant;
import java.util.Optional;

/**
 * Host-owned resolution seam. No production recipient policy is registered by this slice.
 * Implementations must resolve an authorized immutable source fact and configuration, not echo caller claims.
 */
public interface NotificationDeliveryPlanResolver {
    Optional<Plan> resolve(long tenantId, String reference);
    record Plan(long tenantId, String reference, String recipientUserPid, String configurationVersion,
                String sourceType, String sourceId, Instant occurredAt, String title, String content) {}
}
