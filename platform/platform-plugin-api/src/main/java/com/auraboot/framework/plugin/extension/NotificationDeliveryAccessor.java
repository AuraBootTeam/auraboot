package com.auraboot.framework.plugin.extension;

/** Host-verified delivery requests. Recipient/content/provenance are resolved by the host, never payload claims. */
public interface NotificationDeliveryAccessor {
    enum Channel { IN_APP, WECHAT_SUBSCRIPTION }
    enum Status { BLOCKED, QUEUED, REPLAYED }
    enum Reason { DISABLED, INVALID_REQUEST, TENANT_MISMATCH, RESOLUTION_UNAVAILABLE,
        CONFIGURATION_INCOMPLETE, WECHAT_NOT_CONFIGURED }
    record Request(long tenantId, String resolutionReference, Channel channel) {}
    record Result(Status status, Reason reason, String deliveryKey) {
        public static Result blocked(Reason reason) { return new Result(Status.BLOCKED, reason, null); }
    }
    /** Older hosts fail closed, including when callers supply apparently complete requests. */
    default Result enqueue(Request request) { return Result.blocked(Reason.DISABLED); }
}
