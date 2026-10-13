package com.auraboot.framework.notification.wechat;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Host-owned authority for an explicit, trusted template consent and recipient resolution.
 * No production implementation is provided until authorization/recipient rules are supplied.
 * claim must atomically reserve a delivery reference at most once; it must never reissue a used/unknown reservation.
 * Implementations must not treat login, phone grants or a posted accept string as subscription consent.
 * Persistence, crash recovery and operator resolution belong to the future durable attempt boundary.
 */
public interface WechatSubscriptionGrantResolver {
    Optional<Grant> claim(long tenantId, String resolutionReference);
    record Grant(long tenantId, String resolutionReference, String userPid, String appId, String identityPid,
                 String openid, String templateId, String grantReference, String reservationReference,
                 Instant expiresAt, Map<String,String> values) {
        public Grant { values = values == null ? Map.of() : Map.copyOf(values); }
        @Override public String toString() { return "WechatSubscriptionGrant[redacted]"; }
    }
}
