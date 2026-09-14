package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Versioned allow-list for events that may cross the Open Platform boundary. */
@Component
public class OpenApiEventCatalog {
    public static final String PARTNER = "partner";
    public static final String INTERNAL = "internal";

    private final Map<String, EventDescriptor> events = Map.of(
            "assets.assignment.changed", new EventDescriptor(
                    "assets.assignment.changed", 1, Set.of(1), PARTNER,
                    "assets", Set.of("pid", "assetCode", "status", "assignedTo", "updatedAt")),
            "inventory.stock-in.confirmed", new EventDescriptor(
                    "inventory.stock-in.confirmed", 1, Set.of(1), PARTNER,
                    "inventory.stock-ins", Set.of("pid", "receiptCode", "status", "quantity",
                    "warehousePid", "receiptDate", "updatedAt")),
            "platform.command.executed", new EventDescriptor(
                    "platform.command.executed", 1, Set.of(1), INTERNAL,
                    "internal-command", Set.of()));

    public List<EventDescriptor> externallyPublished() {
        return events.values().stream().filter(EventDescriptor::externallyDeliverable)
                .sorted(java.util.Comparator.comparing(EventDescriptor::type)).toList();
    }

    public Optional<EventDescriptor> event(String type) {
        return Optional.ofNullable(events.get(type));
    }

    public EventDescriptor requireExternal(String type, int version) {
        EventDescriptor descriptor = event(type)
                .filter(EventDescriptor::externallyDeliverable)
                .orElseThrow(() -> new IllegalArgumentException("Event is not published for external delivery"));
        if (!descriptor.supportedVersions().contains(version)) {
            throw new IllegalArgumentException("Webhook event schema version is not supported");
        }
        return descriptor;
    }

    public Map<String, Object> envelope(EventDescriptor descriptor, int version,
                                        String subjectPid, Map<String, Object> source) {
        requireExternal(descriptor.type(), version);
        Map<String, Object> data = new LinkedHashMap<>();
        descriptor.allowedDataFields().forEach(field -> {
            if (source.containsKey(field)) {
                data.put(field, source.get(field));
            }
        });
        String eventId = "evt_" + UniqueIdGenerator.generate();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("id", eventId);
        envelope.put("type", descriptor.type());
        envelope.put("schemaVersion", version);
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("subject", Map.of("type", descriptor.subjectResourceCode(), "pid", subjectPid));
        envelope.put("data", data);
        return envelope;
    }

    public record EventDescriptor(String type, int currentVersion, Set<Integer> supportedVersions,
                                  String classification, String subjectResourceCode,
                                  Set<String> allowedDataFields) {
        public boolean externallyDeliverable() {
            return PARTNER.equals(classification);
        }
    }
}
