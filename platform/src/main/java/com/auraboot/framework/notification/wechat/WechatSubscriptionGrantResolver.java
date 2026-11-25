package com.auraboot.framework.notification.wechat;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Host-owned authority for an explicit, trusted template consent and recipient resolution.
 * The default-off host implementation requires explicit source and field mappings and verified provider facts.
 * claim atomically creates or reads the same delivery-bound reservation idempotently; it never issues a replacement
 * reservation for a used/unknown delivery reference. This is an attempt fence, not a default consent/quota policy.
 * Implementations must not treat login, phone grants or a posted accept string as subscription consent.
 * The delivery journal persists attempts and crash recovery; consent acquisition and operator resolution remain separate.
 */
public interface WechatSubscriptionGrantResolver {
    Optional<Grant> claim(long tenantId, String resolutionReference);
    /** Re-read a previously reserved immutable fact; absent support blocks durable dispatch. Never creates consent. */
    default Optional<Grant> verifyReserved(long tenantId, String resolutionReference, String reservationReference) {
        return Optional.empty();
    }
    record Grant(long tenantId, String resolutionReference, String userPid, String appId, String identityPid,
                 String openid, String templateId, String grantReference, String reservationReference,
                 Instant expiresAt, Map<String,String> values, Instant reservedAt,
                 @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String miniProgramPath) {
        public Grant(long tenantId,String resolutionReference,String userPid,String appId,String identityPid,String openid,
                String templateId,String grantReference,String reservationReference,Instant expiresAt,Map<String,String> values,
                Instant reservedAt) {
            this(tenantId,resolutionReference,userPid,appId,identityPid,openid,templateId,grantReference,reservationReference,
                    expiresAt,values,reservedAt,null);
        }
        /** Source compatibility for the transport-only seam. Durable enqueue requires a real immutable reservation time. */
        public Grant(long tenantId,String resolutionReference,String userPid,String appId,String identityPid,String openid,
                String templateId,String grantReference,String reservationReference,Instant expiresAt,Map<String,String> values) {
            this(tenantId,resolutionReference,userPid,appId,identityPid,openid,templateId,grantReference,reservationReference,expiresAt,values,null);
        }
        public Grant {
            values = values == null ? Map.of() : Map.copyOf(values);
            if (!com.auraboot.framework.notification.service.NotificationDeliveryPlanResolver.validMiniProgramPath(miniProgramPath))
                throw new IllegalArgumentException("NOTIFICATION_MINI_PROGRAM_PATH_INVALID");
        }
        @Override public String toString() { return "WechatSubscriptionGrant[redacted]"; }
    }
}
