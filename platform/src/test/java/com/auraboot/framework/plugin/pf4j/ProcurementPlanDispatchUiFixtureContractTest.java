package com.auraboot.framework.plugin.pf4j;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Hermetic prerequisite contracts, not Native execution or dispatch UI qualification. */
class ProcurementPlanDispatchUiFixtureContractTest {
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

    @Test void exactSourceMakerRoleAddsOnlyActualCategoryMembershipRead() {
        var expected = new java.util.ArrayList<>(ProcurementSourceUiFixtureProducerIT.MAKER_PERMISSIONS);
        expected.add("model.qo_procurement_category_supplier_common.read");
        assertEquals(expected, ProcurementPlanDispatchUiFixtureProducerIT.BUYER_PERMISSIONS);
        assertEquals(20, Set.copyOf(expected).size());
        assertEquals(expected, ProcurementPlanDispatchUiFixtureProducerIT.MAKER_PERMISSIONS);
        assertFalse(expected.contains("*")); assertFalse(expected.contains("qo.srm.use"));
        assertFalse(expected.contains("model.pr_purchase_order.read"));
    }
}
