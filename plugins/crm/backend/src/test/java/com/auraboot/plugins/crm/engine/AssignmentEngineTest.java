package com.auraboot.plugins.crm.engine;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AssignmentEngineTest {

    private static Map<String, Object> lead(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void parsePoolTrimsAndDropsBlanks() {
        assertEquals(List.of("u1", "u2", "u3"),
                AssignmentEngine.parsePool(" u1 , u2,,u3 , "));
        assertTrue(AssignmentEngine.parsePool(null).isEmpty());
        assertTrue(AssignmentEngine.parsePool("  ").isEmpty());
    }

    @Test
    void roundRobinCyclesPoolAndAdvancesCursor() {
        var rule = new AssignmentEngine.Rule("rr", "round_robin", null, null,
                List.of("u1", "u2", "u3"), 10, 0);
        var a0 = AssignmentEngine.assign(lead(), List.of(rule), Map.of());
        assertEquals("u1", a0.repId());
        assertEquals(1, a0.nextCursor());

        var ruleAt1 = new AssignmentEngine.Rule("rr", "round_robin", null, null,
                List.of("u1", "u2", "u3"), 10, 1);
        assertEquals("u2", AssignmentEngine.assign(lead(), List.of(ruleAt1), Map.of()).repId());

        var ruleAt2 = new AssignmentEngine.Rule("rr", "round_robin", null, null,
                List.of("u1", "u2", "u3"), 10, 2);
        var a2 = AssignmentEngine.assign(lead(), List.of(ruleAt2), Map.of());
        assertEquals("u3", a2.repId());
        assertEquals(0, a2.nextCursor(), "cursor wraps back to 0 after the last rep");
    }

    @Test
    void roundRobinCursorWrapsWhenOutOfRange() {
        var rule = new AssignmentEngine.Rule("rr", "round_robin", null, null,
                List.of("u1", "u2"), 10, 5);
        // 5 % 2 = 1 -> u2
        assertEquals("u2", AssignmentEngine.assign(lead(), List.of(rule), Map.of()).repId());
    }

    @Test
    void byTerritoryMatchesFieldAndAssignsTargetRep() {
        var rule = new AssignmentEngine.Rule("terr", "by_territory", "crm_lead_industry", "tech",
                List.of("tech-rep"), 10, 0);
        var match = AssignmentEngine.assign(lead("crm_lead_industry", "tech"), List.of(rule), Map.of());
        assertEquals("tech-rep", match.repId());
        assertNull(match.nextCursor());

        var noMatch = AssignmentEngine.assign(lead("crm_lead_industry", "finance"), List.of(rule), Map.of());
        assertNull(noMatch, "territory rule with non-matching field does not assign");
    }

    @Test
    void byTerritoryIsCaseInsensitive() {
        var rule = new AssignmentEngine.Rule("terr", "by_territory", "crm_lead_source", "Referral",
                List.of("ref-rep"), 10, 0);
        assertEquals("ref-rep",
                AssignmentEngine.assign(lead("crm_lead_source", "REFERRAL"), List.of(rule), Map.of()).repId());
    }

    @Test
    void byLoadAssignsLeastLoadedRep() {
        var rule = new AssignmentEngine.Rule("load", "by_load", null, null,
                List.of("u1", "u2", "u3"), 10, 0);
        var loads = Map.of("u1", 7, "u2", 2, "u3", 5);
        assertEquals("u2", AssignmentEngine.assign(lead(), List.of(rule), loads).repId());
    }

    @Test
    void byLoadTreatsMissingLoadAsZeroAndBreaksTiesByPoolOrder() {
        var rule = new AssignmentEngine.Rule("load", "by_load", null, null,
                List.of("u1", "u2"), 10, 0);
        // u1 has no entry (=0), u2 has 3 -> u1 wins
        assertEquals("u1", AssignmentEngine.assign(lead(), List.of(rule), Map.of("u2", 3)).repId());
        // tie at 0 -> first in pool order
        assertEquals("u1", AssignmentEngine.assign(lead(), List.of(rule), Map.of()).repId());
    }

    @Test
    void priorityOrderFirstMatchWins() {
        var territory = new AssignmentEngine.Rule("terr", "by_territory", "crm_lead_industry", "tech",
                List.of("tech-rep"), 10, 0);
        var catchAll = new AssignmentEngine.Rule("rr", "round_robin", null, null,
                List.of("pool-rep"), 20, 0);
        // rules supplied already priority-sorted (10 before 20)
        var rules = List.of(territory, catchAll);
        // tech lead matches the higher-priority territory rule
        assertEquals("tech-rep",
                AssignmentEngine.assign(lead("crm_lead_industry", "tech"), rules, Map.of()).repId());
        // non-tech lead falls through to the catch-all round-robin
        assertEquals("pool-rep",
                AssignmentEngine.assign(lead("crm_lead_industry", "finance"), rules, Map.of()).repId());
    }

    @Test
    void noMatchingRuleReturnsNullForFailLoud() {
        var rule = new AssignmentEngine.Rule("terr", "by_territory", "crm_lead_industry", "tech",
                List.of("tech-rep"), 10, 0);
        assertNull(AssignmentEngine.assign(lead("crm_lead_industry", "retail"), List.of(rule), Map.of()));
        assertNull(AssignmentEngine.assign(lead(), List.of(), Map.of()));
    }

    @Test
    void matchedRuleWithEmptyPoolThrows() {
        var rule = new AssignmentEngine.Rule("rr", "round_robin", null, null, List.of(), 10, 0);
        assertThrows(IllegalStateException.class,
                () -> AssignmentEngine.assign(lead(), List.of(rule), Map.of()));
    }

    @Test
    void unknownStrategyThrows() {
        var rule = new AssignmentEngine.Rule("x", "by_phase_of_moon", null, null, List.of("u1"), 10, 0);
        assertThrows(IllegalStateException.class,
                () -> AssignmentEngine.assign(lead(), List.of(rule), Map.of()));
    }
}
