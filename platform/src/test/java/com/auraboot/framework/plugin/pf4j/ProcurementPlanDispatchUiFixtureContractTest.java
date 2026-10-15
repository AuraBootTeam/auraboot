package com.auraboot.framework.plugin.pf4j;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Hermetic prerequisite contracts, not Native execution or dispatch UI qualification. */
class ProcurementPlanDispatchUiFixtureContractTest {
    @org.junit.jupiter.api.Test
    void databaseJsonRetainsMicrosecondsObjectsAndExactNumbers() throws Exception {
        String raw = "[{\"pid\":\"p1\",\"updated_at\":\"2026-10-14T14:17:13.807477+00:00\","
                + "\"content\":{\"flag\":false,\"items\":[0]},\"qty\":9007199254740993.123456789012,"
                + "\"row_version\":9007199254740993}]";
        var node = ProcurementPlanDispatchUiFixtureProducerIT.readLosslessDatabaseJson(raw);
        assertEquals("2026-10-14T14:17:13.807477+00:00", node.get(0).path("updated_at").asText());
        assertTrue(node.get(0).path("content").isObject());
        assertFalse(node.get(0).path("content").path("flag").booleanValue());
        assertEquals(new java.math.BigDecimal("9007199254740993.123456789012"), node.get(0).path("qty").decimalValue());
        assertEquals(new java.math.BigInteger("9007199254740993"), node.get(0).path("row_version").bigIntegerValue());
        assertEquals(node, ProcurementPlanDispatchUiFixtureProducerIT.readLosslessDatabaseJson(node.toString()));
        var fixture = new ProcurementPlanDispatchUiFixtureProducerIT(); fixture.json = new ObjectMapper(); fixture.tenant = 17L;
        fixture.postgres = org.mockito.Mockito.mock(javax.sql.DataSource.class);
        var connection = org.mockito.Mockito.mock(java.sql.Connection.class);
        var zone = org.mockito.Mockito.mock(java.sql.Statement.class);
        var zoneRows = org.mockito.Mockito.mock(java.sql.ResultSet.class);
        var settings = org.mockito.Mockito.mock(java.sql.PreparedStatement.class);
        var query = org.mockito.Mockito.mock(java.sql.PreparedStatement.class);
        var rows = org.mockito.Mockito.mock(java.sql.ResultSet.class);
        org.mockito.Mockito.when(fixture.postgres.getConnection()).thenReturn(connection);
        org.mockito.Mockito.when(connection.createStatement()).thenReturn(zone);
        org.mockito.Mockito.when(zone.executeQuery("SHOW TIME ZONE")).thenReturn(zoneRows);
        org.mockito.Mockito.when(zoneRows.next()).thenReturn(true);
        org.mockito.Mockito.when(zoneRows.getString(1)).thenReturn("Asia/Shanghai");
        org.mockito.Mockito.when(connection.prepareStatement("SELECT set_config('TimeZone', ?, false)")).thenReturn(settings);
        String sql = "SELECT COALESCE(json_agg(t ORDER BY t.pid), '[]'::json)::text FROM mt_owned t WHERE tenant_id=?";
        org.mockito.Mockito.when(connection.prepareStatement(sql)).thenReturn(query);
        org.mockito.Mockito.when(query.executeQuery()).thenReturn(rows);
        org.mockito.Mockito.when(rows.next()).thenReturn(true, false);
        org.mockito.Mockito.when(rows.getString(1)).thenReturn(raw);
        var method = ProcurementPlanDispatchUiFixtureProducerIT.class.getDeclaredMethod("rawBusinessSnapshot", Map.class);
        method.setAccessible(true);
        var actual = (com.fasterxml.jackson.databind.JsonNode) method.invoke(fixture,
                Map.of("source", Map.of("physicalTable", "mt_owned")));
        assertEquals(node, actual.path("source"));
        org.mockito.Mockito.verify(query).setLong(1, 17L);
        var ordered = org.mockito.Mockito.inOrder(settings, query);
        ordered.verify(settings).setString(1, "UTC"); ordered.verify(settings).execute();
        ordered.verify(query).executeQuery();
        ordered.verify(settings).setString(1, "Asia/Shanghai"); ordered.verify(settings).execute();
        org.mockito.Mockito.verify(connection).close();

    }

    @Test void failedPublicResponseRetainsOnlyKnownDiagnosticsBeforeStatusAssertion() throws Exception {
        var json=new ObjectMapper();var body=json.readTree("{\"code\":\"35000\",\"message\":\"credential-echo\",\"token\":\"not-for-evidence\",\"context\":{\"messageKey\":\"$i18n:qo_pr.error.verified_source_eligibility_required\",\"detail\":\"secret body\",\"cause\":\"private path\",\"fieldCode\":\"row_version\"},\"data\":null}");
        var diagnostic=ProcurementPlanDispatchUiFixtureProducerIT.safePublicResponse(400,"qo_procurement_task_common:source_candidates","2",body);
        assertEquals(400,diagnostic.get("httpStatus"));assertEquals("35000",diagnostic.get("code"));
        assertEquals("$i18n:qo_pr.error.verified_source_eligibility_required",diagnostic.get("messageKey"));
        assertEquals("String",diagnostic.get("targetVersionType"));assertEquals("row_version",diagnostic.get("knownContextField"));
        String encoded=json.writeValueAsString(diagnostic);
        for(String privateValue:List.of("credential-echo","not-for-evidence","secret body","private path"))assertFalse(encoded.contains(privateValue));
        var unknown=json.readTree("{\"code\":\"secret-value\",\"context\":{\"messageKey\":\"/private/session\",\"fieldCode\":\"session_token\"},\"data\":{\"phaseReached\":\"secret_phase\"}}");
        var rejected=ProcurementPlanDispatchUiFixtureProducerIT.safePublicResponse(500,"qo_procurement_task_common:source_candidates",null,unknown);
        assertFalse(rejected.containsKey("code"));assertFalse(rejected.containsKey("messageKey"));assertFalse(rejected.containsKey("knownContextField"));assertFalse(rejected.containsKey("phaseReached"));
        assertEquals(false,rejected.get("rawResponsePersisted"));
    }
    @Test void scaledZeroAndLargeExactQuantitiesUsePlainReceiptLexicalContract() {
        assertEquals("0.000000000000",ProcurementPlanDispatchUiFixtureProducerIT.quantityText(new java.math.BigDecimal("0E-12")));
        for(String value:List.of("300.000001","9007199254740993.000001","1E+20","125.000000000000")) {
            var raw=new java.math.BigDecimal(value);
            String emitted=ProcurementPlanDispatchUiFixtureProducerIT.quantityText(raw);
            assertTrue(emitted.matches("[0-9]+(?:\\.[0-9]+)?"));
            assertEquals(0,raw.compareTo(new java.math.BigDecimal(emitted)));
        }
        assertThrows(NumberFormatException.class,()->ProcurementPlanDispatchUiFixtureProducerIT.quantityText("NaN"));
    }

    @Test void selectedInputRequiresExplicitDefaultOffGate() throws Exception {
        var json = new ObjectMapper();
        var valid = json.readTree("{\"kind\":\"procurement-plan-dispatch-ui-input-v1\",\"schemaVersion\":1,\"planDispatchFixtureEnabled\":true}");
        ProcurementPlanDispatchUiFixtureProducerIT.requireSelectedInput(valid);
        for (String gate : List.of("false", "null", "\"true\"")) {
            var invalid = valid.deepCopy(); ((com.fasterxml.jackson.databind.node.ObjectNode) invalid).set("planDispatchFixtureEnabled", json.readTree(gate));
            assertThrows(AssertionError.class, () -> ProcurementPlanDispatchUiFixtureProducerIT.requireSelectedInput(invalid));
        }
        assertThrows(AssertionError.class, () -> ProcurementPlanDispatchUiFixtureProducerIT.requireSelectedInput(json.readTree("{}")));
    }

    @Test void stagesKeepAwardedReadOnlyFactsSeparateFromDraftMutationTasks() {
        var stages = ProcurementPlanDispatchUiFixtureProducerIT.STAGES;
        assertEquals(List.of("allocatedFacts", "draftPlan", "classificationHappy", "noSupplierMatch", "unclassified", "datesHappy", "datesMissing", "datesExpired", "datesCancel"), stages);
        assertEquals(9, Set.copyOf(stages).size());
        for (String stage : stages) {
            var row = ProcurementPlanDispatchUiFixtureProducerIT.stageRow(stage, "202", "11");
            assertEquals(18, row.size()); assertEquals("202", row.get(0)); assertEquals("11", row.get(2));
            assertEquals(List.of("draftPlan", "classificationHappy").contains(stage) ? 2 : 1,
                    ProcurementPlanDispatchUiFixtureProducerIT.stageSupplierCount(stage));
            boolean large = List.of("allocatedFacts", "draftPlan").contains(stage);
            assertEquals(large ? "1500" : "100", row.get(7)); assertEquals(large ? "1000" : "100", row.get(11));
            assertEquals(row.get(1), ProcurementPlanDispatchUiFixtureProducerIT.stageRow(stage, "202", "12").get(1));
        }
        assertThrows(AssertionError.class, () -> ProcurementPlanDispatchUiFixtureProducerIT.stageRow("sourceCritical", "202", "11"));
        assertThrows(AssertionError.class, () -> ProcurementPlanDispatchUiFixtureProducerIT.stageRow("draftPlan", "PAGE-1", "11"));
    }

    @Test void exactSourceMakerRoleAlreadyIncludesActualCategoryMembershipRead() {
        var expected = new java.util.ArrayList<>(ProcurementSourceUiFixtureProducerIT.MAKER_PERMISSIONS);
        assertTrue(expected.contains("model.qo_procurement_category_supplier_common.read"));
        assertEquals(20, expected.size());
        assertEquals(expected, ProcurementPlanDispatchUiFixtureProducerIT.BUYER_PERMISSIONS);
        assertEquals(20, Set.copyOf(expected).size());
        assertEquals(expected, ProcurementPlanDispatchUiFixtureProducerIT.MAKER_PERMISSIONS);
        assertFalse(expected.contains("*")); assertFalse(expected.contains("qo.srm.use"));
        assertFalse(expected.contains("model.pr_purchase_order.read"));
    }
    @Test void independentRequestKeysRemainBoundedAndUniqueAcrossAllNineStages() {
        var keys = new java.util.HashSet<String>();
        for (String stage : ProcurementPlanDispatchUiFixtureProducerIT.STAGES) {
            for (int i = 0; i < 100; i++) {
                for (String action : List.of("init", "public")) {
                    String key = ProcurementPlanDispatchUiFixtureProducerIT.fixtureRequestKey(action);
                    assertTrue(key.length() <= 64, "Persisted request key exceeds varchar(64): " + key.length());
                    assertTrue(keys.add(key), "Different commands across " + stage + " must not share a nonce");
                    java.util.UUID.fromString(key.substring(key.length() - 36));
                }
            }
        }
        assertEquals(1800, keys.size());
        assertThrows(IllegalArgumentException.class, () -> ProcurementPlanDispatchUiFixtureProducerIT.fixtureRequestKey("other"));
    }

    @Test void completedPublicCommandUsesTextualZeroAndObjectBusinessData() throws Exception {
        var body=new ObjectMapper().readTree("{\"code\":\"0\",\"data\":{\"phaseReached\":\"completed\",\"data\":{\"recordPid\":\"owned\"}}}");
        assertEquals("owned",ProcurementPlanDispatchUiFixtureProducerIT.requireCompletedCommandResponse(body).path("recordPid").asText());
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
            assertThrows(AssertionError.class,()->ProcurementPlanDispatchUiFixtureProducerIT.requireCompletedCommandResponse(body));
        }
    }

}
