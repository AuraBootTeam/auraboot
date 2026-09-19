package com.auraboot.framework.openplatform.service;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Explicit allow-list mapping stable external names to internal DSL definitions. */
@Component
public class OpenApiPublicationRegistry {
    private final Map<String, ResourcePublication> resources = Map.of(
            "assets", new ResourcePublication("assets", "tasset_asset", Map.ofEntries(
                    Map.entry("pid", "pid"),
                    Map.entry("assetCode", "tasset_as_code"),
                    Map.entry("name", "tasset_as_name"),
                    Map.entry("serialNumber", "tasset_as_serial"),
                    Map.entry("status", "tasset_as_status"),
                    Map.entry("assignedTo", "tasset_as_assigned_to"),
                    Map.entry("location", "tasset_as_location"),
                    Map.entry("updatedAt", "updated_at"))));

    private final Map<String, CommandPublication> commands = Map.of(
            "assets.assign", new CommandPublication("assets.assign", "tasset:assign_asset",
                    "tasset.asset.manage", "assets", Map.of("assignee", "tasset_as_assigned_to")));

    public Optional<ResourcePublication> resource(String code) {
        return Optional.ofNullable(resources.get(code));
    }

    public Optional<CommandPublication> command(String code) {
        return Optional.ofNullable(commands.get(code));
    }

    public Map<String, Object> project(ResourcePublication publication, Map<String, Object> record) {
        Map<String, Object> projected = new LinkedHashMap<>();
        publication.fields().forEach((external, internal) -> projected.put(external, record.get(internal)));
        return projected;
    }

    public Map<String, Object> mapInput(CommandPublication publication, Map<String, Object> input) {
        Map<String, Object> mapped = new LinkedHashMap<>();
        input.forEach((external, value) -> {
            String internal = publication.inputFields().get(external);
            if (internal == null) {
                throw new IllegalArgumentException("Unknown command input field: " + external);
            }
            mapped.put(internal, value);
        });
        List<String> missing = publication.inputFields().keySet().stream()
                .filter(field -> !input.containsKey(field)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Missing command input field: " + String.join(",", missing));
        }
        return mapped;
    }

    public record ResourcePublication(String code, String modelCode, Map<String, String> fields) { }
    public record CommandPublication(String code, String commandCode, String permission,
                                     String resultResourceCode, Map<String, String> inputFields) { }
}
