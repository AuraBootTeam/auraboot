package com.auraboot.framework.plugin.pf4j;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exact test-owned create appendices; never substitutes a production command identity. */
final class ProcurementSupplierUiInitialization {
    static final Map<String, List<String>> FIELDS = Map.ofEntries(
            Map.entry("prod_product", List.of("prod_code", "prod_name", "prod_type", "prod_unit", "prod_status")),
            Map.entry("pr_supplier_common", List.of("pr_supplier_code", "pr_supplier_name", "pr_supplier_status", "qo_pr_supplier_account_id")),
            Map.entry("pr_supplier_pcba_contact", List.of("pr_spc_supplier_id", "pr_spc_contact_name", "qo_pr_contact_state")),
            Map.entry("qo_procurement_membership_common", List.of("qo_pr_membership_key", "qo_pr_membership_user_pid", "qo_pr_membership_supplier_pid", "qo_pr_membership_contact_pid", "qo_pr_membership_state")),
            Map.entry("pr_spend_category", List.of("pr_spc_code", "pr_spc_name")),
            Map.entry("customer_pcba_int_kingdee_connection", List.of("customer_pcba_kd_profile_name", "customer_pcba_kd_status", "customer_pcba_kd_acct_id", "customer_pcba_kd_base_url", "customer_pcba_kd_app_id", "customer_pcba_kd_username", "customer_pcba_kd_app_secret_ref", "customer_pcba_kd_auth_type")),
            Map.entry("pr_purchase_request", List.of("pr_preq_code", "pr_preq_product_id", "pr_preq_qty", "pr_preq_source", "pr_preq_source_no", "pr_preq_status", "pr_preq_collaboration_managed", "qo_pr_source_key", "qo_pr_source_task_pid", "qo_pr_source_account_id", "qo_pr_source_external_system", "qo_pr_source_instance_key", "qo_pr_source_requisition_id", "qo_pr_source_entry_id", "qo_pr_source_material_id", "qo_pr_source_material_code", "qo_pr_source_material_name", "qo_pr_source_specification", "qo_pr_source_unit", "qo_pr_source_remaining_qty", "qo_pr_source_reserved_qty", "qo_pr_source_delivery_date", "qo_pr_source_revision", "qo_pr_source_state")),
            Map.entry("pr_purchase_order", List.of("pr_po_code", "pr_po_supplier", "pr_po_date", "pr_po_status", "pr_po_collaboration_managed", "qo_pr_order_account_id", "qo_pr_order_key", "qo_pr_order_erp_id", "qo_pr_order_state", "qo_pr_order_revision", "qo_pr_order_origin", "qo_pr_order_change_pending")),
            Map.entry("pr_purchase_order_line", List.of("pr_pol_order_id", "pr_pol_product_id", "pr_pol_qty", "pr_pol_price", "pr_pol_amount", "pr_pol_received_qty", "pr_pol_collaboration_managed", "qo_pr_order_line_source_pid", "qo_pr_order_line_erp_entry_id", "qo_pr_order_line_submitted_qty", "qo_pr_order_line_material_code", "qo_pr_order_line_material_name", "qo_pr_order_line_specification", "qo_pr_order_line_unit", "qo_pr_order_line_delivery_date")),
            Map.entry("qo_procurement_address_common", List.of("qo_pr_address_supplier_pid", "qo_pr_address_state", "qo_pr_address_address", "qo_pr_address_manager_name", "qo_pr_address_manager_phone")));

    private ProcurementSupplierUiInitialization() { }

    static String command(String model) {
        if (!FIELDS.containsKey(model)) throw new IllegalArgumentException("Unsupported supplier UI fixture model");
        return model + ":init_supplier_ui_fixture";
    }

    static void validatePayload(String model, Map<String, Object> facts) {
        if (!FIELDS.containsKey(model) || !facts.keySet().equals(Set.copyOf(FIELDS.get(model))))
            throw new IllegalArgumentException("Exact supplier UI fixture fields required");
    }

    private static ArrayNode append(JsonNode previous, String code) {
        if (!previous.isArray()) throw new IllegalArgumentException("Explicit production writers required");
        ArrayNode next = (ArrayNode) previous.deepCopy();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode value : previous) {
            if (!value.isTextual() || value.asText().isBlank() || !seen.add(value.asText()) || code.equals(value.asText()))
                throw new IllegalArgumentException("Invalid or preexisting synthetic writer");
        }
        next.add(code);
        return next;
    }

    private static List<ObjectNode> objects(JsonNode root) {
        var result = new ArrayList<ObjectNode>();
        if (root instanceof ObjectNode object) result.add(object);
        else if (root instanceof ArrayNode array) {
            for (JsonNode node : array) {
                if (!(node instanceof ObjectNode object)) throw new IllegalArgumentException("Object resource required");
                result.add(object);
            }
        } else throw new IllegalArgumentException("Unsupported resource shape");
        return result;
    }

    static Map<String, Object> prepare(List<Path> sources, Path destination, ObjectMapper json) throws Exception {
        return prepareOwned(sources, destination, json, FIELDS, ProcurementSupplierUiInitialization::command);
    }

    /** Test-owned families share the exact canonical owner resolver and immutable copy checks. */
    static Map<String, Object> prepareOwned(List<Path> sources, Path destination, ObjectMapper json,
            Map<String, List<String>> fields, java.util.function.Function<String, String> command) throws Exception {
        if (fields.isEmpty() || fields.size() > 16) throw new IllegalArgumentException("Bounded initializer models required");
        var codes = new java.util.HashSet<String>();
        for (var model : fields.entrySet()) {
            String code = command.apply(model.getKey());
            if (!code.startsWith(model.getKey() + ":") || code.length() > 64 || !codes.add(code)
                    || model.getValue().isEmpty() || new java.util.HashSet<>(model.getValue()).size() != model.getValue().size())
                throw new IllegalArgumentException("Exact bounded initializer identity required");
        }
        if (sources.size() != 23 || sources.stream().distinct().count() != 23 || Files.exists(destination))
            throw new IllegalArgumentException("Fresh full twenty-three-owner roster required");
        Files.createDirectory(destination);
        var owners = new LinkedHashMap<String, Path>();
        var fieldDefinitions = new LinkedHashMap<String, Path>();
        var copies = new ArrayList<Map<String, Object>>();
        var fieldProofs = new ArrayList<Map<String, Object>>();
        var modelProofs = new ArrayList<Map<String, Object>>();
        boolean exactOrderParentDeclared = false;
        for (int i = 0; i < sources.size(); i++) {
            Path original = sources.get(i).toRealPath();
            Path copy = destination.resolve("owner-" + i);
            Files.createDirectory(copy);
            Map<String, String> before = resourceRoster(original);
            try (var files = Files.walk(original)) {
                for (Path file : files.sorted().toList()) {
                    if (Files.isSymbolicLink(file)) throw new IllegalArgumentException("Symbolic owner resource denied");
                    Path target = copy.resolve(original.relativize(file));
                    if (Files.isDirectory(file)) Files.createDirectories(target); else Files.copy(file, target);
                }
            }
            var manifest = json.readTree(copy.resolve("plugin.json").toFile());
            String pluginId = manifest.path("pluginId").asText();
            if (pluginId.isBlank()) throw new IllegalArgumentException("Actual resource owner required");
            Path models = copy.resolve("config/models.json");
            if (Files.exists(models)) {
                JsonNode raw = json.readTree(models.toFile());
                for (ObjectNode model : objects(raw)) {
                    String code = model.path("code").asText();
                    if (!fields.containsKey(code)) continue;
                    if ("pr_purchase_order_line".equals(code)) {
                        JsonNode extension = model.path("extension");
                        if (!"pr_purchase_order".equals(extension.path("parentModel").asText())
                                || !"pr_pol_order_id".equals(extension.path("parentField").asText()))
                            throw new IllegalArgumentException("Exact order-line parent declaration required");
                        exactOrderParentDeclared = true;
                    }
                    if (owners.putIfAbsent(code, copy) != null) throw new IllegalArgumentException("Ambiguous model owner");
                    JsonNode policy = model.path("extension").path("recordCommandWriters");
                    if (!policy.isMissingNode()) {
                        if (!(policy.path("commands") instanceof ObjectNode operations)
                                || !Set.of("create", "update", "delete").equals(json.convertValue(operations, Map.class).keySet()))
                            throw new IllegalArgumentException("Exact model writer policy required");
                        JsonNode production = policy.deepCopy();
                        operations.set("create", append(operations.path("create"), command.apply(code)));
                        // The canonical child create guard locks/checks its marked parent as update.
                        // Grant only this synthetic child initializer, never the header initializer.
                        if ("pr_purchase_order".equals(code))
                            operations.set("update", append(operations.path("update"), command.apply("pr_purchase_order_line")));
                        modelProofs.add(Map.of("model", code, "ownerPluginId", pluginId,
                                "productionPolicy", production, "testPolicy", policy.deepCopy()));
                    }
                }
                Files.writeString(models, json.writerWithDefaultPrettyPrinter().writeValueAsString(raw));
            }
            // Native codes can be reused by distinct model owners. Use actual local bindings,
            // never global code uniqueness or last-directory precedence, to choose each edge.
            var bindingsByField = new LinkedHashMap<String, Set<String>>();
            for (Path file : resourceFiles(copy, "bindings")) {
                for (ObjectNode binding : objects(json.readTree(file.toFile()))) {
                    String model = binding.path("modelCode").asText();
                    String field = binding.path("fieldCode").asText();
                    if (fields.containsKey(model) && fields.get(model).contains(field))
                        bindingsByField.computeIfAbsent(field, ignored -> new LinkedHashSet<>()).add(model);
                }
            }
            for (Path file : resourceFiles(copy, "fields")) {
                JsonNode raw = json.readTree(file.toFile());
                boolean changed = false;
                for (ObjectNode field : objects(raw)) {
                    String code = field.path("code").asText();
                    List<String> targets = bindingsByField.getOrDefault(code, Set.of()).stream().sorted().toList();
                    if (targets.isEmpty()) continue;
                    for (String model : targets) {
                        if (fieldDefinitions.putIfAbsent(model + "::" + code, file) != null)
                            throw new IllegalArgumentException("Ambiguous model-bound field contribution");
                    }
                    if (!field.has("allowedWriterCommands")) continue;
                    JsonNode beforeWriters = field.path("allowedWriterCommands").deepCopy();
                    JsonNode next = beforeWriters;
                    for (String model : targets) next = append(next, command.apply(model));
                    field.set("allowedWriterCommands", next);
                    fieldProofs.add(Map.of("field", code, "models", targets, "ownerPluginId", pluginId,
                            "productionWriters", beforeWriters, "testWriters", next));
                    changed = true;
                }
                if (changed) Files.writeString(file, json.writerWithDefaultPrettyPrinter().writeValueAsString(raw));
            }
            copies.add(Map.of("sourceDirectory", original.toString(), "testDirectory", copy.toString(),
                    "pluginId", pluginId, "sourceResourceHashes", before));
        }
        if (fields.containsKey("pr_purchase_order_line") && !exactOrderParentDeclared) throw new IllegalArgumentException("Exact order-line parent declaration missing");
        if (!owners.keySet().equals(fields.keySet())) throw new IllegalArgumentException("All exact model owners required");
        for (var model : fields.entrySet()) for (String field : model.getValue())
            if (!fieldDefinitions.containsKey(model.getKey() + "::" + field))
                throw new IllegalArgumentException("Exact model-bound physical field contribution missing: " + model.getKey() + "::" + field);
        for (var model : fields.entrySet()) {
            Path owner = owners.get(model.getKey());
            var manifest = json.readTree(owner.resolve("plugin.json").toFile());
            JsonNode registered = manifest.path("resourceDirs").path("commands");
            if (!registered.isTextual() || registered.asText().isBlank())
                throw new IllegalArgumentException("Explicit owner command resource registration required");
            Path relative = Path.of(registered.asText());
            Path resource = owner.resolve(relative).normalize();
            if (relative.isAbsolute() || !resource.startsWith(owner) || resource.equals(owner))
                throw new IllegalArgumentException("Owner command resource escapes the copied plugin");
            var definition = json.valueToTree(Map.of(
                    "code", command.apply(model.getKey()), "modelCode", model.getKey(), "displayName", "Synthetic supplier UI initialization",
                    "type", "create", "inputFields", model.getValue(), "permissions", List.of("qo.procurement.integrate"), "cmd_risk_level", "L1"));
            if (Files.isRegularFile(resource) && resource.getFileName().toString().endsWith(".json")) {
                JsonNode current = json.readTree(resource.toFile());
                if (!(current instanceof ArrayNode registeredCommands))
                    throw new IllegalArgumentException("Explicit flat command array required");
                for (JsonNode previous : registeredCommands)
                    if (command.apply(model.getKey()).equals(previous.path("code").asText()))
                        throw new IllegalArgumentException("Initialization cannot replace a production command");
                registeredCommands.add(definition);
                Files.writeString(resource, json.writerWithDefaultPrettyPrinter().writeValueAsString(registeredCommands));
            } else if (Files.isDirectory(resource)) {
                Path file = resource.resolve("supplier-ui-init-" + model.getKey() + ".json");
                if (Files.exists(file)) throw new IllegalArgumentException("Initialization cannot replace a production command");
                Files.writeString(file, json.writerWithDefaultPrettyPrinter().writeValueAsString(List.of(definition)));
            } else {
                throw new IllegalArgumentException("Registered owner command resource must exist");
            }
        }
        var sealed = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> copy : copies) {
            Path original = Path.of(copy.get("sourceDirectory").toString());
            if (!resourceRoster(original).equals(copy.get("sourceResourceHashes"))) throw new IllegalArgumentException("Frozen owner changed");
            var proof = new LinkedHashMap<>(copy);
            proof.put("generatedResourceHashes", resourceRoster(Path.of(copy.get("testDirectory").toString())));
            sealed.add(proof);
        }
        return Map.of("ownerDirectories", sealed, "exactFieldWriterAppendix", fieldProofs,
                "exactModelCreateWriterAppendix", modelProofs, "initializationFields", fields,
                "productionSourceModified", false, "productionUpdateDeleteUnchanged", true);
    }

    /** Both resource forms are supported by the canonical importer (e.g. customer integration). */
    private static List<Path> resourceFiles(Path root, String kind) throws Exception {
        var result = new ArrayList<Path>();
        Path flat = root.resolve("config/" + kind + ".json");
        if (Files.isRegularFile(flat)) result.add(flat);
        Path directory = root.resolve("config/" + kind);
        if (Files.isDirectory(directory)) try (var files = Files.walk(directory)) {
            result.addAll(files.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList());
        }
        return result;
    }

    static Map<String, String> resourceRoster(Path root) throws Exception {
        var result = new LinkedHashMap<String, String>();
        try (var files = Files.walk(root)) {
            for (Path path : files.sorted().toList()) {
                if (Files.isSymbolicLink(path)) throw new IllegalArgumentException("Symbolic resource denied");
                if (Files.isRegularFile(path)) result.put(root.relativize(path).toString(),
                        java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
            }
        }
        return result;
    }
}
