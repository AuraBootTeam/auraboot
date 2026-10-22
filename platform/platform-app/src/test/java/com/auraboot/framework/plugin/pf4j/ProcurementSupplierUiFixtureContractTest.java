package com.auraboot.framework.plugin.pf4j;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Hermetic synthetic guard inputs only; no Native or browser execution. */
class ProcurementSupplierUiFixtureContractTest {
    @TempDir Path root;

    @Test void markedParentRequiresExactChildInitializerAndNeverGrantsHeaderUpdateOrDelete() {
        var marker = com.auraboot.framework.meta.dto.FieldDefinition.builder()
                .code("managed").dataType("boolean").immutable(true).build();
        var model = com.auraboot.framework.meta.dto.ModelDefinition.builder().code("pr_purchase_order")
                .fields(List.of(marker)).extension(Map.of("recordCommandWriters", Map.of("field", "managed", "commands",
                        Map.of("create", List.of(ProcurementSupplierUiInitialization.command("pr_purchase_order")),
                                "update", List.of("native:update"), "delete", List.of())))).build();
        String child = ProcurementSupplierUiInitialization.command("pr_purchase_order_line");
        var stored = Map.<String, Object>of("managed", true);
        com.auraboot.framework.application.tenant.MetaContext.runWithCommandPermitPlan("ALL", null,
                "pr_purchase_order_line", null, () ->
                com.auraboot.framework.application.tenant.MetaContext.runWithAuthorizedCommandCode(child, () -> {
                    assertThrows(com.auraboot.framework.meta.exception.MetaServiceException.class, () ->
                            com.auraboot.framework.meta.service.impl.RecordCommandWriterGuard.assertStoredAllowed(model, stored, "update"));
                    model.setExtension(Map.of("recordCommandWriters", Map.of("field", "managed", "commands",
                            Map.of("create", List.of(ProcurementSupplierUiInitialization.command("pr_purchase_order")),
                                    "update", List.of("native:update", child), "delete", List.of()))));
                    com.auraboot.framework.meta.service.impl.RecordCommandWriterGuard.assertStoredAllowed(model, stored, "update");
                    assertThrows(com.auraboot.framework.meta.exception.MetaServiceException.class, () ->
                            com.auraboot.framework.meta.service.impl.RecordCommandWriterGuard.assertStoredAllowed(model, stored, "delete"));
                }));
        com.auraboot.framework.application.tenant.MetaContext.runWithCommandPermitPlan("ALL", null,
                "pr_purchase_order", null, () ->
                com.auraboot.framework.application.tenant.MetaContext.runWithAuthorizedCommandCode(
                        ProcurementSupplierUiInitialization.command("pr_purchase_order"), () ->
                        assertThrows(com.auraboot.framework.meta.exception.MetaServiceException.class, () ->
                                com.auraboot.framework.meta.service.impl.RecordCommandWriterGuard.assertStoredAllowed(model, stored, "update"))));
    }

    @Test void canonicalPostgresRowRetainsJsonObjectsNullsExactDecimalsAndLargeIdentities() throws Exception {
        var json = new ObjectMapper();
        var raw = "{\"pid\":\"fixture\",\"tenant_id\":368527801529274368,\"source_snapshot\":{\"quantity\":300.000001,\"optional\":null},\"address_snapshot\":{\"address\":\"A\"},\"amount\":375.00000125,\"updated_at\":\"2026-10-14T01:02:03.123456+00:00\"}";
        var actual = ProcurementSupplierUiFixtureProducerIT.canonicalSnapshotRow(json, raw);
        assertEquals(368527801529274368L, actual.get("tenant_id"));
        assertEquals(new java.math.BigDecimal("375.00000125"), actual.get("amount"));
        var snapshot = (Map<?, ?>) actual.get("source_snapshot");
        assertEquals(new java.math.BigDecimal("300.000001"), snapshot.get("quantity"));
        assertTrue(snapshot.containsKey("optional")); assertNull(snapshot.get("optional"));
        assertEquals(Map.of("address", "A"), actual.get("address_snapshot"));
        assertEquals("2026-10-14T01:02:03.123456+00:00", actual.get("updated_at"));
        assertEquals(raw, json.writeValueAsString(actual));
        for (String invalid : List.of("[]", "null", "not-json"))
            assertThrows(IllegalArgumentException.class, () -> ProcurementSupplierUiFixtureProducerIT.canonicalSnapshotRow(json, invalid));
        assertThrows(IllegalArgumentException.class, () -> ProcurementSupplierUiFixtureProducerIT.canonicalSnapshotRow(json, null));
    }

    @Test void completedResponseUsesActualApiResponseTextCodeAndRejectsNumericOrHttpAliases() throws Exception {
        var json = new ObjectMapper();
        var actual = json.valueToTree(com.auraboot.framework.common.dto.ApiResponse.success(
                Map.of("phaseReached", "completed", "data", Map.of("recordPid", "fixture-pid"))));
        assertTrue(actual.path("code").isTextual());
        assertEquals("0", actual.path("code").asText());
        ProcurementSupplierUiFixtureProducerIT.requireCompletedCommandResponse(actual);
        for (String code : List.of("0", "200", "\"200\"", "null")) {
            var wrong = actual.deepCopy(); ((ObjectNode) wrong).set("code", json.readTree(code));
            assertThrows(AssertionError.class, () -> ProcurementSupplierUiFixtureProducerIT.requireCompletedCommandResponse(wrong));
        }
        var missing = actual.deepCopy(); ((ObjectNode) missing).remove("code");
        assertThrows(AssertionError.class, () -> ProcurementSupplierUiFixtureProducerIT.requireCompletedCommandResponse(missing));
        var failed = actual.deepCopy(); ((ObjectNode) failed.path("data")).put("phaseReached", "failed");
        assertThrows(AssertionError.class, () -> ProcurementSupplierUiFixtureProducerIT.requireCompletedCommandResponse(failed));
    }

    @Test void browserIdentityKeepsExactlyThreePermissionsAndNeverBorrowsNativeMakerAuthority() {
        assertEquals(List.of("meta.command.execute", "qo.srm.use", "page.page.read"), ProcurementSupplierUiFixtureProducerIT.SUPPLIER_PERMISSIONS);
        var maker = ProcurementSupplierUiFixtureProducerIT.INITIALIZER_PERMISSIONS;
        assertEquals(25, maker.size()); assertEquals(25, Set.copyOf(maker).size());
        var expected=new java.util.HashSet<>(ProcurementSourceUiFixtureProducerIT.MAKER_PERMISSIONS);
        expected.addAll(List.of("model.pr_purchase_order.read","model.pr_purchase_order_line.read",
                "model.pr_supplier_pcba_contact.read","model.qo_procurement_membership_common.read",
                "model.qo_procurement_address_common.read"));
        assertEquals(25,expected.size());assertEquals(expected,Set.copyOf(maker));
        assertFalse(maker.contains("*")); assertFalse(maker.contains("qo.srm.use"));
        assertEquals(List.of("quoteSubmit", "quoteDecline", "shipmentJourney", "shipmentUnknown", "closedOrder"), ProcurementSupplierUiFixtureProducerIT.STAGES);
        assertEquals(5, Set.copyOf(ProcurementSupplierUiFixtureProducerIT.STAGES).size());
    }

    @Test void explicitDefaultOffInputAndUniqueBoundedKeysCannotBorrowAnotherProducer() throws Exception {
        var json = new ObjectMapper();
        var valid = json.readTree("{\"kind\":\"procurement-supplier-ui-input-v1\",\"schemaVersion\":1,\"supplierFixtureEnabled\":true}");
        ProcurementSupplierUiFixtureProducerIT.requireSelectedInput(valid);
        for (String value : List.of("false", "null", "\"true\"")) {
            var wrong = valid.deepCopy(); ((ObjectNode) wrong).set("supplierFixtureEnabled", json.readTree(value));
            assertThrows(AssertionError.class, () -> ProcurementSupplierUiFixtureProducerIT.requireSelectedInput(wrong));
        }
        var legacy = valid.deepCopy(); ((ObjectNode) legacy).put("kind", "procurement-source-ui-input-v1");
        assertThrows(AssertionError.class, () -> ProcurementSupplierUiFixtureProducerIT.requireSelectedInput(legacy));
        var keys = new HashSet<String>();
        for (int i = 0; i < 1000; i++) {
            String key = ProcurementSupplierUiFixtureProducerIT.requestKey();
            assertTrue(key.length() <= 64); assertTrue(keys.add(key));
        }
    }

    @Test void testOwnedInitializerRefusesProductionAliasesOrUnlistedFields() {
        assertEquals(10, ProcurementSupplierUiInitialization.FIELDS.size());
        assertThrows(IllegalArgumentException.class, () -> ProcurementSupplierUiInitialization.command("s2p_bid"));
        assertThrows(IllegalArgumentException.class, () -> ProcurementSupplierUiInitialization.validatePayload(
                "pr_purchase_order", Map.of("supplierPid", "other", "receivedQty", "250")));
        for (String model : ProcurementSupplierUiInitialization.FIELDS.keySet()) {
            assertTrue(ProcurementSupplierUiInitialization.command(model).length() <= 64,
                    "Audit command_code is varchar(64): " + model);
            assertTrue(ProcurementSupplierUiInitialization.command(model).endsWith(":init_supplier_ui_fixture"));
        }
        assertFalse(ProcurementSupplierUiInitialization.FIELDS.containsKey("qo_procurement_shipment_common"));
    }

    @Test void exactTwentyThreeOwnerCopiesGrantOnlyDeclaredChildParentCheckAndPreserveOtherWriters() throws Exception {
        var json = new ObjectMapper(); var sources = new java.util.ArrayList<Path>();
        for (int i = 0; i < 23; i++) {
            Path source = root.resolve("source-" + i); Files.createDirectories(source.resolve("config/fields")); Files.createDirectories(source.resolve("config/commands"));
            Files.writeString(source.resolve("plugin.json"), "{\"pluginId\":\"com.test.hermetic.owner-" + i + "\",\"resourceDirs\":{\"commands\":\"config/commands\"}}"); sources.add(source);
        }
        Path first = sources.getFirst();
        var models = json.createArrayNode(); var bindings = json.createArrayNode();
        Files.createDirectories(first.resolve("config/bindings"));
        for (var entry : ProcurementSupplierUiInitialization.FIELDS.entrySet()) {
            var model = models.addObject(); model.put("code", entry.getKey()); model.put("immutable", false);
            var extension = model.putObject("extension");
            if ("pr_purchase_order_line".equals(entry.getKey()))
                extension.put("parentModel", "pr_purchase_order").put("parentField", "pr_pol_order_id");
            var policy = extension.putObject("recordCommandWriters"); policy.put("field", "managed");
            var commands = policy.putObject("commands"); commands.putArray("create").add("native:create"); commands.putArray("update").add("native:update"); commands.putArray("delete");
            for (String code : entry.getValue()) {
                bindings.addObject().put("modelCode", entry.getKey()).put("fieldCode", code);
                var field = json.createObjectNode().put("code", code).put("dataType", "string");
                field.putArray("allowedWriterCommands").add("native:write"); field.putObject("extension").put("systemManaged", true);
                Files.writeString(first.resolve("config/fields/" + code + ".json"), json.writeValueAsString(field));
            }
        }
        Files.writeString(first.resolve("config/models.json"), json.writeValueAsString(models));
        Files.writeString(first.resolve("config/bindings/fixture.json"), json.writeValueAsString(bindings));
        var before = ProcurementSupplierUiInitialization.resourceRoster(first);
        var proof = ProcurementSupplierUiInitialization.prepare(sources, root.resolve("copy"), json);
        assertEquals(before, ProcurementSupplierUiInitialization.resourceRoster(first));
        @SuppressWarnings("unchecked") var owners = (List<Map<String, Object>>) proof.get("ownerDirectories");
        assertEquals(23, owners.size());
        Path copied = Path.of(owners.getFirst().get("testDirectory").toString());
        var generated = json.readTree(copied.resolve("config/models.json").toFile());
        for (var model : generated) {
            var operations = model.path("extension").path("recordCommandWriters").path("commands");
            assertEquals(List.of("native:create", ProcurementSupplierUiInitialization.command(model.path("code").asText())), json.convertValue(operations.path("create"), List.class));
            assertEquals(model.path("code").asText().equals("pr_purchase_order")
                    ? List.of("native:update", ProcurementSupplierUiInitialization.command("pr_purchase_order_line"))
                    : List.of("native:update"), json.convertValue(operations.path("update"), List.class));
            assertTrue(operations.path("delete").isEmpty()); assertFalse(model.path("immutable").asBoolean());
        }
        var childDefinition = models.get(ProcurementSupplierUiInitialization.FIELDS.keySet().stream().toList().indexOf("pr_purchase_order_line"));
        ((ObjectNode) childDefinition.path("extension")).put("parentField", "other_parent");
        Files.writeString(first.resolve("config/models.json"), json.writeValueAsString(models));
        assertThrows(IllegalArgumentException.class, () -> ProcurementSupplierUiInitialization.prepare(sources, root.resolve("wrong-parent-copy"), json));
        ((ObjectNode) childDefinition.path("extension")).put("parentField", "pr_pol_order_id").put("parentModel", "other_model");
        Files.writeString(first.resolve("config/models.json"), json.writeValueAsString(models));
        assertThrows(IllegalArgumentException.class, () -> ProcurementSupplierUiInitialization.prepare(sources, root.resolve("wrong-parent-model-copy"), json));
        ((ObjectNode) childDefinition.path("extension")).put("parentModel", "pr_purchase_order");
        Files.writeString(first.resolve("config/models.json"), json.writeValueAsString(models));
        @SuppressWarnings("unchecked") var fields = (List<Map<String, Object>>) proof.get("exactFieldWriterAppendix");
        assertFalse(fields.isEmpty());
        for (Map<String, Object> field : fields) assertEquals(List.of("native:write"), json.convertValue(field.get("productionWriters"), List.class));
        // Category and contact fields have distinct codes; never grant the other model's writer.
        Path second = sources.get(1); Files.createDirectories(second.resolve("config/bindings"));
        var movedModels = json.createArrayNode(); var retainedModels = json.createArrayNode();
        for (var model : models) (model.path("code").asText().equals("pr_spend_category") ? movedModels : retainedModels).add(model);
        Files.writeString(first.resolve("config/models.json"), json.writeValueAsString(retainedModels));
        Files.writeString(second.resolve("config/models.json"), json.writeValueAsString(movedModels));
        var movedBindings = json.createArrayNode(); var retainedBindings = json.createArrayNode();
        for (var binding : bindings) (binding.path("modelCode").asText().equals("pr_spend_category") ? movedBindings : retainedBindings).add(binding);
        Files.writeString(first.resolve("config/bindings/fixture.json"), json.writeValueAsString(retainedBindings));
        Files.writeString(second.resolve("config/bindings.json"), json.writeValueAsString(movedBindings));
        var flatFields = json.createArrayNode();
        for (String code : ProcurementSupplierUiInitialization.FIELDS.get("pr_spend_category"))
            flatFields.add(json.readTree(first.resolve("config/fields/" + code + ".json").toFile()));
        Files.writeString(second.resolve("config/fields.json"), json.writeValueAsString(flatFields));
        var flatManifest = json.readTree(second.resolve("plugin.json").toFile());
        ((ObjectNode) flatManifest.path("resourceDirs")).put("commands", "config/commands.json");
        Files.writeString(second.resolve("plugin.json"), json.writeValueAsString(flatManifest));
        var productionCommand = json.readTree("{\"code\":\"pr_spend_category:existing\",\"modelCode\":\"pr_spend_category\",\"type\":\"create\"}");
        Files.writeString(second.resolve("config/commands.json"), json.writeValueAsString(List.of(productionCommand)));
        var scoped = ProcurementSupplierUiInitialization.prepare(sources, root.resolve("scoped-copy"), json);
        @SuppressWarnings("unchecked") var scopedOwners = (List<Map<String, Object>>) scoped.get("ownerDirectories");
        var loader = new com.auraboot.framework.plugin.service.impl.PluginDirectoryLoader();
        var loaded = loader.loadFromDirectory(Path.of(scopedOwners.get(1).get("testDirectory").toString()));
        assertEquals(Set.of("pr_spend_category:existing", ProcurementSupplierUiInitialization.command("pr_spend_category")),
                loaded.getCommands().stream().map(command -> command.getCode()).collect(java.util.stream.Collectors.toSet()));
        var copiedFlat = json.readTree(Path.of(scopedOwners.get(1).get("testDirectory").toString()).resolve("config/commands.json").toFile());
        assertEquals(productionCommand, copiedFlat.get(0));
        assertEquals(flatManifest, json.readTree(Path.of(scopedOwners.get(1).get("testDirectory").toString()).resolve("plugin.json").toFile()));
        var wrongRegistration = flatManifest.deepCopy();
        ((ObjectNode) wrongRegistration.path("resourceDirs")).put("commands", "../outside.json");
        Files.writeString(second.resolve("plugin.json"), json.writeValueAsString(wrongRegistration));
        assertThrows(IllegalArgumentException.class, () -> ProcurementSupplierUiInitialization.prepare(sources, root.resolve("escaped-copy"), json));
        ((ObjectNode) wrongRegistration.path("resourceDirs")).remove("commands");
        Files.writeString(second.resolve("plugin.json"), json.writeValueAsString(wrongRegistration));
        assertThrows(IllegalArgumentException.class, () -> ProcurementSupplierUiInitialization.prepare(sources, root.resolve("unregistered-copy"), json));
        Files.writeString(second.resolve("plugin.json"), json.writeValueAsString(flatManifest));
        @SuppressWarnings("unchecked") var scopedFields = (List<Map<String, Object>>) scoped.get("exactFieldWriterAppendix");
        var scopedNames = scopedFields.stream().filter(f -> Set.of("pr_spc_name", "pr_spc_contact_name").contains(f.get("field"))).toList();
        assertEquals(2, scopedNames.size());
        assertEquals(Set.of("pr_spc_name", "pr_spc_contact_name"), scopedNames.stream()
                .map(field -> field.get("field")).collect(java.util.stream.Collectors.toSet()));
        for (var field : scopedNames) {
            @SuppressWarnings("unchecked") var targetModels = (List<String>) field.get("models");
            assertEquals(1, targetModels.size());
            assertEquals(field.get("field").equals("pr_spc_name") ? "pr_spend_category" : "pr_supplier_pcba_contact",
                    targetModels.getFirst());
            assertEquals(List.of("native:write", ProcurementSupplierUiInitialization.command(targetModels.getFirst())),
                    json.convertValue(field.get("testWriters"), List.class));
        }
        assertThrows(IllegalArgumentException.class, () -> ProcurementSupplierUiInitialization.prepare(sources.subList(0, 22), root.resolve("bad"), json));
        assertThrows(IllegalArgumentException.class, () -> ProcurementSupplierUiInitialization.prepare(sources, root.resolve("copy"), json));
    }
}
