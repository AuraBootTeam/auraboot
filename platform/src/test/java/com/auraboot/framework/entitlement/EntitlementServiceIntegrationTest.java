package com.auraboot.framework.entitlement;

import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.entitlement.dto.EntitlementSnapshot;
import com.auraboot.framework.entitlement.entity.PluginPlan;
import com.auraboot.framework.entitlement.mapper.PluginPlanMapper;
import com.auraboot.framework.entitlement.service.EntitlementService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link EntitlementService}.
 *
 * <p>Tests execute in order, sharing mutable state through instance fields.
 * The entitlement system is enabled via {@code @TestPropertySource} so that
 * access-control enforcement is exercised on every check.</p>
 *
 * <p>Data is NOT rolled back — test records persist in DB as verification artifacts.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {"auraboot.entitlement.enabled=true"})
public class EntitlementServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private EntitlementService entitlementService;

    @Autowired
    private PluginPlanMapper pluginPlanMapper;

    private final String testRunId = String.valueOf(System.currentTimeMillis());

    // Primary plugin ID shared across most ordered tests
    private final String pluginId = "test-plugin-" + testRunId;

    // =========================================================================
    // Test 1 — system flag
    // =========================================================================

    @Test
    @Order(1)
    void isEnabled_whenEnabled_returnsTrue() {
        assertTrue(entitlementService.isEnabled(),
                "EntitlementService.isEnabled() should return true when property is set to true");
    }

    // =========================================================================
    // Test 2 — unknown plugin with no records
    // =========================================================================

    @Test
    @Order(2)
    void isPluginActive_forUnknownPlugin_returnsFalse() {
        Long tenantId = getTestTenant().getId();
        // Plugin ID that has never been registered — should return false in enabled mode
        assertFalse(entitlementService.isPluginActive(tenantId, pluginId + "-nonexistent"),
                "isPluginActive should return false for a plugin with no entitlement record");
    }

    // =========================================================================
    // Test 3 — createFreeEntitlement
    // =========================================================================

    @Test
    @Order(3)
    void createFreeEntitlement_createsActiveRecord() {
        Long tenantId = getTestTenant().getId();
        assertDoesNotThrow(() -> entitlementService.createFreeEntitlement(tenantId, pluginId),
                "createFreeEntitlement should not throw for a new plugin");
    }

    // =========================================================================
    // Test 4 — plugin is active after free entitlement
    // =========================================================================

    @Test
    @Order(4)
    void isPluginActive_afterFreeEntitlement_returnsTrue() {
        Long tenantId = getTestTenant().getId();
        assertTrue(entitlementService.isPluginActive(tenantId, pluginId),
                "isPluginActive should return true after createFreeEntitlement");
    }

    // =========================================================================
    // Test 5 — hasFeature returns false for unknown feature on FREE plan
    // =========================================================================

    @Test
    @Order(5)
    void hasFeature_withUnknownFeature_returnsFalse() {
        Long tenantId = getTestTenant().getId();
        // FREE plan created in test 3 has no features — so any feature key should be absent
        assertFalse(entitlementService.hasFeature(tenantId, pluginId, "unknown.feature"),
                "hasFeature should return false for a feature key not bound to the FREE plan");
    }

    // =========================================================================
    // Test 6 — disableEntitlement makes plugin inactive
    // =========================================================================

    @Test
    @Order(6)
    void disableEntitlement_marksInactive() {
        Long tenantId = getTestTenant().getId();
        entitlementService.disableEntitlement(tenantId, pluginId, "Test disable");
        assertFalse(entitlementService.isPluginActive(tenantId, pluginId),
                "isPluginActive should return false after disableEntitlement");
    }

    // =========================================================================
    // Test 7 — activateEntitlement with PRO plan
    // =========================================================================

    @Test
    @Order(7)
    void activateEntitlement_createsActiveRecord() {
        Long tenantId = getTestTenant().getId();

        // Create a PRO plan for the test plugin so that activateEntitlement can find it
        PluginPlan proPlan = PluginPlan.builder()
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
        pluginPlanMapper.insert(proPlan);

        // Activate using the PRO plan just created
        Instant expiry = Instant.now().plus(30, ChronoUnit.DAYS);
        assertDoesNotThrow(() -> entitlementService.activateEntitlement(tenantId, pluginId, "pro", expiry),
                "activateEntitlement should not throw when the plan exists");

        assertTrue(entitlementService.isPluginActive(tenantId, pluginId),
                "isPluginActive should return true after activateEntitlement with PRO plan");
    }

    // =========================================================================
    // Test 8 — renewEntitlement extends expiry
    // =========================================================================

    @Test
    @Order(8)
    void renewEntitlement_extendExpiryDate() {
        Long tenantId = getTestTenant().getId();
        Instant newExpiry = Instant.now().plus(60, ChronoUnit.DAYS);
        assertDoesNotThrow(() -> entitlementService.renewEntitlement(tenantId, pluginId, newExpiry),
                "renewEntitlement should not throw for an active entitlement");

        List<EntitlementSnapshot> snapshots = entitlementService.listEntitlements(tenantId);
        EntitlementSnapshot active = snapshots.stream()
                .filter(s -> pluginId.equals(s.getPluginId()) && "active".equals(s.getStatus()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected an ACTIVE snapshot for " + pluginId));

        assertNotNull(active.getExpiresAt(), "Snapshot expiresAt should not be null after renew");
        assertTrue(active.getExpiresAt().isAfter(Instant.now().plus(50, ChronoUnit.DAYS)),
                "expiresAt should be more than 50 days from now after renewal with 60-day expiry");
    }

    // =========================================================================
    // Test 9 — listEntitlements returns records for tenant
    // =========================================================================

    @Test
    @Order(9)
    void listEntitlements_returnsAllForTenant() {
        Long tenantId = getTestTenant().getId();
        List<EntitlementSnapshot> snapshots = entitlementService.listEntitlements(tenantId);
        assertFalse(snapshots.isEmpty(),
                "listEntitlements should return at least one snapshot for the test tenant");
    }

    // =========================================================================
    // Test 10 — cache invalidation after disable
    // =========================================================================

    @Test
    @Order(10)
    void cacheInvalidation_afterDisable_reflectsNewState() {
        Long tenantId = getTestTenant().getId();

        // Warm the cache with an isPluginActive read (plugin is active from test 7/8)
        assertTrue(entitlementService.isPluginActive(tenantId, pluginId),
                "Plugin should be active before disable (pre-condition)");

        // Disable — should immediately invalidate the cache
        entitlementService.disableEntitlement(tenantId, pluginId, "Cache invalidation test");

        // Next read must reflect the new DISABLED state without stale cache
        assertFalse(entitlementService.isPluginActive(tenantId, pluginId),
                "isPluginActive should return false immediately after disableEntitlement (cache invalidated)");
    }

    // =========================================================================
    // Test 11 — plugin with no entitlement returns false (enabled mode)
    // =========================================================================

    @Test
    @Order(11)
    void isPluginActive_withNoEntitlement_returnsFalse() {
        Long tenantId = getTestTenant().getId();
        String freshPlugin = "nope-" + testRunId;

        // No entitlement created for this plugin — enabled mode must enforce access control
        assertFalse(entitlementService.isPluginActive(tenantId, freshPlugin),
                "isPluginActive should return false for a plugin that has no entitlement record (enabled mode)");
    }

    // =========================================================================
    // Test 12 — activate then disable lifecycle
    // =========================================================================

    @Test
    @Order(12)
    void activateEntitlement_thenDisable_gracefullyRemovesAccess() {
        Long tenantId = getTestTenant().getId();
        String freshPlugin = "lifecycle-" + testRunId;

        // Step 1: create a free entitlement → should be active
        entitlementService.createFreeEntitlement(tenantId, freshPlugin);
        assertTrue(entitlementService.isPluginActive(tenantId, freshPlugin),
                "Plugin should be active immediately after createFreeEntitlement");

        // Step 2: disable → should no longer be active
        entitlementService.disableEntitlement(tenantId, freshPlugin, "Graceful removal test");
        assertFalse(entitlementService.isPluginActive(tenantId, freshPlugin),
                "Plugin should be inactive after disableEntitlement");
    }
}
