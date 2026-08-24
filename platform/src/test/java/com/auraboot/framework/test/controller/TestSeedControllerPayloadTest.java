package com.auraboot.framework.test.controller;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TestSeedControllerPayloadTest {

    @Test
    void crmAccountFixtureIncludesRequiredOwnedPoolState() {
        Map<String, Object> payload = TestSeedController.buildCrmAccountPayload(
                "E2E-ACC-001",
                "E2E Demo Account Alpha",
                "technology",
                "active",
                "A");

        assertThat(payload)
                .containsEntry("crm_acc_code", "E2E-ACC-001")
                .containsEntry("crm_acc_status", "active")
                .containsEntry("crm_acc_pool_state", "owned");
    }
}
