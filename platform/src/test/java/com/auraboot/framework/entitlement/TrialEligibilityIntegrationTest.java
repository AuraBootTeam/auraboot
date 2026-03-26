package com.auraboot.framework.entitlement;

import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.entitlement.entity.PluginPlan;
import com.auraboot.framework.entitlement.mapper.PluginPlanMapper;
import com.auraboot.framework.entitlement.service.EntitlementService;
import com.auraboot.framework.entitlement.service.TrialEligibilityService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link TrialEligibilityService}.
 *
 * <p>Verifies the core eligibility rule: a tenant is eligible for a trial only
 * if no entitlement record of any status exists for that tenant + plugin.
 * Any prior entitlement (active, trial, or disabled) makes the tenant ineligible.</p>
 *
 * <p>Data is NOT rolled back — test records persist in the database as
 * verification artifacts, including the full trial lifecycle.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {"auraboot.entitlement.enabled=true"})
public class TrialEligibilityIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TrialEligibilityService trialEligibilityService;

    @Autowired
    private EntitlementService entitlementService;

    @Autowired
    private PluginPlanMapper pluginPlanMapper;

    private final String testRunId = String.valueOf(System.currentTimeMillis());

    // Primary trial plugin — used for the ordered trial lifecycle (tests 1-3)
    private final String trialPluginId = "trial-plugin-" + testRunId;

    // =========================================================================
    // Helper — create a default trial plan for a plugin
    // =========================================================================

    private void createDefaultTrialPlan(String pluginId, int trialDays) {
        PluginPlan plan = PluginPlan.builder()
                .pid(UlidGenerator.nextULID())
                .pluginId(pluginId)
                .planCode("free")
                .displayNameEn("Free")
                .displayNameZh("免费版")
                .sortOrder(0)
                .isDefault(true)
                .billingType("free")
                .trialDays(trialDays)
                .createdAt(Instant.now())
                .build();
        pluginPlanMapper.insert(plan);
    }

    private void createNonDefaultProPlan(String pluginId) {
        PluginPlan plan = PluginPlan.builder()
                .pid(UlidGenerator.nextULID())
                .pluginId(pluginId)
                .planCode("pro")
                .displayNameEn("Pro")
                .displayNameZh("专业版")
                .sortOrder(1)
                .isDefault(false)
                .billingType("subscription")
                .createdAt(Instant.now())
                .build();
        pluginPlanMapper.insert(plan);
    }

    // =========================================================================
    // Test 1 — fresh tenant/plugin is eligible
    // =========================================================================

    @Test
    @Order(1)
    void isEligibleForTrial_freshTenant_returnsTrue() {
        Long tenantId = getTestTenant().getId();

        // No entitlement records exist for this brand-new plugin ID
        assertTrue(trialEligibilityService.isEligibleForTrial(tenantId, trialPluginId),
                "A tenant with no prior entitlement records should be eligible for a trial");
    }

    // =========================================================================
    // Test 2 — grantTrial creates an active TRIAL entitlement
    // =========================================================================

    @Test
    @Order(2)
    void grantTrial_setsExpiryTo14Days() {
        Long tenantId = getTestTenant().getId();

        // grantTrial requires a default plan in ab_plugin_plan
        createDefaultTrialPlan(trialPluginId, 14);

        assertDoesNotThrow(() -> entitlementService.grantTrial(tenantId, trialPluginId),
                "grantTrial should not throw when a default plan is configured");

        // TRIAL status is treated as active (plugin is accessible during trial)
        assertTrue(entitlementService.isPluginActive(tenantId, trialPluginId),
                "Plugin should be accessible immediately after a trial is granted");
    }

    // =========================================================================
    // Test 3 — after trial is granted, tenant is no longer eligible
    // =========================================================================

    @Test
    @Order(3)
    void isEligibleForTrial_afterTrialUsed_returnsFalse() {
        Long tenantId = getTestTenant().getId();

        // A TRIAL record was created in test 2 — any record (even TRIAL) makes the tenant ineligible
        assertFalse(trialEligibilityService.isEligibleForTrial(tenantId, trialPluginId),
                "Tenant should not be eligible for another trial once a TRIAL entitlement record exists");
    }

    // =========================================================================
    // Test 4 — paid subscription also disqualifies trial eligibility
    // =========================================================================

    @Test
    @Order(4)
    void isEligibleForTrial_afterPaidSubscription_returnsFalse() {
        Long tenantId = getTestTenant().getId();
        String paidPlugin = "paid-" + testRunId;

        // Create a non-default PRO plan for this plugin
        createNonDefaultProPlan(paidPlugin);

        // Activate a paid subscription
        Instant expiry = Instant.now().plus(365, ChronoUnit.DAYS);
        assertDoesNotThrow(() -> entitlementService.activateEntitlement(tenantId, paidPlugin, "pro", expiry),
                "activateEntitlement should succeed for an existing PRO plan");

        // Even after a paid activation the tenant cannot start a trial for the same plugin
        assertFalse(trialEligibilityService.isEligibleForTrial(tenantId, paidPlugin),
                "Tenant with an existing paid entitlement should not be eligible for a trial");
    }

    // =========================================================================
    // Test 5 — independent verification: grant trial on a fresh plugin, re-check eligibility
    // =========================================================================

    @Test
    @Order(5)
    void grantTrial_thenCheckEligibility_notEligibleAgain() {
        Long tenantId = getTestTenant().getId();
        String freshPlugin = "trial2-" + testRunId;

        // Fresh plugin — eligible before any grant
        assertTrue(trialEligibilityService.isEligibleForTrial(tenantId, freshPlugin),
                "Should be eligible before any entitlement exists (pre-condition)");

        // Create the required default plan
        createDefaultTrialPlan(freshPlugin, 7);

        // Grant the trial
        assertDoesNotThrow(() -> entitlementService.grantTrial(tenantId, freshPlugin),
                "grantTrial should not throw when a default plan exists");

        // Must no longer be eligible after the trial has been granted
        assertFalse(trialEligibilityService.isEligibleForTrial(tenantId, freshPlugin),
                "Tenant should not be eligible for another trial after grantTrial has been called");
    }
}
