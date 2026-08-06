package com.auraboot.plugins.crm.engine;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LeadScoringEngineTest {

    private static Map<String, Object> lead(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static LeadScoringEngine.Rule rule(String dim, String op, String val, int pts) {
        return new LeadScoringEngine.Rule("r-" + dim + "-" + op, dim, op, val, pts, 100);
    }

    @Test
    void sumsPointsOfAllMatchingRules() {
        var rules = List.of(
                rule("lead_source", "equals", "referral", 30),
                rule("industry", "equals", "tech", 20),
                rule("contact_email", "is_present", null, 10));
        var lead = lead("crm_lead_source", "referral", "crm_lead_industry", "tech",
                "crm_lead_contact_email", "a@b.com");

        var result = LeadScoringEngine.score(lead, rules);

        assertEquals(60, result.totalScore());
        assertEquals(3, result.matched().size());
    }

    @Test
    void nonMatchingRulesContributeZero() {
        var rules = List.of(
                rule("lead_source", "equals", "referral", 30),
                rule("industry", "equals", "finance", 20));
        var lead = lead("crm_lead_source", "web", "crm_lead_industry", "tech");

        var result = LeadScoringEngine.score(lead, rules);

        assertEquals(0, result.totalScore());
        assertTrue(result.matched().isEmpty());
    }

    @Test
    void equalsIsCaseInsensitive() {
        var rules = List.of(rule("lead_source", "equals", "Referral", 30));
        var result = LeadScoringEngine.score(lead("crm_lead_source", "REFERRAL"), rules);
        assertEquals(30, result.totalScore());
    }

    @Test
    void negativePointsLowerScore() {
        var rules = List.of(
                rule("lead_source", "equals", "cold_list", -15),
                rule("contact_phone", "is_present", null, 10));
        var lead = lead("crm_lead_source", "cold_list", "crm_lead_contact_phone", "12345");
        assertEquals(-5, LeadScoringEngine.score(lead, rules).totalScore());
    }

    @Test
    void isPresentAndIsAbsent() {
        assertTrue(LeadScoringEngine.matches("x", "is_present", null));
        assertFalse(LeadScoringEngine.matches(null, "is_present", null));
        assertFalse(LeadScoringEngine.matches("  ", "is_present", null));
        assertTrue(LeadScoringEngine.matches(null, "is_absent", null));
        assertTrue(LeadScoringEngine.matches("", "is_absent", null));
        assertFalse(LeadScoringEngine.matches("x", "is_absent", null));
    }

    @Test
    void containsMatchesSubstringCaseInsensitive() {
        assertTrue(LeadScoringEngine.matches("Need 500 units urgently", "contains", "urgent"));
        assertFalse(LeadScoringEngine.matches("hello", "contains", "world"));
    }

    @Test
    void notEqualsTreatsAbsentAsNotEqual() {
        assertTrue(LeadScoringEngine.matches(null, "not_equals", "tech"));
        assertTrue(LeadScoringEngine.matches("finance", "not_equals", "tech"));
        assertFalse(LeadScoringEngine.matches("tech", "not_equals", "tech"));
    }

    @Test
    void lengthGteOnRequirementText() {
        var rules = List.of(rule("requirement", "length_gte", "20", 15));
        var longReq = lead("crm_lead_requirement", "We need a full ERP rollout next quarter");
        var shortReq = lead("crm_lead_requirement", "hi");
        assertEquals(15, LeadScoringEngine.score(longReq, rules).totalScore());
        assertEquals(0, LeadScoringEngine.score(shortReq, rules).totalScore());
    }

    @Test
    void numericComparatorsOnActivityCount() {
        var gte3 = List.of(rule("activity_count", "gte", "3", 25));
        assertEquals(25, LeadScoringEngine.score(lead("activity_count", 5), gte3).totalScore());
        assertEquals(25, LeadScoringEngine.score(lead("activity_count", 3), gte3).totalScore());
        assertEquals(0, LeadScoringEngine.score(lead("activity_count", 2), gte3).totalScore());

        var gt0 = List.of(rule("activity_count", "gt", "0", 5));
        assertEquals(5, LeadScoringEngine.score(lead("activity_count", 1), gt0).totalScore());
        assertEquals(0, LeadScoringEngine.score(lead("activity_count", 0), gt0).totalScore());
    }

    @Test
    void activityCountDimensionReadsSyntheticKey() {
        assertEquals("activity_count", LeadScoringEngine.dimensionField("activity_count"));
        assertEquals("crm_lead_source", LeadScoringEngine.dimensionField("lead_source"));
    }

    @Test
    void unknownOperatorThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> LeadScoringEngine.matches("x", "between", "1"));
    }

    @Test
    void numericOperatorOnNonNumericThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> LeadScoringEngine.matches("abc", "gte", "3"));
    }

    @Test
    void deterministicAcrossRuns() {
        var rules = List.of(
                rule("lead_source", "equals", "referral", 30),
                rule("activity_count", "gte", "2", 20));
        var lead = lead("crm_lead_source", "referral", "activity_count", 4);
        int first = LeadScoringEngine.score(lead, rules).totalScore();
        int second = LeadScoringEngine.score(lead, rules).totalScore();
        assertEquals(first, second);
        assertEquals(50, first);
    }
}
