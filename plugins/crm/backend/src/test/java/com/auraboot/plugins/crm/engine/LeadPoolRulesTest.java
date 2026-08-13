package com.auraboot.plugins.crm.engine;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class LeadPoolRulesTest {

    @Test
    void membershipParsingIsTrimmedDistinctAndAdminImpliesMember() {
        assertEquals(2, LeadPoolRules.userIds(" user-a, user-b, user-a ").size());
        assertEquals(java.util.Set.of("user-a", "user-b"),
                LeadPoolRules.userIds("[\"user-a\",\"user-b\"]"));
        assertTrue(LeadPoolRules.isMember("user-a", "manager", "manager"));
        assertFalse(LeadPoolRules.isMember("user-a", "manager", "stranger"));
    }

    @Test
    void cooldownAndRecycleBoundariesAreInclusive() {
        Instant entered = Instant.parse("2026-08-01T00:00:00Z");
        assertFalse(LeadPoolRules.cooldownElapsed(entered, 3, Instant.parse("2026-08-03T23:59:59Z")));
        assertTrue(LeadPoolRules.cooldownElapsed(entered, 3, Instant.parse("2026-08-04T00:00:00Z")));
        assertTrue(LeadPoolRules.shouldRecycle(entered, 3, Instant.parse("2026-08-04T00:00:00Z")));
    }
}
