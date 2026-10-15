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
    private final org.springframework.beans.factory.ObjectProvider<OpenApiPublicationService> publications;

    public OpenApiEventCatalog() { this.publications = null; }

    @org.springframework.beans.factory.annotation.Autowired
    public OpenApiEventCatalog(org.springframework.beans.factory.ObjectProvider<OpenApiPublicationService> publications) {
        this.publications = publications;
    }

    public static boolean isModelEvent(String type) {
        return type != null && type.startsWith("open.resource.");
    }

    private Optional<OpenApiResourceContract> dynamic(Long tenant, String type) {
        if (tenant == null || publications == null || !isModelEvent(type)) return Optional.empty();
        int delimiter = type.lastIndexOf('.');
        if (delimiter <= "open.resource.".length()) return Optional.empty();
        String operation = type.substring(delimiter + 1);
        String code = type.substring("open.resource.".length(), delimiter);
        return publications.getObject().active(tenant, code).filter(contract -> contract.events().contains(operation));
    }

    private EventDescriptor descriptor(Long tenant, String type, OpenApiResourceContract contract) {
        String operation = type.substring(type.lastIndexOf('.') + 1);
        Set<Integer> versions = publications.getObject().snapshots(tenant, contract.code()).stream()
                .filter(snapshot -> snapshot.events().contains(operation))
                .map(OpenApiResourceContract::schemaVersion).collect(java.util.stream.Collectors.toSet());
        Set<String> fields = contract.fields().entrySet().stream().filter(entry -> entry.getValue().readable())
                .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
        return new EventDescriptor(type, contract.schemaVersion(), versions, PARTNER, contract.code(), fields);
    }

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
        java.util.ArrayList<EventDescriptor> descriptors = new java.util.ArrayList<>(events.values());
        Long tenant = com.auraboot.framework.application.tenant.MetaContext.exists()
                ? com.auraboot.framework.application.tenant.MetaContext.getCurrentTenantId() : null;
        if (tenant != null && publications != null) {
            for (var contract : publications.getObject().publishedContracts(tenant)) {
                contract.events().forEach(operation -> descriptors.add(descriptor(tenant,
                        "open.resource." + contract.code() + "." + operation, contract)));
            }
        }
        return descriptors.stream().filter(EventDescriptor::externallyDeliverable)
                .sorted(java.util.Comparator.comparing(EventDescriptor::type)).toList();
    }

    public Optional<EventDescriptor> event(String type) {
        var fixed = events.get(type);
        if (fixed != null) return Optional.of(fixed);
        Long tenant = com.auraboot.framework.application.tenant.MetaContext.exists()
                ? com.auraboot.framework.application.tenant.MetaContext.getCurrentTenantId() : null;
        return dynamic(tenant, type).map(contract -> descriptor(tenant, type, contract));
    }

    public EventDescriptor requireExternal(Long tenant, String type, int version) {
        if (!isModelEvent(type)) return requireExternal(type, version);
        var current = dynamic(tenant, type)
                .orElseThrow(() -> new IllegalArgumentException("Event is not published for external delivery"));
        var snapshot = publications.getObject().snapshot(tenant, current.code(), version)
                .filter(contract -> contract.events().contains(type.substring(type.lastIndexOf('.') + 1)))
                .orElseThrow(() -> new IllegalArgumentException("Webhook event schema version is not supported"));
        return descriptor(tenant, type, snapshot);
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
