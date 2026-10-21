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
            "pr_supplier_pcba_contact", List.of("pr_spc_supplier_id", "pr_spc_contact_name", "qo_pr_contact_state"),
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
        return model + ":init_order_ui_fixture";
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

    /** Exact test-only directory/material setup; domain task/quote/allocation lifecycle stays public. */
    public static final Map<String, List<String>> SOURCE_UI_FIELDS = Map.of(
            "pr_supplier_common", ORDER_UI_FIELDS.get("pr_supplier_common"),
            "pr_supplier_pcba_contact", FIELDS.get("pr_supplier_pcba_contact"),
            "qo_procurement_membership_common", FIELDS.get("qo_procurement_membership_common"),
            "pr_spend_category", List.of("pr_spc_code", "pr_spc_name"),
            "customer_pcba_int_kingdee_connection", List.of("customer_pcba_kd_profile_name", "customer_pcba_kd_status",
                    "customer_pcba_kd_acct_id", "customer_pcba_kd_base_url", "customer_pcba_kd_app_id",
                    "customer_pcba_kd_username", "customer_pcba_kd_app_secret_ref", "customer_pcba_kd_auth_type"),
            "customer_pcba_int_external_ref", List.of("customer_pcba_ref_external_system", "customer_pcba_ref_instance_key",
                    "customer_pcba_ref_account_id", "customer_pcba_ref_external_form_id", "customer_pcba_ref_external_id",
                    "customer_pcba_ref_local_model_code", "customer_pcba_ref_local_pid"));

    public static String sourceUiCommand(String model) {
        if (!SOURCE_UI_FIELDS.containsKey(model)) throw new IllegalArgumentException("Unsupported source UI initialization model");
        return model + ":init_source_ui_fixture";
    }

    public static void validateSourceUiPayload(String model, Map<String, Object> facts) {
        if (!SOURCE_UI_FIELDS.containsKey(model) || !facts.keySet().equals(Set.copyOf(SOURCE_UI_FIELDS.get(model)))) {
            throw new IllegalArgumentException("Source UI initialization requires exact declared fields");
        }
    }

    public static Map<String, Object> prepareSourceUi(Path original, Path destination, ObjectMapper json) throws Exception {
        return prepare(original, destination, json, SOURCE_UI_FIELDS, ProductQuoteNativeInitialization::sourceUiCommand,
                "source-ui-native-initialization.json");
    }

    public static final String SOURCE_INTEGRATION_OWNER="com.auraboot.customer-pcba-integration";
    /** Select only the formally sealed dependency owner; never derive authority from the fixture payload. */
    public static Path sourceUiIntegrationOwner(List<Path> directories,ObjectMapper json) throws Exception {
        var owners=new java.util.ArrayList<Path>();
        for(Path directory:directories) {
            JsonNode manifest=json.readTree(directory.resolve("plugin.json").toFile());
            if(SOURCE_INTEGRATION_OWNER.equals(manifest.path("pluginId").asText()))owners.add(directory.toRealPath());
        }
        if(owners.size()!=1)throw new IllegalArgumentException("Source UI requires one exact integration owner");
        return owners.getFirst();
    }
    /** Test-only writer amendment on the actual foreign-field owner, with no replacement command definitions. */
    public static Map<String,Object> prepareSourceUiIntegration(Path original,Path destination,ObjectMapper json) throws Exception {
        JsonNode manifest=json.readTree(original.resolve("plugin.json").toFile());
        if(!SOURCE_INTEGRATION_OWNER.equals(manifest.path("pluginId").asText())
                || !"customer_pcba_int".equals(manifest.path("namespace").asText()) || Files.exists(destination))
            throw new IllegalArgumentException("Source UI requires its fresh actual integration owner copy");
        var originals=new LinkedHashMap<String,String>();
        try(var paths=Files.walk(original)) {
            for(Path file:paths.sorted().toList()) {
                if(Files.isSymbolicLink(file))throw new IllegalArgumentException("Source UI owner cannot copy symbolic resources");
                Path target=destination.resolve(original.relativize(file));
                if(Files.isDirectory(file))Files.createDirectories(target);
                else {Files.copy(file,target);originals.put(original.relativize(file).toString(),sha(file));}
            }
        }
        var declaredFields=declaredResources(destination,manifest.path("resourceDirs").path("fields").asText(),json);
        var bindings=declaredResources(destination,manifest.path("resourceDirs").path("modelFieldBindings").asText(),json);
        var audit=new LinkedHashMap<String,Object>();var writers=new LinkedHashMap<String,Object>();
        for(String model:List.of("customer_pcba_int_kingdee_connection","customer_pcba_int_external_ref")) {
            for(String field:SOURCE_UI_FIELDS.get(model)) {
                long bound=bindings.stream().filter(entry->model.equals(entry.node().path("modelCode").asText())
                        && field.equals(entry.node().path("fieldCode").asText())).count();
                var definitions=declaredFields.stream().filter(entry->field.equals(entry.node().path("code").asText())).toList();
                if(bound!=1 || definitions.size()!=1)throw new IllegalArgumentException("Source UI initialization field must have one actual owner binding");
                ObjectNode definition=definitions.getFirst().node();
                if(definition.has("allowedWriterCommands")) {
                    JsonNode prior=definition.get("allowedWriterCommands").deepCopy();
                    if(!prior.isArray())throw new IllegalArgumentException("Source UI owner writers must be explicit array");
                    String command=sourceUiCommand(model);ArrayNode next=(ArrayNode)prior.deepCopy();
                    for(JsonNode writer:next)if(!writer.isTextual() || command.equals(writer.textValue()))
                        throw new IllegalArgumentException("Source UI owner writer cannot be duplicated");
                    next.add(command);definition.set("allowedWriterCommands",next);
                    writers.put(field,Map.of("productionWriters",prior,"testWriters",next));
                }
                audit.put(field,Map.of("modelCode",model,"ownerPluginId",SOURCE_INTEGRATION_OWNER,
                        "resource",destination.relativize(definitions.getFirst().file()).toString(),
                        "protectedWriterDeclared",definition.has("allowedWriterCommands")));
            }
        }
        for(Path file:declaredFields.stream().map(OwnerResource::file).distinct().toList()) {
            JsonNode tree=json.readTree(file.toFile());
            // Replace only the exact declared field objects, preserving unrelated array entries.
            if(tree.isArray())for(int i=0;i<tree.size();i++) {
                final String code=tree.get(i).path("code").asText();
                var replacement=declaredFields.stream().filter(entry->entry.file().equals(file) && code.equals(entry.node().path("code").asText())).findFirst().orElseThrow();
                ((ArrayNode)tree).set(i,replacement.node());
            } else tree=declaredFields.stream().filter(entry->entry.file().equals(file)).findFirst().orElseThrow().node();
            Files.writeString(file,json.writerWithDefaultPrettyPrinter().writeValueAsString(tree));
        }
        for(var entry:originals.entrySet())if(!sha(original.resolve(entry.getKey())).equals(entry.getValue()))
            throw new IllegalArgumentException("Source UI owner changed during appendix preparation");
        var generated=new LinkedHashMap<String,String>();
        try(var paths=Files.walk(destination)) {for(Path file:paths.filter(Files::isRegularFile).sorted().toList())
            generated.put(destination.relativize(file).toString(),sha(file));}
        return Map.of("pluginId",SOURCE_INTEGRATION_OWNER,"sourceDirectory",original.toRealPath().toString(),
                "testDirectory",destination.toRealPath().toString(),"sourceResourceHashes",originals,
                "generatedResourceHashes",generated,"exactWriterAppendix",writers,"initializationFieldAudit",audit);
    }
    private record OwnerResource(Path file,ObjectNode node) {}
    private static List<OwnerResource> declaredResources(Path root,String relative,ObjectMapper json) throws Exception {
        Path resource=root.resolve(relative).normalize();
        if(relative.isBlank() || !resource.startsWith(root) || !Files.exists(resource))
            throw new IllegalArgumentException("Source UI declared owner resource is missing");
        var files=new java.util.ArrayList<Path>();
        if(Files.isDirectory(resource))try(var paths=Files.walk(resource)) {files.addAll(paths.filter(Files::isRegularFile).sorted().toList());}
        else files.add(resource);
        var result=new java.util.ArrayList<OwnerResource>();
        for(Path file:files) {
            JsonNode tree=json.readTree(file.toFile());var entries=tree.isArray()?tree:json.createArrayNode().add(tree);
            for(JsonNode entry:entries) {
                if(!(entry instanceof ObjectNode object))throw new IllegalArgumentException("Source UI owner resource must contain objects");
                result.add(new OwnerResource(file,object));
            }
        }
        return result;
    }

    public static final Map<String,List<String>> ORDER_DIRECTORY_FIELDS=Map.of("pr_spend_category",SOURCE_UI_FIELDS.get("pr_spend_category"),
            "prod_product",List.of("prod_code","prod_name","prod_type","prod_unit","prod_status"),
            "customer_pcba_int_kingdee_connection",SOURCE_UI_FIELDS.get("customer_pcba_int_kingdee_connection"),
            "customer_pcba_int_external_ref",SOURCE_UI_FIELDS.get("customer_pcba_int_external_ref"));
    public static String orderUiDirectoryCommand(String model) {
        if(!ORDER_DIRECTORY_FIELDS.containsKey(model))throw new IllegalArgumentException("Unsupported order directory model");
        return "prod_product".equals(model)?model+":init_order_ui_fixture":sourceUiCommand(model);
    }
    /** One overwrite import must carry both exact initializer families for this owner. */
    public static Map<String,Object> prepareOrderUiComposed(Path original,Path destination,ObjectMapper json) throws Exception {
        var fields=new LinkedHashMap<String,List<String>>(ORDER_UI_FIELDS);fields.putAll(ORDER_DIRECTORY_FIELDS);
        var proof=new LinkedHashMap<String,Object>(prepare(original,destination,json,fields,
                model->ORDER_UI_FIELDS.containsKey(model)?orderUiCommand(model):orderUiDirectoryCommand(model),
                "order-ui-composed-initialization.json"));
        proof.put("compositionKind","order-ui-single-owner-initialization-v1");
        proof.put("initializerFields",fields);
        return proof;
    }

    public static Map<String,Object> prepareOrderUiDirectory(Path original,Path destination,ObjectMapper json) throws Exception {
        return prepare(original,destination,json,ORDER_DIRECTORY_FIELDS,ProductQuoteNativeInitialization::orderUiDirectoryCommand,"order-ui-directory-initialization.json");
    }

    public static final List<String> ORDER_CONNECTION_UPDATE_FIELDS=List.of("customer_pcba_kd_base_url","customer_pcba_kd_username","customer_pcba_kd_app_id");
    public static final String ORDER_CONNECTION_UPDATE_COMMAND="customer_pcba_int_kingdee_connection:update_order_ui_fixture";

    public static void validateOrderConnectionUpdate(Map<String,Object> facts) {
        if(!facts.keySet().equals(Set.copyOf(ORDER_CONNECTION_UPDATE_FIELDS)))
            throw new IllegalArgumentException("Order UI connection update requires its exact three fields");
    }

    /** Same formal foreign owner as Source; only three extra controlled-connection update writers. */
    public static Map<String,Object> prepareOrderUiIntegration(Path original,Path destination,ObjectMapper json) throws Exception {
        var base=prepareSourceUiIntegration(original,destination,json);
        JsonNode manifest=json.readTree(destination.resolve("plugin.json").toFile());
        var fields=declaredResources(destination,manifest.path("resourceDirs").path("fields").asText(),json);
        var bindings=declaredResources(destination,manifest.path("resourceDirs").path("modelFieldBindings").asText(),json);
        var extra=new LinkedHashMap<String,Object>();
        for(String field:ORDER_CONNECTION_UPDATE_FIELDS) {
            var selected=fields.stream().filter(entry->field.equals(entry.node().path("code").asText())).toList();
            long bound=bindings.stream().filter(entry->"customer_pcba_int_kingdee_connection".equals(entry.node().path("modelCode").asText())
                    && field.equals(entry.node().path("fieldCode").asText())).count();
            if(selected.size()!=1 || bound!=1)throw new IllegalArgumentException("Order UI update requires exact actual owner bindings");
            ObjectNode definition=selected.getFirst().node();
            if(definition.has("allowedWriterCommands")) {
                JsonNode previous=definition.get("allowedWriterCommands").deepCopy();
                ArrayNode next=appendWriter(previous,ORDER_CONNECTION_UPDATE_COMMAND);
                definition.set("allowedWriterCommands",next);
                extra.put(field,Map.of("productionWriters",previous,"testWriters",next));
                JsonNode tree=json.readTree(selected.getFirst().file().toFile());
                if(tree.isArray())for(int index=0;index<tree.size();index++) {
                    if(field.equals(tree.get(index).path("code").asText()))((ArrayNode)tree).set(index,definition);
                } else tree=definition;
                Files.writeString(selected.getFirst().file(),json.writerWithDefaultPrettyPrinter().writeValueAsString(tree));
            }
        }
        JsonNode models=json.readTree(destination.resolve(manifest.path("resourceDirs").path("models").asText()).toFile());
        if(!models.isArray())throw new IllegalArgumentException("Order UI integration models must remain an array");
        for(JsonNode model:models)if("customer_pcba_int_kingdee_connection".equals(model.path("code").asText())
                && model.path("extension").has("recordCommandWriters")) {
            JsonNode policy=model.path("extension").path("recordCommandWriters");
            if(!(policy.path("commands") instanceof ObjectNode operations) || !operations.path("update").isArray())
                throw new IllegalArgumentException("Order UI connection record update policy must remain explicit");
            JsonNode prior=operations.path("update").deepCopy();operations.set("update",appendWriter(prior,ORDER_CONNECTION_UPDATE_COMMAND));
            extra.put("$connectionRecordUpdate",Map.of("productionWriters",prior,"testWriters",operations.path("update")));
        }
        Files.writeString(destination.resolve(manifest.path("resourceDirs").path("models").asText()),json.writerWithDefaultPrettyPrinter().writeValueAsString(models));
        var command=json.createObjectNode();command.put("code",ORDER_CONNECTION_UPDATE_COMMAND);command.put("modelCode","customer_pcba_int_kingdee_connection");
        command.put("type","update");command.put("displayName","Synthetic order UI controlled connection only");
        command.set("inputFields",json.valueToTree(ORDER_CONNECTION_UPDATE_FIELDS));command.set("permissions",json.valueToTree(List.of("qo.procurement.integrate")));command.put("cmd_risk_level","L1");
        Path commandFile=destination.resolve(manifest.path("resourceDirs").path("commands").asText()).normalize();
        if(!commandFile.startsWith(destination) || !Files.isRegularFile(commandFile))throw new IllegalArgumentException("Order UI update requires the actual declared command array");
        JsonNode definitions=json.readTree(commandFile.toFile());
        if(!(definitions instanceof ArrayNode commands))throw new IllegalArgumentException("Order UI actual commands must remain an array");
        for(JsonNode existing:commands)if(ORDER_CONNECTION_UPDATE_COMMAND.equals(existing.path("code").asText()))throw new IllegalArgumentException("Order UI update cannot replace an existing command");
        commands.add(command);Files.writeString(commandFile,json.writerWithDefaultPrettyPrinter().writeValueAsString(commands));
        var combined=new LinkedHashMap<String,Object>(base);var writers=new LinkedHashMap<String,Object>();
        @SuppressWarnings("unchecked") var previous=(Map<String,Object>)base.get("exactWriterAppendix");writers.putAll(previous);writers.putAll(extra);
        combined.put("exactWriterAppendix",writers);combined.put("connectionUpdateCommand",command);combined.put("connectionUpdateFields",ORDER_CONNECTION_UPDATE_FIELDS);
        var generated=new LinkedHashMap<String,String>();try(var paths=Files.walk(destination)) {for(Path file:paths.filter(Files::isRegularFile).sorted().toList())generated.put(destination.relativize(file).toString(),sha(file));}
        combined.put("generatedResourceHashes",generated);return combined;
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
    // BEGIN additive automatic-registry fixture.
    static final Map<String, List<String>> AUTOMATIC_FIELDS = Map.of(
            "prod_product", List.of("prod_name", "prod_unit", "prod_type", "prod_status"),
            "pr_purchase_order", List.of("pr_po_code", "pr_po_supplier", "pr_po_date", "pr_po_status",
                    "pr_po_collaboration_managed", "qo_pr_order_state"),
            "pr_purchase_order_line", List.of("pr_pol_order_id", "pr_pol_product_id", "pr_pol_qty",
                    "pr_pol_received_qty", "pr_pol_collaboration_managed", "qo_pr_order_line_submitted_qty"));

    static String automaticCommand(String model) {
        if (!AUTOMATIC_FIELDS.containsKey(model)) throw new IllegalArgumentException("Unsupported automatic fixture model");
        return model + ":initialize_s06_automatic_fixture";
    }
    static void validateAutomaticPayload(String model, Map<String, Object> facts) {
        if (!AUTOMATIC_FIELDS.containsKey(model) || !facts.keySet().equals(Set.copyOf(AUTOMATIC_FIELDS.get(model))))
            throw new IllegalArgumentException("Automatic fixture requires exact fields");
    }
    static Map<String, Object> prepareAutomaticQuoteOwner(Path original, Path destination, ObjectMapper json) throws Exception {
        if (!"com.auraboot.quote-procurement".equals(json.readTree(original.resolve("plugin.json").toFile()).path("pluginId").asText()))
            throw new IllegalArgumentException("Automatic fixture requires the actual quote owner");
        return prepare(original, destination, json, AUTOMATIC_FIELDS, ProductQuoteNativeInitialization::automaticCommand,
                "s06-automatic-native-initialization.json");
    }
    /** Only the actual native owner may amend the two managed policies/markers. */
    static Map<String, Object> prepareAutomaticProcurementOwner(Path original, Path destination, ObjectMapper json) throws Exception {
        JsonNode manifest = json.readTree(original.resolve("plugin.json").toFile());
        if (!"com.auraboot.procurement".equals(manifest.path("pluginId").asText())
                || !"pr".equals(manifest.path("namespace").asText()) || Files.exists(destination))
            throw new IllegalArgumentException("Automatic fixture requires a fresh actual procurement owner");
        var originals = new LinkedHashMap<String, String>();
        try (var paths = Files.walk(original)) {
            for (Path file : paths.sorted().toList()) {
                if (Files.isSymbolicLink(file)) throw new IllegalArgumentException("No symbolic fixture resources");
                Path target = destination.resolve(original.relativize(file));
                if (Files.isDirectory(file)) Files.createDirectories(target);
                else { Files.copy(file, target); originals.put(original.relativize(file).toString(), sha(file)); }
            }
        }
        var markers = Map.of("pr_purchase_order", "pr_po_collaboration_managed",
                "pr_purchase_order_line", "pr_pol_collaboration_managed");
        var audit = new LinkedHashMap<String, Object>();
        for (var entry : markers.entrySet()) {
            Path fieldFile = destination.resolve("config/fields/" + entry.getValue() + ".json");
            JsonNode field = json.readTree(fieldFile.toFile());
            if (!(field instanceof ObjectNode object) || !entry.getValue().equals(field.path("code").asText())
                    || !"boolean".equals(field.path("dataType").asText()) || !field.path("immutable").isBoolean() || !field.path("immutable").booleanValue()
                    || !field.path("extension").path("systemManaged").isBoolean() || !field.path("extension").path("systemManaged").booleanValue())
                throw new IllegalArgumentException("Automatic marker policy changed");
            JsonNode before = field.deepCopy();
            object.set("allowedWriterCommands", appendWriter(field.path("allowedWriterCommands"), automaticCommand(entry.getKey())));
            Files.writeString(fieldFile, json.writerWithDefaultPrettyPrinter().writeValueAsString(field));
            audit.put(entry.getValue(), Map.of("before", before, "after", field.deepCopy()));
        }
        Path modelsFile = destination.resolve("config/models.json");
        JsonNode models = json.readTree(modelsFile.toFile());
        if (!models.isArray()) throw new IllegalArgumentException("Native model resources must be an array");
        var seen = new java.util.HashSet<String>();
        for (JsonNode model : models) {
            String code = model.path("code").asText();
            if (!markers.containsKey(code)) continue;
            if (!seen.add(code) || !(model.path("extension").path("recordCommandWriters") instanceof ObjectNode policy)
                    || !markers.get(code).equals(policy.path("field").asText())
                    || !(policy.path("commands") instanceof ObjectNode operations)
                    || !json.convertValue(operations, Map.class).keySet().equals(Set.of("create", "update", "delete")))
                throw new IllegalArgumentException("Automatic native policy must be exact");
            JsonNode before = policy.deepCopy();
            operations.set("create", appendWriter(operations.path("create"), automaticCommand(code)));
            // checkParent validates the managed parent for a child CREATE using its update list.
            if (code.equals("pr_purchase_order")) operations.set("update",
                    appendWriter(operations.path("update"), automaticCommand("pr_purchase_order_line")));
            audit.put(code, Map.of("before", before, "after", policy.deepCopy()));
        }
        if (!seen.equals(markers.keySet())) throw new IllegalArgumentException("Both native policies are required");
        Files.writeString(modelsFile, json.writerWithDefaultPrettyPrinter().writeValueAsString(models));
        var generated = new LinkedHashMap<String, String>();
        for (var entry : originals.entrySet()) {
            if (!sha(original.resolve(entry.getKey())).equals(entry.getValue())) throw new IllegalArgumentException("Native owner changed");
            generated.put(entry.getKey(), sha(destination.resolve(entry.getKey())));
        }
        return Map.of("sourceDirectory", original.toRealPath().toString(), "testDirectory", destination.toRealPath().toString(),
                "sourceResourceHashes", originals, "generatedResourceHashes", generated, "exactAutomaticAppendix", audit);
    }
    // END additive automatic-registry fixture.
}
