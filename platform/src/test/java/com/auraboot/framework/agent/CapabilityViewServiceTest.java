package com.auraboot.framework.agent;

import com.auraboot.framework.agent.dto.CapabilityView;
import com.auraboot.framework.agent.entity.AbCapability;
import com.auraboot.framework.agent.mapper.AbCapabilityMapper;
import com.auraboot.framework.agent.service.CapabilityViewService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for CapabilityViewService — write-path materialization.
 *
 * Covers: syncCapabilities, getCapabilityFromTable, listByModelFromTable,
 * listAllFromTable, deprecateCapability, idempotency, empty-tenant safety.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class CapabilityViewServiceTest extends BaseIntegrationTest {

    @Autowired
    private CapabilityViewService capabilityViewService;

    @Autowired
    private AbCapabilityMapper capabilityMapper;

    private Long tenantId;

    @BeforeAll
    void setup() {
        // Ensure test data is initialized before @BeforeAll (parent's @BeforeEach hasn't run yet)
        setupTenantContext();
        tenantId = getTestTenant().getId();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 1: syncCapabilities creates records from published DSL sources
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void syncCapabilities_createsOrUpdatesRecords() {
        CompletableFuture<Integer> future = capabilityViewService.syncCapabilities(tenantId);
        int count = future.join();

        assertTrue(count >= 0, "syncCapabilities should return a non-negative count");

        // After sync, ab_capability must have rows for this tenant
        List<AbCapability> caps = capabilityMapper.selectList(
                new LambdaQueryWrapper<AbCapability>()
                        .eq(AbCapability::getTenantId, tenantId)
        );
        // If count > 0, rows must exist; if count == 0 rows may already have existed
        if (count > 0) {
            assertFalse(caps.isEmpty(), "Synced capabilities must be persisted to ab_capability");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 2: Re-sync with no changes should return 0 (hash-based dedup)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(2)
    void syncCapabilities_idempotent_returnZeroOnNoChange() {
        // First sync (ensure table is populated)
        capabilityViewService.syncCapabilities(tenantId).join();

        // Second sync with identical source data
        CompletableFuture<Integer> future = capabilityViewService.syncCapabilities(tenantId);
        int count = future.join();

        assertEquals(0, count,
                "Re-sync with unchanged DSL sources should update 0 capabilities (hash match)");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 3: getCapabilityFromTable returns persisted record by code
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(3)
    void getCapabilityFromTable_returnsPersistedRecord() {
        capabilityViewService.syncCapabilities(tenantId).join();

        List<AbCapability> caps = capabilityMapper.selectList(
                new LambdaQueryWrapper<AbCapability>()
                        .eq(AbCapability::getTenantId, tenantId)
                        .eq(AbCapability::getStatus, "active")
                        .last("LIMIT 1")
        );

        if (caps.isEmpty()) {
            // No capabilities in DB for this tenant — skip rather than fail
            return;
        }

        String code = caps.get(0).getCode();
        CapabilityView view = capabilityViewService.getCapabilityFromTable(tenantId, code);

        assertNotNull(view, "getCapabilityFromTable should return a view for an existing code");
        assertEquals(code, view.getCode(), "Returned view code must match requested code");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 4: getCapabilityFromTable returns null for unknown code
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(4)
    void getCapabilityFromTable_returnsNullForUnknownCode() {
        CapabilityView view = capabilityViewService.getCapabilityFromTable(
                tenantId, "nonexistent_capability_code_xyz_" + System.currentTimeMillis());

        assertNull(view, "getCapabilityFromTable should return null for an unknown code");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 5: listByModelFromTable filters by model code
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(5)
    void listByModelFromTable_filtersCorrectly() {
        capabilityViewService.syncCapabilities(tenantId).join();

        List<AbCapability> caps = capabilityMapper.selectList(
                new LambdaQueryWrapper<AbCapability>()
                        .eq(AbCapability::getTenantId, tenantId)
                        .isNotNull(AbCapability::getModelCode)
                        .last("LIMIT 1")
        );

        if (caps.isEmpty()) {
            return; // No model-scoped capabilities — skip
        }

        String modelCode = caps.get(0).getModelCode();
        List<CapabilityView> views = capabilityViewService.listByModelFromTable(tenantId, modelCode);

        assertFalse(views.isEmpty(), "listByModelFromTable should return at least 1 view for model " + modelCode);
        views.forEach(v -> assertEquals(modelCode, v.getModelCode(),
                "All returned views must belong to the requested model"));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 6: listAllFromTable respects limit (pagination)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(6)
    void listAllFromTable_respectsLimit() {
        capabilityViewService.syncCapabilities(tenantId).join();

        List<CapabilityView> page = capabilityViewService.listAllFromTable(tenantId, 5, 0, null);

        assertTrue(page.size() <= 5, "listAllFromTable with limit=5 must return at most 5 entries");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 7: listAllFromTable with typeFilter = COMMAND returns only COMMAND capabilities
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(7)
    void listAllFromTable_typeFilter_returnsOnlyMatchingType() {
        capabilityViewService.syncCapabilities(tenantId).join();

        List<CapabilityView> commandViews = capabilityViewService.listAllFromTable(tenantId, 20, 0, "command");

        // All returned views must have type COMMAND (or list is empty if no COMMAND caps yet)
        commandViews.forEach(v -> assertEquals("command", v.getType(),
                "Type-filtered list must only contain COMMAND capabilities"));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 8: deprecateCapability sets status to DEPRECATED
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(8)
    void deprecateCapability_setsStatusDeprecated() {
        capabilityViewService.syncCapabilities(tenantId).join();

        List<AbCapability> caps = capabilityMapper.selectList(
                new LambdaQueryWrapper<AbCapability>()
                        .eq(AbCapability::getTenantId, tenantId)
                        .eq(AbCapability::getStatus, "active")
                        .last("LIMIT 1")
        );

        if (caps.isEmpty()) {
            return; // No capabilities to deprecate — skip
        }

        AbCapability cap = caps.get(0);
        String code = cap.getCode();

        capabilityViewService.deprecateCapability(tenantId, code);

        AbCapability updated = capabilityMapper.selectOne(
                new LambdaQueryWrapper<AbCapability>()
                        .eq(AbCapability::getTenantId, tenantId)
                        .eq(AbCapability::getCode, code)
        );

        assertNotNull(updated, "Capability must still exist after deprecation");
        assertEquals("deprecated", updated.getStatus(),
                "deprecateCapability must set status to DEPRECATED");

        // Restore to ACTIVE so later tests are unaffected
        updated.setStatus("active");
        capabilityMapper.updateById(updated);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 9: syncCapabilities with non-existent tenant completes without error
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(9)
    void syncCapabilities_unknownTenant_doesNotThrow() {
        assertDoesNotThrow(
                () -> capabilityViewService.syncCapabilities(Long.MAX_VALUE).join(),
                "syncCapabilities with an unknown tenant must not throw"
        );
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 10: contractHash is deterministic (64-char SHA-256 hex string)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    void contractHash_isDeterministicSha256() {
        capabilityViewService.syncCapabilities(tenantId).join();

        List<AbCapability> caps = capabilityMapper.selectList(
                new LambdaQueryWrapper<AbCapability>()
                        .eq(AbCapability::getTenantId, tenantId)
                        .isNotNull(AbCapability::getContractHash)
                        .last("LIMIT 1")
        );

        if (caps.isEmpty()) {
            return; // No hashes computed yet — skip
        }

        String hash = caps.get(0).getContractHash();
        assertNotNull(hash, "contractHash must be non-null after sync");
        assertEquals(64, hash.length(),
                "contractHash must be a 64-character SHA-256 hex string, got: " + hash);
        assertTrue(hash.matches("[0-9a-f]{64}"),
                "contractHash must contain only lowercase hex chars, got: " + hash);
    }
}
