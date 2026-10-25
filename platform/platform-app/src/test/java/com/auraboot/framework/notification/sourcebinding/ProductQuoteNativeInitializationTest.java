package com.auraboot.framework.notification.sourcebinding;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Hermetic appendix authority checks; no native command execution is claimed. */
class ProductQuoteNativeInitializationTest {
    @TempDir Path root;
    private final ObjectMapper json = new ObjectMapper();

    private Path original() throws Exception {
        Path source = Files.createDirectory(root.resolve("original"));
        Files.createDirectories(source.resolve("config/fields"));
        Files.createDirectories(source.resolve("config/commands"));
        Files.writeString(source.resolve("plugin.json"), "{\"pluginId\":\"com.auraboot.quote-procurement\"}");
        Files.writeString(source.resolve("config/fields/contact.json"), "{\"code\":\"qo_pr_contact_state\",\"allowedWriterCommands\":[\"pr_supplier_pcba_contact:sync_directory\"],\"defaultValue\":\"inactive\"}");
        Files.writeString(source.resolve("config/fields/unrelated.json"), "{\"code\":\"other_protected\",\"allowedWriterCommands\":[]}");
        Files.writeString(source.resolve("config/commands/formal.json"), "[{\"code\":\"formal-command\"}]");
        return source;
    }

    @Test void appendixPreservesProductionWritersCommandsAndOriginalBytes() throws Exception {
        Path source = original();
        String original = Files.readString(source.resolve("config/fields/contact.json"));
        Path copy = root.resolve("appendix");
        var proof = ProductQuoteNativeInitialization.prepare(source, copy, json);
        assertThat(Files.readString(source.resolve("config/fields/contact.json"))).isEqualTo(original);
        var field = json.readTree(copy.resolve("config/fields/contact.json").toFile());
        assertThat(field.path("allowedWriterCommands").toString()).isEqualTo(
                "[\"pr_supplier_pcba_contact:sync_directory\",\"pr_supplier_pcba_contact:initialize_s06_native_fixture\"]");
        assertThat(field.path("defaultValue").asText()).isEqualTo("inactive");
        assertThat(Files.readAllBytes(copy.resolve("config/commands/formal.json"))).isEqualTo(Files.readAllBytes(source.resolve("config/commands/formal.json")));
        assertThat(Files.readAllBytes(copy.resolve("config/fields/unrelated.json"))).isEqualTo(Files.readAllBytes(source.resolve("config/fields/unrelated.json")));
        var commands = json.readTree(copy.resolve("config/commands/s06-native-initialization.json").toFile());
        assertThat(commands).hasSize(5);
        for (var command : commands) {
            assertThat(command.path("type").asText()).isEqualTo("create");
            assertThat(command.path("permissions").toString()).isEqualTo("[\"qo.procurement.integrate\"]");
            assertThat(command.path("code").asText()).isEqualTo(ProductQuoteNativeInitialization.command(command.path("modelCode").asText()));
        }
        assertThat(proof).containsKeys("sourceResourceHashes", "exactWriterAppendix", "commands");
    }

    @Test void missingExtraFieldsUnsupportedModelAndReusedDirectoryCannotAcquireInitialization() throws Exception {
        assertThatThrownBy(() -> ProductQuoteNativeInitialization.validatePayload("pr_supplier_common", Map.of("pr_supplier_code", "x"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProductQuoteNativeInitialization.validatePayload("pr_supplier_common", Map.of(
                "pr_supplier_code", "x", "pr_supplier_name", "x", "pr_supplier_status", "enabled", "extra", "x"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProductQuoteNativeInitialization.command("unknown_model")).isInstanceOf(IllegalArgumentException.class);
        Path source = original();
        Path copy = Files.createDirectory(root.resolve("used"));
        assertThatThrownBy(() -> ProductQuoteNativeInitialization.prepare(source, copy, json)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void malformedWriterDeclarationAndPreexistingAppendixFailClosed() throws Exception {
        Path source = original();
        Files.writeString(source.resolve("config/fields/contact.json"), "{\"code\":\"qo_pr_contact_state\",\"allowedWriterCommands\":\"ALL\"}");
        assertThatThrownBy(() -> ProductQuoteNativeInitialization.prepare(source, root.resolve("malformed"), json)).isInstanceOf(IllegalArgumentException.class);
        Files.writeString(source.resolve("config/fields/contact.json"), "{\"code\":\"qo_pr_contact_state\",\"allowedWriterCommands\":[\"pr_supplier_pcba_contact:initialize_s06_native_fixture\"]}");
        assertThatThrownBy(() -> ProductQuoteNativeInitialization.prepare(source, root.resolve("already"), json)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void orderUiAppendixUsesIndependentCommandsWithoutClaimingVerifiedPhone() throws Exception {
        Path source = original();
        Path copy = root.resolve("order-ui");
        ProductQuoteNativeInitialization.prepareOrderUi(source, copy, json);
        var commands = json.readTree(copy.resolve("config/commands/order-ui-native-initialization.json").toFile());
        assertThat(commands).hasSize(3);
        for (var command : commands) {
            assertThat(command.path("code").asText()).endsWith(":init_order_ui_fixture")
                    .doesNotContain("bind_verified_phone", "sync_directory");
            assertThat(command.path("code").asText().length()).isLessThanOrEqualTo(64);
        }
        assertThatThrownBy(() -> ProductQuoteNativeInitialization.validateOrderUiPayload("pr_supplier_common", Map.of(
                "pr_supplier_code", "x", "pr_supplier_name", "x", "pr_supplier_status", "enabled")))
                .isInstanceOf(IllegalArgumentException.class);
        ProductQuoteNativeInitialization.validateOrderUiPayload("pr_supplier_common", Map.of(
                "pr_supplier_code", "x", "pr_supplier_name", "x", "pr_supplier_status", "enabled", "qo_pr_supplier_account_id", "x"));
    }


    @Test void singleOwnerCompositionPreservesBothWriterFamiliesAcrossPolicyCopies() throws Exception {
        Path source=original();
        Files.writeString(source.resolve("config/fields/membership.json"),"{\"code\":\"qo_pr_membership_state\",\"allowedWriterCommands\":[]}");
        String contactOriginal=Files.readString(source.resolve("config/fields/contact.json"));
        var proof=ProductQuoteNativeInitialization.prepareOrderUiComposed(source,root.resolve("composed"),json);
        Path composed=Path.of(proof.get("testDirectory").toString());
        assertThat(proof.get("sourceDirectory")).isEqualTo(source.toRealPath().toString());
        assertThat(proof.get("compositionKind")).isEqualTo("order-ui-single-owner-initialization-v1");
        assertThat(json.readTree(composed.resolve("config/commands/order-ui-composed-initialization.json").toFile())).hasSize(7);
        assertThat(json.readTree(composed.resolve("config/fields/contact.json").toFile()).path("allowedWriterCommands").toString())
            .isEqualTo("[\"pr_supplier_pcba_contact:sync_directory\",\"pr_supplier_pcba_contact:init_order_ui_fixture\"]");
        assertThat(json.readTree(composed.resolve("config/fields/membership.json").toFile()).path("allowedWriterCommands").toString())
            .isEqualTo("[\"qo_procurement_membership_common:init_order_ui_fixture\"]");
        assertThat(Files.readString(source.resolve("config/fields/contact.json"))).isEqualTo(contactOriginal);
        Path policy=Files.createDirectory(root.resolve("policy-copy"));
        try(var files=Files.walk(composed)){for(Path file:files.toList()){
            Path target=policy.resolve(composed.relativize(file));if(Files.isDirectory(file))Files.createDirectories(target);else Files.copy(file,target);
        }}
        assertThat(Files.readAllBytes(policy.resolve("config/fields/contact.json"))).isEqualTo(Files.readAllBytes(composed.resolve("config/fields/contact.json")));
        assertThat(Files.readAllBytes(policy.resolve("config/fields/membership.json"))).isEqualTo(Files.readAllBytes(composed.resolve("config/fields/membership.json")));
        assertThat(Files.readAllBytes(policy.resolve("config/commands/order-ui-composed-initialization.json"))).isEqualTo(Files.readAllBytes(composed.resolve("config/commands/order-ui-composed-initialization.json")));
    }

    @Test void orderDirectoryUsesBoundedExactModelCommandsAndConnectionUpdateOnlyThreeFacts() {
        assertThat(ProductQuoteNativeInitialization.orderUiDirectoryCommand("prod_product")).isEqualTo("prod_product:init_order_ui_fixture");
        for(String model:ProductQuoteNativeInitialization.ORDER_DIRECTORY_FIELDS.keySet()) {
            assertThat(ProductQuoteNativeInitialization.orderUiDirectoryCommand(model)).startsWith(model+":");
            assertThat(ProductQuoteNativeInitialization.orderUiDirectoryCommand(model).length()).isLessThanOrEqualTo(64);
        }
        assertThatThrownBy(()->ProductQuoteNativeInitialization.orderUiDirectoryCommand("qo_procurement_task_common")).isInstanceOf(IllegalArgumentException.class);
        ProductQuoteNativeInitialization.validateOrderConnectionUpdate(Map.of("customer_pcba_kd_base_url","http://127.0.0.1:52169", "customer_pcba_kd_username","test", "customer_pcba_kd_app_id","app"));
        assertThatThrownBy(()->ProductQuoteNativeInitialization.validateOrderConnectionUpdate(Map.of("customer_pcba_kd_base_url","http://127.0.0.1:52169"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ProductQuoteNativeInitialization.validateOrderConnectionUpdate(Map.of("customer_pcba_kd_base_url","http://127.0.0.1:52169", "customer_pcba_kd_username","test", "customer_pcba_kd_app_id","app","customer_pcba_kd_status","enabled"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(ProductQuoteNativeInitialization.ORDER_CONNECTION_UPDATE_COMMAND.length()).isLessThanOrEqualTo(64);
    }

    @Test void sourceUiCommandsFitExistingChangeLogWidthWithoutChangingTheirModelPrefix() {
        assertThat(ProductQuoteNativeInitialization.SOURCE_UI_FIELDS).hasSize(6);
        for (String model : new java.util.TreeSet<>(ProductQuoteNativeInitialization.SOURCE_UI_FIELDS.keySet())) {
            String code = ProductQuoteNativeInitialization.sourceUiCommand(model);
            // ab_data_change_log.command_code retains its canonical VARCHAR(64) schema.
            assertThat(code.length()).as("Persisted audit command width for %s", model).isLessThanOrEqualTo(64);
            assertThat(code).startsWith(model + ":").endsWith(":init_source_ui_fixture");
        }
    }

    private Path sourceOwner() throws Exception {
        Path source = Files.createDirectory(root.resolve("owner"));
        Files.createDirectories(source.resolve("config/fields"));
        Files.createDirectories(source.resolve("config/commands"));
        Files.writeString(source.resolve("plugin.json"), "{\"pluginId\":\"com.auraboot.source-to-pay\",\"namespace\":\"s2p\"}");
        var definitions = json.createArrayNode();
        for (String model : java.util.List.of("s2p_sourcing_event", "s2p_bid")) {
            String marker = model.equals("s2p_bid") ? "s2p_bid_collaboration_managed" : "s2p_se_collaboration_managed";
            var fields = json.createArrayNode();
            var field = fields.addObject();
            field.put("code", marker).put("dataType", "boolean").put("immutable", true).put("defaultValue", false);
            field.putObject("extension").put("systemManaged", true);
            field.putArray("allowedWriterCommands").add("formal:create");
            Files.writeString(source.resolve("config/fields/" + model + ".json"), fields.toString());
            var definition = definitions.addObject();
            definition.put("code", model);
            var policy = definition.putObject("extension").putObject("recordCommandWriters");
            policy.put("field", marker);
            var operations = policy.putObject("commands");
            operations.putArray("create").add("formal:create");
            operations.putArray("update").add("formal:update");
            operations.putArray("delete");
        }
        Files.writeString(source.resolve("config/models.json"), definitions.toString());
        Files.writeString(source.resolve("config/commands/formal.json"), "[]");
        return source;
    }

    @Test void exactOwnerCopyAddsOnlyItsTwoFieldAndCreatePermits() throws Exception {
        Path source = sourceOwner();
        String original = Files.readString(source.resolve("config/models.json"));
        Path copy = root.resolve("owner-copy");
        var proof = ProductQuoteNativeInitialization.prepareSourceToPay(source, copy, json);
        assertThat(Files.readString(source.resolve("config/models.json"))).isEqualTo(original);
        var models = json.readTree(copy.resolve("config/models.json").toFile());
        for (var model : models) {
            var operations = model.path("extension").path("recordCommandWriters").path("commands");
            assertThat(operations.path("create").toString()).isEqualTo("[\"formal:create\",\"" +
                    ProductQuoteNativeInitialization.command(model.path("code").asText()) + "\"]");
            assertThat(operations.path("update").toString()).isEqualTo("[\"formal:update\"]");
            assertThat(operations.path("delete").toString()).isEqualTo("[]");
            var field = json.readTree(copy.resolve("config/fields/" + model.path("code").asText() + ".json").toFile()).get(0);
            assertThat(field.path("allowedWriterCommands")).hasSize(2);
            assertThat(field.path("immutable").booleanValue()).isTrue();
            assertThat(field.path("extension").path("systemManaged").booleanValue()).isTrue();
            assertThat(field.path("defaultValue").booleanValue()).isFalse();
        }
        assertThat(Files.readAllBytes(copy.resolve("config/commands/formal.json")))
                .isEqualTo(Files.readAllBytes(source.resolve("config/commands/formal.json")));
        assertThat(proof).containsKeys("exactWriterAppendix", "exactModelAppendix", "sourceResourceHashes", "generatedResourceHashes");
    }

    @Test void wrongOwnerMissingProtectionAndMissingModelPolicyFailClosed() throws Exception {
        Path source = sourceOwner();
        Files.writeString(source.resolve("plugin.json"), "{\"pluginId\":\"other\",\"namespace\":\"s2p\"}");
        assertThatThrownBy(() -> ProductQuoteNativeInitialization.prepareSourceToPay(source, root.resolve("wrong"), json))
                .isInstanceOf(IllegalArgumentException.class);
        Files.writeString(source.resolve("plugin.json"), "{\"pluginId\":\"com.auraboot.source-to-pay\",\"namespace\":\"s2p\"}");
        String fields = Files.readString(source.resolve("config/fields/s2p_bid.json"));
        Files.writeString(source.resolve("config/fields/s2p_bid.json"), fields.replace("\"immutable\":true", "\"immutable\":false"));
        assertThatThrownBy(() -> ProductQuoteNativeInitialization.prepareSourceToPay(source, root.resolve("unprotected"), json))
                .isInstanceOf(IllegalArgumentException.class);
        Files.writeString(source.resolve("config/fields/s2p_bid.json"), "[]");
        assertThatThrownBy(() -> ProductQuoteNativeInitialization.prepareSourceToPay(source, root.resolve("missing-marker"), json))
                .isInstanceOf(IllegalArgumentException.class);
        Files.writeString(source.resolve("config/fields/s2p_bid.json"), fields.replace("allowedWriterCommands", "missingWriters"));
        assertThatThrownBy(() -> ProductQuoteNativeInitialization.prepareSourceToPay(source, root.resolve("missing-writers"), json))
                .isInstanceOf(IllegalArgumentException.class);
        Files.writeString(source.resolve("config/fields/s2p_bid.json"), fields);
        String models = Files.readString(source.resolve("config/models.json"));
        Files.writeString(source.resolve("config/models.json"), models.replace("recordCommandWriters", "missingPolicy"));
        assertThatThrownBy(() -> ProductQuoteNativeInitialization.prepareSourceToPay(source, root.resolve("missing-policy"), json))
                .isInstanceOf(IllegalArgumentException.class);
        Files.writeString(source.resolve("config/models.json"), models.replace("formal:create", "s2p_bid:initialize_s06_native_fixture"));
        assertThatThrownBy(() -> ProductQuoteNativeInitialization.prepareSourceToPay(source, root.resolve("injected"), json))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Path integration(String name,boolean directory) throws Exception {
        Path source=Files.createDirectory(root.resolve(name));Files.createDirectories(source.resolve("config"));
        var manifest=json.createObjectNode().put("pluginId",ProductQuoteNativeInitialization.SOURCE_INTEGRATION_OWNER)
                .put("namespace","customer_pcba_int");
        manifest.putObject("resourceDirs").put("fields",directory?"config/fields":"config/fields.json")
                .put("modelFieldBindings","config/bindings.json");
        Files.writeString(source.resolve("plugin.json"),manifest.toString());
        var fields=json.createArrayNode();var bindings=json.createArrayNode();
        for(String model:java.util.List.of("customer_pcba_int_kingdee_connection","customer_pcba_int_external_ref"))
            for(String code:ProductQuoteNativeInitialization.SOURCE_UI_FIELDS.get(model)) {
                var field=fields.addObject().put("code",code).put("dataType","string");
                if(java.util.Set.of("customer_pcba_ref_instance_key","customer_pcba_ref_account_id").contains(code)) {
                    field.putObject("extension").put("systemManaged",true);
                    field.putArray("allowedWriterCommands").add("qo_procurement_task_common:sync_sources");
                }
                bindings.addObject().put("modelCode",model).put("fieldCode",code);
            }
        fields.addObject().put("code","customer_pcba_ref_identity_key").putArray("allowedWriterCommands")
                .add("qo_procurement_task_common:sync_sources");
        if(directory) {
            Files.createDirectory(source.resolve("config/fields"));
            for(var field:fields)Files.writeString(source.resolve("config/fields/"+field.path("code").asText()+".json"),field.toString());
        } else Files.writeString(source.resolve("config/fields.json"),fields.toString());
        Files.writeString(source.resolve("config/bindings.json"),bindings.toString());
        Files.writeString(source.resolve("config/commands.json"),"[{\"code\":\"customer_pcba_int:existing\"}]");
        return source;
    }
    @Test void integrationOwnerArrayAndDirectoryDeclarationsAmendOnlyTheTwoProtectedPayloadFields() throws Exception {
        for(boolean directory:java.util.List.of(false,true)) {
            Path source=integration("owner-"+directory,directory),copy=root.resolve("copy-"+directory);
            String manifest=Files.readString(source.resolve("plugin.json")),commands=Files.readString(source.resolve("config/commands.json"));
            var proof=ProductQuoteNativeInitialization.prepareSourceUiIntegration(source,copy,json);
            assertThat((Map<?,?>)proof.get("initializationFieldAudit")).hasSize(15);
            var writers=(Map<?,?>)proof.get("exactWriterAppendix");
            assertThat(writers.keySet().stream().map(Object::toString).toList()).containsExactlyInAnyOrder("customer_pcba_ref_instance_key","customer_pcba_ref_account_id");
            assertThat(Files.readString(source.resolve("plugin.json"))).isEqualTo(manifest);
            assertThat(Files.readString(copy.resolve("plugin.json"))).isEqualTo(manifest);
            assertThat(Files.readString(copy.resolve("config/commands.json"))).isEqualTo(commands);
            String code=ProductQuoteNativeInitialization.sourceUiCommand("customer_pcba_int_external_ref");
            for(var fieldCode:writers.keySet()) {
                var item=json.valueToTree(writers.get(fieldCode));
                assertThat(item.path("testWriters").toString()).isEqualTo("[\"qo_procurement_task_common:sync_sources\",\""+code+"\"]");
            }
            if(!directory) {
                var original=json.readTree(source.resolve("config/fields.json").toFile());
                var amended=json.readTree(copy.resolve("config/fields.json").toFile());
                assertThat(original.get(original.size()-1)).isEqualTo(amended.get(amended.size()-1));
                assertThat(original.toString()).doesNotContain(code);
            }
        }
    }
    @Test void integrationOwnerCannotBeGuessedDuplicatedOrReusedWithAnExistingWriterAppendix() throws Exception {
        Path source=integration("guard-owner",false);
        assertThat(ProductQuoteNativeInitialization.sourceUiIntegrationOwner(java.util.List.of(original(),source),json)).isEqualTo(source.toRealPath());
        assertThatThrownBy(()->ProductQuoteNativeInitialization.sourceUiIntegrationOwner(java.util.List.of(source,source),json)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ProductQuoteNativeInitialization.sourceUiIntegrationOwner(java.util.List.of(),json)).isInstanceOf(IllegalArgumentException.class);
        Path copy=root.resolve("guard-copy");ProductQuoteNativeInitialization.prepareSourceUiIntegration(source,copy,json);
        assertThatThrownBy(()->ProductQuoteNativeInitialization.prepareSourceUiIntegration(copy,root.resolve("reappended"),json))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicated");
        var manifest=json.readTree(source.resolve("plugin.json").toFile());((com.fasterxml.jackson.databind.node.ObjectNode)manifest).put("pluginId","com.auraboot.quote-procurement");
        Files.writeString(source.resolve("plugin.json"),manifest.toString());
        assertThatThrownBy(()->ProductQuoteNativeInitialization.prepareSourceUiIntegration(source,root.resolve("wrong-owner"),json)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void integrationOwnerRequiresEveryActualPayloadBindingAndExplicitProtectedWriterArray() throws Exception {
        for(String defect:java.util.List.of("binding","duplicate","writers")) {
            Path source=integration("bad-"+defect,false);
            if(defect.equals("binding")) {
                var bindings=(com.fasterxml.jackson.databind.node.ArrayNode)json.readTree(source.resolve("config/bindings.json").toFile());bindings.remove(0);
                Files.writeString(source.resolve("config/bindings.json"),bindings.toString());
            } else {
                var fields=(com.fasterxml.jackson.databind.node.ArrayNode)json.readTree(source.resolve("config/fields.json").toFile());
                if(defect.equals("duplicate"))fields.add(fields.get(0).deepCopy());
                else for(var field:fields)if(field.path("code").asText().equals("customer_pcba_ref_account_id"))
                    ((com.fasterxml.jackson.databind.node.ObjectNode)field).put("allowedWriterCommands","ALL");
                Files.writeString(source.resolve("config/fields.json"),fields.toString());
            }
            assertThatThrownBy(()->ProductQuoteNativeInitialization.prepareSourceUiIntegration(source,root.resolve("bad-copy-"+defect),json)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void fourSequentialIntegrationAppendicesStartFromTheSameUnmodifiedActualOwner() throws Exception {
        Path source=integration("sequential-owner",false);
        byte[] fields=Files.readAllBytes(source.resolve("config/fields.json"));
        for(int index=0;index<4;index++) {
            var proof=ProductQuoteNativeInitialization.prepareSourceUiIntegration(source,root.resolve("sequential-"+index),json);
            assertThat(proof.get("sourceDirectory")).isEqualTo(source.toRealPath().toString());
            assertThat(Files.readAllBytes(source.resolve("config/fields.json"))).isEqualTo(fields);
            var writers=(Map<?,?>)proof.get("exactWriterAppendix");
            assertThat(writers).hasSize(2);
            for(Object writer:writers.values())assertThat(json.valueToTree(writer).path("testWriters").size()).isEqualTo(2);
        }
    }

}
