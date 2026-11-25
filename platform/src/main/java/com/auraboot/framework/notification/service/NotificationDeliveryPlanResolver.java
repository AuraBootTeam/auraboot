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
                String sourceType, String sourceId, Instant occurredAt, String title, String content,
                String miniProgramPath, java.util.Map<String, String> fields) {
        public Plan(long tenantId, String reference, String recipientUserPid, String configurationVersion,
                    String sourceType, String sourceId, Instant occurredAt, String title, String content) {
            this(tenantId, reference, recipientUserPid, configurationVersion, sourceType, sourceId, occurredAt, title,
                    content, null, java.util.Map.of());
        }
        public Plan {
            fields = java.util.Map.copyOf(fields);
            if (!validMiniProgramPath(miniProgramPath)) throw new IllegalArgumentException("NOTIFICATION_MINI_PROGRAM_PATH_INVALID");
        }
    }

    static boolean validMiniProgramPath(String path) {
        return path == null || path.length() <= 220 && path.matches(
                "pages/[a-z0-9-]+(?:/[a-z0-9-]+)+\\?kind=[a-z][a-z0-9_-]{0,23}(?:&id=[A-Za-z0-9_-]{1,64})?");
    }
}
