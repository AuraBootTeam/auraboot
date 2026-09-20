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
            "assets", new ResourcePublication("assets", "tasset_asset", 1, Map.ofEntries(
                    Map.entry("pid", "pid"),
                    Map.entry("assetCode", "tasset_as_code"),
                    Map.entry("name", "tasset_as_name"),
                    Map.entry("serialNumber", "tasset_as_serial"),
                    Map.entry("status", "tasset_as_status"),
                    Map.entry("assignedTo", "tasset_as_assigned_to"),
                    Map.entry("location", "tasset_as_location"),
                    Map.entry("updatedAt", "updated_at"))),
            "inventory.stock-ins", new ResourcePublication("inventory.stock-ins", "tinv_stock_in", 1,
                    Map.ofEntries(
                            Map.entry("pid", "pid"),
                            Map.entry("receiptCode", "tinv_si_code"),
                            Map.entry("productPid", "tinv_si_product_id"),
                            Map.entry("warehousePid", "tinv_si_warehouse_id"),
                            Map.entry("quantity", "tinv_si_quantity"),
                            Map.entry("unitCost", "tinv_si_unit_cost"),
                            Map.entry("status", "tinv_si_status"),
                            Map.entry("supplier", "tinv_si_supplier"),
                            Map.entry("receiptDate", "tinv_si_receipt_date"),
                            Map.entry("updatedAt", "updated_at"))));

    private final Map<String, CommandPublication> commands = Map.of(
            "assets.assign", new CommandPublication("assets.assign", "tasset:assign_asset",
                    "tasset.asset.manage", "assets", Map.of("assignee", "tasset_as_assigned_to"),
                    "assets.assignment.changed", 1),
            "inventory.stock-ins.confirm", new CommandPublication("inventory.stock-ins.confirm",
                    "tinv:confirm_stock_in", "tinv.stockin.manage", "inventory.stock-ins", Map.of(),
                    "inventory.stock-in.confirmed", 1));

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

    public long rowVersion(Map<String, Object> record) {
        Object value = record.get("row_version");
        if (value instanceof Number number && number.longValue() > 0) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                long parsed = Long.parseLong(text);
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // handled by the stable failure below
            }
        }
        throw new IllegalStateException("Published resource row version is unavailable");
    }

    public record ResourcePublication(String code, String modelCode, int schemaVersion,
                                      Map<String, String> fields) { }
    public record CommandPublication(String code, String commandCode, String permission,
                                     String resultResourceCode, Map<String, String> inputFields,
                                     String eventType, int eventVersion) { }
}
