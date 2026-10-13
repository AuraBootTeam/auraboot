package com.auraboot.framework.notification.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.notification.service.NotificationDeliveryPlanResolver;
import com.auraboot.framework.notification.service.NotificationDeliveryPlanResolver.Plan;
import com.auraboot.framework.plugin.extension.NotificationDeliveryAccessor;
import com.auraboot.framework.plugin.extension.integration.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Default-off host bridge. Only host-resolved in-app plans can enter the reliable delivery plane. */
@Service
@RequiredArgsConstructor
public class PluginNotificationDeliveryAccessorImpl implements NotificationDeliveryAccessor, ReliableEventConsumerExtension {
    public static final String EVENT_TYPE = "notification.in-app.delivery-requested.v1";
    private final List<NotificationDeliveryPlanResolver> resolvers;
    private final ReliableIntegrationAccessor reliable;
    private final StrictInAppDeliveryWriter writer;
    @Value("${aura.notification.plugin-delivery.enabled:false}") private boolean enabled;

    @Override public Result enqueue(Request request) {
        if (!enabled) return Result.blocked(Reason.DISABLED);
        if (request == null || request.tenantId() <= 0 || blank(request.resolutionReference()) || request.channel() == null)
            return Result.blocked(Reason.INVALID_REQUEST);
        if (!Objects.equals(MetaContext.getCurrentTenantId(), request.tenantId()))
            return Result.blocked(Reason.TENANT_MISMATCH);
        if (request.channel() == Channel.WECHAT_SUBSCRIPTION)
            return Result.blocked(Reason.WECHAT_NOT_CONFIGURED);
        Optional<Plan> resolved = resolve(request.tenantId(), request.resolutionReference());
        if (resolved.isEmpty()) return Result.blocked(Reason.RESOLUTION_UNAVAILABLE);
        Plan plan = resolved.get();
        if (!complete(plan, request.tenantId(), request.resolutionReference()))
            return Result.blocked(Reason.CONFIGURATION_INCOMPLETE);
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("NOTIFICATION_TRANSACTION_REQUIRED");
        writer.requireRecipient(plan);
        IntegrationEventEnvelope event = envelope(plan);
        boolean inserted = reliable.enqueueIdempotent(event);
        return new Result(inserted ? Status.QUEUED : Status.REPLAYED, null, event.eventId());
    }

    @Override public String consumerCode() { return "core.notification.in-app.persistence.v1"; }
    @Override public Set<String> subscribedEventTypes() { return Set.of(EVENT_TYPE); }
    @Override public void consume(IntegrationEventEnvelope event) {
        if (!enabled || !EVENT_TYPE.equals(event.eventType())
                || !Objects.equals(MetaContext.getCurrentTenantId(), event.tenantId()))
            throw new IllegalArgumentException("NOTIFICATION_DELIVERY_UNAVAILABLE");
        Object reference = event.payload().get("resolutionReference");
        if (!(reference instanceof String ref)) throw new IllegalArgumentException("NOTIFICATION_RESOLUTION_REQUIRED");
        Plan plan = resolve(event.tenantId(), ref).filter(p -> complete(p, event.tenantId(), ref))
                .orElseThrow(() -> new IllegalArgumentException("NOTIFICATION_RESOLUTION_UNAVAILABLE"));
        if (!envelope(plan).equals(event)) throw new IllegalArgumentException("NOTIFICATION_ENVELOPE_CONFLICT");
        writer.persist(plan);
    }

    private Optional<Plan> resolve(long tenant, String reference) {
        // Multiple matching resolvers are ambiguous ownership, never choose a default recipient.
        // Only the host classloader may own recipient resolution. PF4J background beans are not policy authorities.
        if (resolvers.stream().anyMatch(r -> r.getClass().getClassLoader() != getClass().getClassLoader()))
            return Optional.empty();
        List<Plan> plans = resolvers.stream().map(r -> r.resolve(tenant, reference)).flatMap(Optional::stream).toList();
        return plans.size() == 1 ? Optional.of(plans.getFirst()) : Optional.empty();
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static boolean complete(Plan p, long tenant, String ref) {
        return p.tenantId() == tenant && ref.equals(p.reference()) && !blank(p.recipientUserPid())
                && !blank(p.configurationVersion()) && !blank(p.sourceType()) && p.sourceType().length() <= 64
                && !blank(p.sourceId()) && p.sourceId().length() <= 64 && p.occurredAt() != null
                && !blank(p.title()) && p.title().length() <= 256 && !blank(p.content());
    }
    private static IntegrationEventEnvelope envelope(Plan p) {
        String key;
        try {
            byte[] identity = (p.tenantId() + "\n" + p.reference() + "\nin_app").getBytes(StandardCharsets.UTF_8);
            key = "notification-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
        // This notification contract stores occurrence at PostgreSQL microsecond precision.
        // Preserve the source plan's timestamp; only the immutable notification envelope truncates sub-microsecond digits.
        return new IntegrationEventEnvelope("1.0", key, EVENT_TYPE, "core.notification", p.sourceId(),
                p.occurredAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                p.tenantId(), key, null, key, 0, Map.of("resolutionReference", p.reference(),
                "recipientUserPid", p.recipientUserPid(), "configurationVersion", p.configurationVersion(),
                "sourceType", p.sourceType(), "sourceId", p.sourceId(), "title", p.title(), "content", p.content()), Map.of());
    }
}
