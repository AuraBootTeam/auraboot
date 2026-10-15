package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.openplatform.mapper.OpenApiPublicationMapper;
import com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope;
import com.auraboot.framework.webhook.entity.WebhookDeliveryLog;
import com.auraboot.framework.webhook.entity.WebhookSubscription;
import com.auraboot.framework.webhook.service.WebhookDeliveryPolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.*;

/** Source snapshots carry no authority: every enqueue/send resolves current installation grants. */
@Component
@RequiredArgsConstructor
public class OpenApiModelEventDeliveryPolicy implements WebhookDeliveryPolicy {
    private final OpenApiPublicationMapper publications;
    private final ObjectMapper json;
    private final OpenApiPublicationService resourcePublications;
    private final OpenApiPublicationValidator validator;

    @Override public boolean supports(String eventType) { return OpenApiEventCatalog.isModelEvent(eventType); }

    public void validateSource(IntegrationEventEnvelope event) {
        var contract = json.convertValue(event.payload().get("contract"), OpenApiResourceContract.class);
        String operation = String.valueOf(event.payload().get("operation"));
        if (!IntegrationEventEnvelope.VERSION_1.equals(event.schemaVersion()) || !"dynamic-model".equals(event.source())
                || contract == null || !contract.events().contains(operation)
                || !event.eventType().equals("open.resource." + contract.code() + "." + operation)
                || !event.orderingKey().equals("open.resource:" + contract.code() + ":" + event.subject())
                || !contract.code().equals(event.payload().get("resource"))
                || !(event.payload().get("source") instanceof Map<?, ?> source) || !event.subject().equals(source.get("pid"))
                || !(event.payload().get("schemaVersion") instanceof Number schema) || schema.intValue() != contract.schemaVersion()
                || !(event.payload().get("recordVersion") instanceof Number version) || version.longValue() != event.sequence()) {
            throw new IllegalArgumentException("Invalid published model source event");
        }
        String definition = publications.snapshot(event.tenantId(), contract.code(), contract.schemaVersion());
        if (definition == null || !contract.equals(read(definition, OpenApiResourceContract.class)))
            throw new IllegalArgumentException("Published model source snapshot mismatch");
        if (publications.sourceBinding(event.tenantId(), contract.code(), contract.schemaVersion()) == null)
            throw new IllegalArgumentException("Published model source provenance is unavailable");
    }

    @Override
    public Map<String, Object> prepare(WebhookSubscription subscription, WebhookDeliveryLog delivery) {
        require(Boolean.TRUE.equals(subscription.getEnabled()) && subscription.getInstallationPid() != null,
                "Active installation-bound subscription required");
        require(subscription.getTenantId().equals(delivery.getTenantId())
                && subscription.getInstallationPid().equals(delivery.getInstallationPid()), "Delivery identity mismatch");
        require(subscription.getPid().equals(delivery.getSubscriptionPid()), "Delivery subscription mismatch");
        require(delivery.getAuthorizationContext() != null, "Trusted model event source context required");
        var event = read(delivery.getAuthorizationContext(), IntegrationEventEnvelope.class);
        validateSource(event);
        require(IntegrationEventEnvelope.VERSION_1.equals(event.schemaVersion())
                && event.tenantId() == subscription.getTenantId()
                && event.eventType().equals(subscription.getEventType()), "Source event identity mismatch");
        require(delivery.getEventId() == null || delivery.getEventId().equals(event.eventId()), "Delivery event mismatch");
        var emitted = json.convertValue(event.payload().get("contract"), OpenApiResourceContract.class);
        String operation = String.valueOf(event.payload().get("operation"));
        require(emitted.events().contains(operation)
                && event.eventType().equals("open.resource." + emitted.code() + "." + operation)
                && emitted.code().equals(event.payload().get("resource")), "Source publication mismatch");
        require(event.payload().get("source") instanceof Map<?, ?>, "Source record required");
        @SuppressWarnings("unchecked")
        var source = (Map<String, Object>) event.payload().get("source");
        require(event.subject().equals(source.get("pid")), "Source subject mismatch");
        String sourceDefinition = publications.snapshot(event.tenantId(), emitted.code(), emitted.schemaVersion());
        require(sourceDefinition != null && emitted.equals(read(sourceDefinition, OpenApiResourceContract.class)),
                "Source publication snapshot mismatch");
        var authority = publications.eventAuthority(event.tenantId(), subscription.getInstallationPid(), emitted.code());
        require(authority != null, "Published resource installation read authority required");
        var current = read(authority.definition(), OpenApiResourceContract.class);
        var policy = read(authority.policy(), OpenApiInstallationPolicy.class);
        policy.validate(current);
        require(current.events().contains(operation), "Resource event withdrawn");
        require(policy.permitsRow(current, source), "Source row is outside the current installation policy");
        int version = subscription.getEventVersion() == null ? 1 : subscription.getEventVersion();
        require(version <= emitted.schemaVersion(), "Source event predates subscription schema");
        String targetDefinition = publications.snapshot(event.tenantId(), emitted.code(), version);
        require(targetDefinition != null, "Subscribed schema does not exist");
        var target = read(targetDefinition, OpenApiResourceContract.class);
        require(target.events().contains(operation), "Subscribed schema does not publish this event");
        current.requireCompatibleWith(target);
        emitted.requireCompatibleWith(target);
        require(Objects.equals(com.auraboot.framework.application.tenant.MetaContext.getCurrentTenantId(), event.tenantId()),
                "Model event tenant context mismatch");
        try {
            var model = validator.validate(current);
            resourcePublications.requireSourceBinding(event.tenantId(), current, model);
            resourcePublications.requireSourceBinding(event.tenantId(), emitted, model);
        } catch (OpenApiPublicationService.SourceBindingConflictException | IllegalArgumentException invalidSource) {
            // CATCH: source revocation is a terminal authorization decision before network I/O.
            throw new AccessDeniedException("Published model source is unavailable or changed", invalidSource);
        }
        var data = new LinkedHashMap<>(target.project(source));
        data.keySet().retainAll(policy.readableFields());
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("id", event.eventId());
        envelope.put("type", event.eventType());
        envelope.put("schemaVersion", version);
        envelope.put("occurredAt", event.occurredAt().toString());
        envelope.put("subject", Map.of("type", emitted.code(), "pid", event.subject()));
        envelope.put("recordVersion", event.sequence());
        envelope.put("correlationId", event.correlationId());
        envelope.put("data", data);
        return envelope;
    }

    private <T> T read(String value, Class<T> type) {
        try { return json.readerFor(type).with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(value); }
        catch (JsonProcessingException error) { throw new AccessDeniedException("Model event authority context is invalid", error); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AccessDeniedException(message);
    }
}
