package com.auraboot.framework.plugin.pf4j;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Hermetic controlled-read fixture contract, not a public/native receipt acceptance test. */
class ProcurementPublicSourceNativeFixtureReadRowsTest {
    @Test void originalOrderReadRetainsItsExactEighteenColumnsAndSyntheticReceived250() throws Exception {
        var fixture = fixture();
        assertEquals(List.of(List.of("local-order-1", "LOCAL-PO-1", "local-entry-1", "supplier-1",
                "material-1", "2026-10-14", "LOCAL_APPROVED", "LOCAL_OPEN", "300.000001", "0", "250",
                "LOCAL-MATERIAL", "Local material", "local", "PCS", "2026-10-21", "source-head", "source-entry")),
                fixture.orderReadRows());
        assertEquals(ProcurementPublicSourceNativeFixture.READ_COLUMNS.size(), fixture.orderReadRows().getFirst().size());
    }

    @Test void receiptZeroAndTwoLinesAreExplicitOverridesWithoutChangingDefaultOrderFacts() throws Exception {
        var original = fixture();
        var receipt = new ProcurementPublicSourceNativeFixture() {
            @Override protected List<List<Object>> orderReadRows() {
                var base = super.orderReadRows().getFirst();
                var first = new ArrayList<Object>(base); first.set(8, "150");
                var second = new ArrayList<Object>(base); second.set(2, "local-entry-2"); second.set(8, "150.000001");
                return List.of(first, second);
            }
        };
        receipt.savedOrder = original.savedOrder;
        receipt.readReceivedQuantity = "0";
        var rows = receipt.orderReadRows();
        assertEquals(2, rows.size());
        assertEquals(List.of("0", "0"), rows.stream().map(row -> row.get(10)).toList());
        assertEquals(List.of("local-entry-1", "local-entry-2"), rows.stream().map(row -> row.get(2)).toList());
        assertEquals("250", original.orderReadRows().getFirst().get(10));
        assertEquals("300.000001", original.orderReadRows().getFirst().get(8));
    }

    @Test void legacySourceObjectCannotSubstituteForTheSavedLink() throws Exception {
        var fixture = fixture();
        var line = (com.fasterxml.jackson.databind.node.ObjectNode) fixture.savedOrder.path("Lines").get(0);
        line.remove("Lines_Link");
        line.putObject("Source").put("Head", "source-head").put("Entry", "source-entry");
        assertThrows(NullPointerException.class, fixture::orderReadRows);
    }

    private ProcurementPublicSourceNativeFixture fixture() throws Exception {
        var fixture = new ProcurementPublicSourceNativeFixture() {};
        fixture.savedOrder = new ObjectMapper().readTree("""
                {"Supplier":{"Id":"supplier-1"},"Date":"2026-10-14","Lines":[
                 {"Material":{"Id":"material-1"},"DeliveryDate":"2026-10-21",
                  "Lines_Link":[{"Lines_Link_Bill":"source-head","Lines_Link_Entry":"source-entry"}]}]}
                """);
        return fixture;
    }
}
