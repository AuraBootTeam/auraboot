package com.auraboot.framework.agent.memory.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ConflictMetrics — pure function unit tests")
class ConflictMetricsTest {

    private static ConflictAnnotation anno(String tag) {
        return new ConflictAnnotation("s", 1L, "a", 3, tag, List.of(), "", "");
    }

    @Test
    @DisplayName("conflictRate throws on empty input")
    void emptyThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> ConflictMetrics.conflictRate(List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> ConflictMetrics.conflictRate(null));
    }

    @Test
    @DisplayName("conflictRate counts temporal+factual+granularity as conflicts, not 'unclear'")
    void mixedDistribution() {
        // 5 annotations: 2 conflict, 2 no-conflict, 1 unclear → 2 / (5-1) = 0.5
        double rate = ConflictMetrics.conflictRate(List.of(
                anno("temporal-conflict"),
                anno("factual-conflict"),
                anno("no-conflict"),
                anno("no-conflict"),
                anno("unclear")
        ));
        assertEquals(0.5, rate, 1e-9);
    }

    @Test
    @DisplayName("conflictRate = 0.0 when no conflicts")
    void zeroRate() {
        double rate = ConflictMetrics.conflictRate(List.of(
                anno("no-conflict"), anno("no-conflict")));
        assertEquals(0.0, rate, 1e-9);
    }

    @Test
    @DisplayName("conflictRate = 1.0 when all conflicts")
    void oneRate() {
        double rate = ConflictMetrics.conflictRate(List.of(
                anno("temporal-conflict"),
                anno("granularity-conflict"),
                anno("factual-conflict")));
        assertEquals(1.0, rate, 1e-9);
    }

    @Test
    @DisplayName("conflictRate = NaN when every annotation is unclear")
    void allUnclearNaN() {
        double rate = ConflictMetrics.conflictRate(List.of(
                anno("unclear"), anno("unclear")));
        assertTrue(Double.isNaN(rate));
    }

    @Test
    @DisplayName("justifiesDualZoneSchema: ≥5% = yes")
    void thresholdAbove() {
        assertTrue(ConflictMetrics.justifiesDualZoneSchema(0.05));
        assertTrue(ConflictMetrics.justifiesDualZoneSchema(0.30));
    }

    @Test
    @DisplayName("justifiesDualZoneSchema: <5% = no")
    void thresholdBelow() {
        assertFalse(ConflictMetrics.justifiesDualZoneSchema(0.04));
        assertFalse(ConflictMetrics.justifiesDualZoneSchema(0.0));
    }

    @Test
    @DisplayName("justifiesDualZoneSchema: NaN = no (escalate, do not auto-justify)")
    void nanIsNotJustification() {
        assertFalse(ConflictMetrics.justifiesDualZoneSchema(Double.NaN));
    }

    @Test
    @DisplayName("distribution covers all enum values, zero for missing tags")
    void distributionAllKeys() {
        var dist = ConflictMetrics.distribution(List.of(anno("temporal-conflict")));
        assertEquals(5, dist.size());
        assertEquals(1, dist.get(ConflictTag.TEMPORAL_CONFLICT));
        assertEquals(0, dist.get(ConflictTag.NO_CONFLICT));
        assertEquals(0, dist.get(ConflictTag.UNCLEAR));
    }

    @Test
    @DisplayName("effectiveTag honours second_reviewer_tag when primary is unclear")
    void secondReviewerOverridesUnclear() {
        ConflictAnnotation a = new ConflictAnnotation(
                "s", 1L, "a", 3, "unclear", List.of(), "tied",
                "factual-conflict");
        assertEquals(ConflictTag.FACTUAL_CONFLICT, a.effectiveTag());
    }

    @Test
    @DisplayName("effectiveTag returns primary when second_reviewer_tag is blank")
    void secondReviewerBlankKeepsPrimary() {
        ConflictAnnotation a = new ConflictAnnotation(
                "s", 1L, "a", 3, "no-conflict", List.of(), "", "");
        assertEquals(ConflictTag.NO_CONFLICT, a.effectiveTag());
    }

    @Test
    @DisplayName("fromWire throws on unknown tag string")
    void fromWireUnknownThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> ConflictTag.fromWire("bogus"));
    }
}
