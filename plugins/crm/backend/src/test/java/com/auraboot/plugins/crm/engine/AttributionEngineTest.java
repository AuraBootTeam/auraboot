package com.auraboot.plugins.crm.engine;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden-standard coverage for {@link AttributionEngine}:
 * happy path, sad path, edge case, corner case (CRM gap #13).
 */
class AttributionEngineTest {

    private static final BigDecimal REV = new BigDecimal("900");

    // ---------- happy path ----------
    @Test
    void linearSplitsEvenlyAndSumsToRevenue() {
        var r = AttributionEngine.distribute(List.of("A", "B", "C"), REV, "linear");
        assertEquals(0, r.get("A").compareTo(new BigDecimal("300")));
        assertEquals(0, r.get("B").compareTo(new BigDecimal("300")));
        assertEquals(0, r.get("C").compareTo(new BigDecimal("300")));
        assertEquals(0, sum(r).compareTo(REV));
    }

    @Test
    void firstTouchAllToFirst() {
        var r = AttributionEngine.distribute(List.of("A", "B", "C"), REV, "first_touch");
        assertEquals(0, r.get("A").compareTo(REV));
        assertEquals(0, r.get("B").compareTo(BigDecimal.ZERO));
    }

    @Test
    void lastTouchAllToLast() {
        var r = AttributionEngine.distribute(List.of("A", "B", "C"), REV, "last_touch");
        assertEquals(0, r.get("C").compareTo(REV));
        assertEquals(0, r.get("A").compareTo(BigDecimal.ZERO));
    }

    // ---------- sad path ----------
    @Test
    void negativeRevenueClampsToZero() {
        var r = AttributionEngine.distribute(List.of("A", "B"), new BigDecimal("-100"), "linear");
        assertEquals(0, sum(r).compareTo(BigDecimal.ZERO));
    }

    // ---------- edge case ----------
    @Test
    void emptyTouchpointsYieldEmptyMap() {
        var r = AttributionEngine.distribute(List.of(), REV, "linear");
        assertTrue(r.isEmpty());
    }

    @Test
    void linearRemainderGoesToLastSoSumIsExact() {
        // 100 / 3 = 33.33 each, last gets remainder so total == 100
        var r = AttributionEngine.distribute(List.of("A", "B", "C"), new BigDecimal("100"), "linear");
        assertEquals(0, sum(r).compareTo(new BigDecimal("100")));
        assertEquals(0, r.get("C").compareTo(new BigDecimal("33.34")));
    }

    // ---------- corner cases ----------
    @Test
    void singleTouchpointGets100PercentRegardlessOfModel() {
        for (String model : List.of("first_touch", "last_touch", "linear")) {
            var r = AttributionEngine.distribute(List.of("solo"), REV, model);
            assertEquals(0, r.get("solo").compareTo(REV), "model=" + model);
        }
    }

    @Test
    void unknownModelDefaultsToLastTouch() {
        var r = AttributionEngine.distribute(List.of("A", "B"), REV, "weird_model");
        assertEquals(0, r.get("B").compareTo(REV));
    }

    private static BigDecimal sum(Map<String, BigDecimal> m) {
        return m.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
