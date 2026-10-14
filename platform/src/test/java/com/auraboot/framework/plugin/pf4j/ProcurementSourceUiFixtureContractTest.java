package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.notification.sourcebinding.ProductQuoteNativeInitialization;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Hermetic prerequisite contracts; neither Native nor UI execution is claimed. */
class ProcurementSourceUiFixtureContractTest {
    @TempDir Path root;

    @Test void exactPositiveSourceIdentityAndFixedEighteenFieldsCannotBorrowLegacyPageIds() {
        assertEquals(18, ProcurementSourceUiFixtureProducerIT.FIELDS.size());
        assertEquals("FID", ProcurementSourceUiFixtureProducerIT.FIELDS.getFirst());
        assertEquals("FCloseStatus", ProcurementSourceUiFixtureProducerIT.FIELDS.getLast());
        var row = ProcurementSourceUiFixtureProducerIT.sourceRow("104", "61", "UI-PAGINATION");
        assertEquals(18, row.size()); assertEquals("1500", row.get(7)); assertEquals("1000", row.get(11));
        assertThrows(AssertionError.class, () -> ProcurementSourceUiFixtureProducerIT.sourceRow("PAGE-01", "61", "UI"));
        assertThrows(AssertionError.class, () -> ProcurementSourceUiFixtureProducerIT.sourceRow("0", "61", "UI"));
        assertThrows(AssertionError.class, () -> ProcurementSourceUiFixtureProducerIT.sourceRow("104", "01", "UI"));
        assertEquals(Map.of("requisitionId", "104", "entryId", "61"), ProcurementSourceUiFixtureProducerIT.pair("104", "61"));
    }

    @Test void publicBuyerAndNativeMakerHaveDistinctExactRolesWithoutWildcardOrSupplierAuthority() {
        var buyer = ProcurementSourceUiFixtureProducerIT.BUYER_PERMISSIONS;
        var maker = ProcurementSourceUiFixtureProducerIT.MAKER_PERMISSIONS;
        assertEquals(15, buyer.size()); assertEquals(15, Set.copyOf(buyer).size());
        assertTrue(buyer.containsAll(List.of("page.page.read", "qo.procurement.manage", "qo.procurement.integrate",
                "model.pr_supplier_common.read", "model.qo_procurement_history_common.read")));
        assertFalse(buyer.contains("*")); assertFalse(buyer.contains("qo.srm.use"));
        assertEquals(19, maker.size()); assertTrue(maker.containsAll(buyer));
        assertTrue(maker.containsAll(List.of("model.qo_procurement_submission_common.read",
                "model.qo_procurement_submission_line_common.read", "model.qo_procurement_execution_common.read",
                "model.qo_procurement_category_policy_common.read")));
    }

    @Test void sourceInitializationNeverIncludesTaskQuoteOrReservedQuantityAndPreservesProductionWriters() throws Exception {
        var models = ProductQuoteNativeInitialization.SOURCE_UI_FIELDS;
        assertEquals(6, models.size());
        assertFalse(models.containsKey("s2p_sourcing_event")); assertFalse(models.containsKey("s2p_bid"));
        assertFalse(models.values().stream().flatMap(List::stream).anyMatch(code -> code.contains("reserved")));
        assertThrows(IllegalArgumentException.class, () -> ProductQuoteNativeInitialization.sourceUiCommand("pr_purchase_request"));
        assertThrows(IllegalArgumentException.class, () -> ProductQuoteNativeInitialization.validateSourceUiPayload(
                "pr_spend_category", Map.of("pr_spc_code", "x", "pr_spc_name", "x", "reserved", "125")));
        Path source = root.resolve("source"); Files.createDirectories(source.resolve("config/fields"));
        Files.createDirectories(source.resolve("config/commands"));
        Files.writeString(source.resolve("plugin.json"), "{\"pluginId\":\"com.auraboot.quote-procurement\"}");
        String field = "{\"code\":\"qo_pr_contact_state\",\"allowedWriterCommands\":[\"pr_supplier_pcba_contact:sync_directory\"],\"defaultValue\":\"inactive\"}";
        Files.writeString(source.resolve("config/fields/contact.json"), field);
        Path copy = root.resolve("copy"); var json = new ObjectMapper();
        ProductQuoteNativeInitialization.prepareSourceUi(source, copy, json);
        assertEquals(field, Files.readString(source.resolve("config/fields/contact.json")));
        var generated = json.readTree(copy.resolve("config/fields/contact.json").toFile());
        assertEquals(List.of("pr_supplier_pcba_contact:sync_directory", "pr_supplier_pcba_contact:init_source_ui_fixture"),
                json.convertValue(generated.path("allowedWriterCommands"), List.class));
        var commands = json.readTree(copy.resolve("config/commands/source-ui-native-initialization.json").toFile());
        assertEquals(6, commands.size());
        for (var command : commands) {
            assertEquals("create", command.path("type").asText());
            assertEquals("qo.procurement.integrate", command.path("permissions").get(0).asText());
            assertTrue(command.path("code").asText().endsWith(":init_source_ui_fixture"));
        }
    }

    @Test void sourcePolicyHasOnlyExactServerIdentityAndVerifiedSyntheticStatuses() {
        var policy = ProcurementSourceUiFixtureProducerIT.policy("connection", "account");
        assertEquals(Set.of("connectionPid", "accountId", "rules"), policy.keySet());
        assertFalse(policy.containsKey("remarkField"));
        assertEquals("connection", policy.get("connectionPid")); assertEquals("account", policy.get("accountId"));
        assertThrows(AssertionError.class, () -> ProcurementSourceUiFixtureProducerIT.assertDecimal("125", "0"));
    }
    @Test void optionalControlledRemarkIsSeparateNineteenthFieldAndNeverAnExternalCustomerAlias() throws Exception {
        var type = ProcurementSourceUiFixtureProducerIT.class;
        var method = type.getDeclaredMethod("remarkRow", String.class, String.class, String.class, String.class);
        @SuppressWarnings("unchecked")
        var initial = (List<Object>) method.invoke(null, "105", "71", "UI-REMARK", "Initial controlled remark");
        assertEquals(19, initial.size());
        assertEquals(ProcurementSourceUiFixtureProducerIT.sourceRow("105", "71", "UI-REMARK"), initial.subList(0, 18));
        assertEquals("Initial controlled remark", initial.get(18));
        @SuppressWarnings("unchecked")
        var cleared = (List<Object>) method.invoke(null, "105", "71", "UI-REMARK", null);
        assertEquals(19, cleared.size());
        assertNull(cleared.get(18));
        @SuppressWarnings("unchecked")
        var empty = (List<Object>) method.invoke(null, "105", "71", "UI-REMARK", "");
        assertEquals("", empty.get(18));
        var policyMethod = type.getDeclaredMethod("policy", String.class, String.class, String.class);
        @SuppressWarnings("unchecked")
        var configured = (Map<String, Object>) policyMethod.invoke(null, "connection", "account", "FControlledRemark");
        assertEquals(Set.of("connectionPid", "accountId", "rules", "remarkField"), configured.keySet());
        assertEquals("FControlledRemark", configured.get("remarkField"));
        assertFalse(ProcurementSourceUiFixtureProducerIT.policy("connection", "account").containsKey("remarkField"));
        assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> policyMethod.invoke(null, "connection", "account", "FNote"));
    }

    @Test void independentRequestKeysFitThePersistedContractAndNeverReuseAcrossStages() {
        var keys = new java.util.HashSet<String>();
        for (int i = 0; i < 1000; i++) {
            for (String action : List.of("init", "public")) {
                String key = ProcurementSourceUiFixtureProducerIT.fixtureRequestKey(action);
                assertTrue(key.length() <= 64, "Persisted request key exceeds varchar(64): " + key.length());
                assertTrue(keys.add(key), "Different initialization or public commands must not share a nonce");
                java.util.UUID.fromString(key.substring(key.length() - 36));
            }
        }
        assertEquals(2000, keys.size());
        assertThrows(IllegalArgumentException.class, () -> ProcurementSourceUiFixtureProducerIT.fixtureRequestKey("other"));
    }

    @Test void completedPublicCommandUsesTextualZeroAndObjectBusinessData() throws Exception {
        var body=new ObjectMapper().readTree("{\"code\":\"0\",\"data\":{\"phaseReached\":\"completed\",\"data\":{\"recordPid\":\"owned\"}}}");
        assertEquals("owned",ProcurementSourceUiFixtureProducerIT.requireCompletedCommandResponse(body).path("recordPid").asText());
    }
    @Test void publicCommandCannotAcceptHttpAliasCoercionOrIncompleteData() throws Exception {
        var json=new ObjectMapper();
        for(String invalid:List.of(
                "{\"code\":\"200\",\"data\":{\"phaseReached\":\"completed\",\"data\":{}}}",
                "{\"code\":0,\"data\":{\"phaseReached\":\"completed\",\"data\":{}}}",
                "{\"data\":{\"phaseReached\":\"completed\",\"data\":{}}}",
                "{\"code\":\"0\",\"data\":{\"phaseReached\":\"pending\",\"data\":{}}}",
                "{\"code\":\"0\",\"data\":{\"data\":{}}}",
                "{\"code\":\"0\"}",
                "{\"code\":\"0\",\"data\":{\"phaseReached\":\"completed\"}}",
                "{\"code\":\"0\",\"data\":{\"phaseReached\":\"completed\",\"data\":[]}}")) {
            var body=json.readTree(invalid);
            assertThrows(AssertionError.class,()->ProcurementSourceUiFixtureProducerIT.requireCompletedCommandResponse(body));
        }
    }

}
