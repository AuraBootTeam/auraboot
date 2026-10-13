package com.auraboot.framework.notification.sourcebinding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Test-only additive command appendix. Never modifies a sealed Product source directory. */
public final class ProductQuoteNativeInitialization {
    static final Map<String, List<String>> FIELDS = Map.of(
            "pr_supplier_common", List.of("pr_supplier_code", "pr_supplier_name", "pr_supplier_status"),
            "pr_supplier_pcba_contact", List.of("pr_spc_supplier_id", "pr_spc_name", "qo_pr_contact_state"),
            "qo_procurement_membership_common", List.of("qo_pr_membership_key", "qo_pr_membership_user_pid",
                    "qo_pr_membership_supplier_pid", "qo_pr_membership_contact_pid", "qo_pr_membership_state"),
            "s2p_sourcing_event", List.of("s2p_se_code", "s2p_se_title", "s2p_se_event_type", "s2p_se_status",
                    "s2p_se_collaboration_managed"),
            "s2p_bid", List.of("s2p_bid_sourcing_event_id", "s2p_bid_supplier_id", "s2p_bid_status",
                    "s2p_bid_collaboration_managed", "qo_pr_quote_cutoff_at"));

    private ProductQuoteNativeInitialization() {}

    private static String sha(Path path) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    static String command(String model) {
        if (!FIELDS.containsKey(model)) throw new IllegalArgumentException("Unsupported S06 initialization model");
        return model + ":initialize_s06_native_fixture";
    }

    static void validatePayload(String model, Map<String, Object> facts) {
        if (!FIELDS.containsKey(model) || !facts.keySet().equals(Set.copyOf(FIELDS.get(model)))) {
            throw new IllegalArgumentException("S06 initialization requires its exact declared fields");
        }
    }

    public static final Map<String, List<String>> ORDER_UI_FIELDS = Map.of(
            "pr_supplier_common", List.of("pr_supplier_code", "pr_supplier_name", "pr_supplier_status", "qo_pr_supplier_account_id"),
            "pr_supplier_pcba_contact", FIELDS.get("pr_supplier_pcba_contact"),
            "qo_procurement_membership_common", FIELDS.get("qo_procurement_membership_common"));

    public static String orderUiCommand(String model) {
        if (!ORDER_UI_FIELDS.containsKey(model)) throw new IllegalArgumentException("Unsupported order UI initialization model");
        return model + ":initialize_order_ui_native_fixture";
    }

    public static void validateOrderUiPayload(String model, Map<String, Object> facts) {
        if (!ORDER_UI_FIELDS.containsKey(model) || !facts.keySet().equals(Set.copyOf(ORDER_UI_FIELDS.get(model)))) {
            throw new IllegalArgumentException("Order UI initialization requires exact declared fields");
        }
    }

    public static Map<String, Object> prepareOrderUi(Path original, Path destination, ObjectMapper json) throws Exception {
        return prepare(original, destination, json, ORDER_UI_FIELDS, ProductQuoteNativeInitialization::orderUiCommand,
                "order-ui-native-initialization.json");
    }

    static Map<String, Object> prepare(Path original, Path destination, ObjectMapper json) throws Exception {
        return prepare(original, destination, json, FIELDS, ProductQuoteNativeInitialization::command,
                "s06-native-initialization.json");
    }

    /** Only S06 task/bid facts need this exact source owner appendix; order UI directory facts do not. */
    static Map<String, Object> prepareSourceToPay(Path original, Path destination, ObjectMapper json) throws Exception {
        JsonNode manifest = json.readTree(original.resolve("plugin.json").toFile());
        if (!"com.auraboot.source-to-pay".equals(manifest.path("pluginId").asText())
                || !"s2p".equals(manifest.path("namespace").asText()) || Files.exists(destination)) {
            throw new IllegalArgumentException("S06 requires its exact fresh source-to-pay owner copy");
        }
        Map<String, String> originals = new LinkedHashMap<>();
        try (var paths = Files.walk(original)) {
            for (Path file : paths.sorted().toList()) {
                if (Files.isSymbolicLink(file)) throw new IllegalArgumentException("S06 owner resource cannot be symbolic");
                Path relative = original.relativize(file);
                Path target = destination.resolve(relative);
                if (Files.isDirectory(file)) Files.createDirectories(target);
                else {
                    Files.copy(file, target);
                    originals.put(relative.toString(), sha(file));
                }
            }
        }
        Map<String, Object> writers = new LinkedHashMap<>();
        Map<String, Object> models = new LinkedHashMap<>();
        Map<String, String> markers = Map.of("s2p_sourcing_event", "s2p_se_collaboration_managed",
                "s2p_bid", "s2p_bid_collaboration_managed");
        for (var entry : markers.entrySet()) {
            Path fieldPath = destination.resolve("config/fields/" + entry.getKey() + ".json");
            JsonNode fields = json.readTree(fieldPath.toFile());
            if (!fields.isArray()) throw new IllegalArgumentException("S06 owner fields must be declared arrays");
            ObjectNode marker = null;
            for (JsonNode field : fields) {
                if (entry.getValue().equals(field.path("code").asText())) {
                    if (marker != null || !(field instanceof ObjectNode)) throw new IllegalArgumentException("Ambiguous S06 owner marker");
                    marker = (ObjectNode) field;
                }
            }
            if (marker == null || !"boolean".equals(marker.path("dataType").asText())
                    || !marker.path("immutable").isBoolean() || !marker.path("immutable").booleanValue()
                    || !marker.path("extension").path("systemManaged").isBoolean()
                    || !marker.path("extension").path("systemManaged").booleanValue()) {
                throw new IllegalArgumentException("S06 owner marker protection must remain explicit");
            }
            JsonNode previous = marker.path("allowedWriterCommands").deepCopy();
            ArrayNode next = appendWriter(previous, command(entry.getKey()));
            marker.set("allowedWriterCommands", next);
            Files.writeString(fieldPath, json.writerWithDefaultPrettyPrinter().writeValueAsString(fields));
            writers.put(entry.getValue(), Map.of("productionWriters", previous, "testWriters", next));
        }
        Path modelPath = destination.resolve("config/models.json");
        JsonNode definitions = json.readTree(modelPath.toFile());
        if (!definitions.isArray()) throw new IllegalArgumentException("S06 owner models must be declared arrays");
        for (var entry : markers.entrySet()) {
            ObjectNode found = null;
            for (JsonNode model : definitions) {
                if (entry.getKey().equals(model.path("code").asText())) {
                    if (found != null || !(model instanceof ObjectNode)) throw new IllegalArgumentException("Ambiguous S06 owner model");
                    found = (ObjectNode) model;
                }
            }
            if (found == null || !(found.path("extension").path("recordCommandWriters") instanceof ObjectNode policy)
                    || !entry.getValue().equals(policy.path("field").asText())
                    || !(policy.path("commands") instanceof ObjectNode operations)
                    || !Set.of("create", "update", "delete").equals(json.convertValue(operations, Map.class).keySet())) {
                throw new IllegalArgumentException("S06 owner record policy must remain exact");
            }
            JsonNode previous = policy.deepCopy();
            appendWriter(operations.path("update"), command(entry.getKey())); // Validate without adding an update permit.
            appendWriter(operations.path("delete"), command(entry.getKey()));
            operations.set("create", appendWriter(operations.path("create"), command(entry.getKey())));
            models.put(entry.getKey(), Map.of("productionPolicy", previous, "testPolicy", policy.deepCopy()));
        }
        Files.writeString(modelPath, json.writerWithDefaultPrettyPrinter().writeValueAsString(definitions));
        Map<String, String> generated = new LinkedHashMap<>();
        for (var entry : originals.entrySet()) {
            if (!sha(original.resolve(entry.getKey())).equals(entry.getValue())) throw new IllegalArgumentException("S06 owner source changed");
            generated.put(entry.getKey(), sha(destination.resolve(entry.getKey())));
        }
        return Map.of("sourceDirectory", original.toRealPath().toString(), "testDirectory", destination.toRealPath().toString(),
                "sourceResourceHashes", originals, "generatedResourceHashes", generated,
                "exactWriterAppendix", writers, "exactModelAppendix", models);
    }

    private static ArrayNode appendWriter(JsonNode previous, String command) {
        if (!previous.isArray()) throw new IllegalArgumentException("S06 writers must be explicit arrays");
        ArrayNode next = (ArrayNode) previous.deepCopy();
        Set<String> seen = new java.util.HashSet<>();
        for (JsonNode writer : previous) {
            if (!writer.isTextual() || writer.asText().isBlank() || !seen.add(writer.asText()) || command.equals(writer.asText())) {
                throw new IllegalArgumentException("S06 original writers cannot include synthetic authority");
            }
        }
        next.add(command);
        return next;
    }

    private static Map<String, Object> prepare(Path original, Path destination, ObjectMapper json,
            Map<String, List<String>> fields, java.util.function.Function<String, String> commandCode,
            String appendixName) throws Exception {
        if (Files.exists(destination)) throw new IllegalArgumentException("S06 appendix directory must be fresh");
        Files.createDirectory(destination);
        Map<String, String> originals = new LinkedHashMap<>();
        try (var paths = Files.walk(original)) {
            for (Path file : paths.sorted().toList()) {
                Path relative = original.relativize(file);
                Path target = destination.resolve(relative);
                if (Files.isSymbolicLink(file)) throw new IllegalArgumentException("S06 appendix cannot copy symbolic resources");
                if (Files.isDirectory(file)) Files.createDirectories(target);
                else {
                    Files.copy(file, target);
                    originals.put(relative.toString(), sha(file));
                }
            }
        }
        var writers = new LinkedHashMap<String, Object>();
        try (var paths = Files.walk(destination.resolve("config/fields"))) {
            for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                JsonNode definition = json.readTree(file.toFile());
                if (!(definition instanceof ObjectNode object)) throw new IllegalArgumentException("S06 field resource must be an object");
                String code = object.path("code").asText();
                List<String> models = fields.entrySet().stream().filter(e -> e.getValue().contains(code))
                        .map(Map.Entry::getKey).toList();
                if (models.isEmpty() || !object.has("allowedWriterCommands")) continue;
                JsonNode previous = object.get("allowedWriterCommands").deepCopy();
                if (!previous.isArray()) throw new IllegalArgumentException("S06 protected field writers must be explicit array");
                ArrayNode next = (ArrayNode) previous.deepCopy();
                for (String model : models) {
                    String command = commandCode.apply(model);
                    for (JsonNode writer : next) {
                        if (!writer.isTextual() || writer.asText().equals(command)) throw new IllegalArgumentException("Invalid S06 existing writer");
                    }
                    next.add(command);
                }
                object.set("allowedWriterCommands", next);
                Files.writeString(file, json.writerWithDefaultPrettyPrinter().writeValueAsString(object));
                writers.put(code, Map.of("productionWriters", previous, "testWriters", next));
            }
        }
        ArrayNode commands = json.createArrayNode();
        for (String model : fields.keySet().stream().sorted().toList()) {
            ObjectNode command = commands.addObject();
            command.put("code", commandCode.apply(model));
            command.put("modelCode", model);
            command.put("displayName", "Synthetic S06 initialization only");
            command.put("type", "create");
            command.set("inputFields", json.valueToTree(fields.get(model)));
            command.set("permissions", json.valueToTree(List.of("qo.procurement.integrate")));
            command.put("cmd_risk_level", "L1");
        }
        Path appendix = destination.resolve("config/commands/" + appendixName);
        if (Files.exists(appendix)) throw new IllegalArgumentException("S06 appendix must not replace production commands");
        Files.writeString(appendix, json.writerWithDefaultPrettyPrinter().writeValueAsString(commands));
        for (var source : originals.entrySet()) {
            if (!sha(original.resolve(source.getKey())).equals(source.getValue())) {
                throw new IllegalArgumentException("S06 source changed during appendix preparation");
            }
        }
        Map<String, String> generated = new LinkedHashMap<>();
        try (var paths = Files.walk(destination)) {
            for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                generated.put(destination.relativize(file).toString(), sha(file));
            }
        }
        return Map.of("sourceDirectory", original.toRealPath().toString(), "testDirectory", destination.toRealPath().toString(),
                "sourceResourceHashes", originals, "generatedResourceHashes", generated, "exactWriterAppendix", writers, "commands", commands);
    }
}
