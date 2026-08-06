package com.auraboot.plugins.crm.engine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden-standard coverage for {@link SegmentCriteriaEngine}:
 * happy path, sad path, edge case, corner case (CRM gap #11).
 */
class SegmentCriteriaEngineTest {

    private static SegmentCriteriaEngine.Criterion crit(String f, String op, String v) {
        return new SegmentCriteriaEngine.Criterion(f, op, v);
    }

    private static final Map<String, Object> TECH_BJ = Map.of(
            "crm_con_industry", "tech", "crm_con_city", "Beijing", "crm_con_name", "Acme Corp");

    // ---------- happy path ----------
    @Test
    void recordMatchingAllCriteria() {
        var c = List.of(crit("crm_con_industry", "eq", "tech"), crit("crm_con_city", "eq", "Beijing"));
        assertTrue(SegmentCriteriaEngine.matches(TECH_BJ, c));
    }

    // ---------- sad path ----------
    @Test
    void recordFailingOneCriterionDoesNotMatch() {
        var c = List.of(crit("crm_con_industry", "eq", "tech"), crit("crm_con_city", "eq", "Shanghai"));
        assertFalse(SegmentCriteriaEngine.matches(TECH_BJ, c));
    }

    @Test
    void neOperatorExcludesMatchingValue() {
        var c = List.of(crit("crm_con_industry", "ne", "tech"));
        assertFalse(SegmentCriteriaEngine.matches(TECH_BJ, c));
    }

    @Test
    void containsOperatorMatchesSubstring() {
        var c = List.of(crit("crm_con_name", "contains", "Acme"));
        assertTrue(SegmentCriteriaEngine.matches(TECH_BJ, c));
    }

    // ---------- edge case ----------
    @Test
    void emptyCriteriaMatchesEveryRecord() {
        assertTrue(SegmentCriteriaEngine.matches(TECH_BJ, List.of()));
    }

    @Test
    void nullRecordWithCriteriaDoesNotMatch() {
        assertFalse(SegmentCriteriaEngine.matches(null, List.of(crit("x", "eq", "y"))));
    }

    // ---------- corner cases ----------
    @Test
    void absentFieldDoesNotMatch() {
        var c = List.of(crit("crm_con_missing", "eq", "anything"));
        assertFalse(SegmentCriteriaEngine.matches(TECH_BJ, c));
    }

    @Test
    void unknownOperatorDoesNotMatch() {
        var c = List.of(crit("crm_con_industry", "gt", "tech"));
        assertFalse(SegmentCriteriaEngine.matches(TECH_BJ, c));
    }

    @Test
    void countMatchesAcrossPopulation() {
        var pop = List.<Map<String, Object>>of(
                Map.of("crm_con_industry", "tech"),
                Map.of("crm_con_industry", "finance"),
                Map.of("crm_con_industry", "tech"));
        assertEquals(2, SegmentCriteriaEngine.countMatches(pop, List.of(crit("crm_con_industry", "eq", "tech"))));
    }
}
