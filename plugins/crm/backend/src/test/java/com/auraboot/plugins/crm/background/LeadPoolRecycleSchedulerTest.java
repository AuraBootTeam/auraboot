package com.auraboot.plugins.crm.background;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeadPoolRecycleSchedulerTest {

    @Test
    void skipsOnlyTheExactMissingCrmModelCondition() {
        assertTrue(LeadPoolRecycleScheduler.isCrmModelAbsent(
                new IllegalStateException("wrapper",
                        new RuntimeException("Model not found: crm_lead_pool"))));
        assertFalse(LeadPoolRecycleScheduler.isCrmModelAbsent(
                new RuntimeException("Model not found: crm_lead_common")));
        assertFalse(LeadPoolRecycleScheduler.isCrmModelAbsent(
                new RuntimeException("database unavailable")));
    }
}
