package com.auraboot.framework.tenant.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * TenantService integration tests.
 *
 * <p>Covers:
 * <ul>
 *   <li>T1-01 to T1-05: CRUD lifecycle</li>
 *   <li>T2-01 to T2-03: status transitions (activate, deactivate, suspend/resume)</li>
 *   <li>T3-01 to T3-02: query helpers (findByName, findByPid, pagination)</li>
 *   <li>T4-01: isNameAvailable validation</li>
 * </ul>
 */
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TenantServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TenantService tenantService;

    private final String runId = String.valueOf(System.currentTimeMillis());
    private Long tenantId;
    private String tenantPid;

    // ==================== T1: CRUD ====================

    @Test
    @Order(1)
    @DisplayName("T1-01: createTenant persists with correct fields")
    void createTenant_persistsWithCorrectFields() {
        Tenant tenant = buildTenant("tenant-" + runId, "Test Tenant " + runId);

        Tenant saved = tenantService.createTenant(tenant);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getPid()).isNotBlank();
        assertThat(saved.getName()).isEqualTo("tenant-" + runId);
        tenantId = saved.getId();
        tenantPid = saved.getPid();
        log.info("T1-01: created tenant id={}", tenantId);
    }

    @Test
    @Order(2)
    @DisplayName("T1-02: findByName returns the created tenant")
    void findByName_returnsCreatedTenant() {
        assertThat(tenantId).as("tenantId must be set by T1-01").isNotNull();

        Tenant found = tenantService.findByName("tenant-" + runId);

        assertThat(found).isNotNull();
        assertThat(found.getId()).isEqualTo(tenantId);
    }

    @Test
    @Order(3)
    @DisplayName("T1-03: findByPid returns the created tenant")
    void findByPid_returnsCreatedTenant() {
        assertThat(tenantPid).as("tenantPid must be set by T1-01").isNotBlank();

        Tenant found = tenantService.findByPid(tenantPid);

        assertThat(found).isNotNull();
        assertThat(found.getId()).isEqualTo(tenantId);
    }

    @Test
    @Order(4)
    @DisplayName("T1-04: updateTenant changes displayName")
    void updateTenant_changesDisplayName() {
        assertThat(tenantId).as("tenantId must be set by T1-01").isNotNull();
        Tenant existing = tenantService.getById(tenantId);
        existing.setDisplayName("Updated Tenant " + runId);
        existing.setUpdatedAt(Instant.now());

        Tenant updated = tenantService.updateTenant(existing);

        assertThat(updated.getDisplayName()).isEqualTo("Updated Tenant " + runId);
    }

    @Test
    @Order(5)
    @DisplayName("T1-05: getAllTenants includes the created tenant")
    void getAllTenants_includesCreatedTenant() {
        List<Tenant> all = tenantService.getAllTenants();

        assertThat(all).isNotNull().isNotEmpty();
        boolean found = all.stream().anyMatch(t -> t.getId().equals(tenantId));
        assertThat(found).isTrue();
    }

    // ==================== T2: status transitions ====================

    @Test
    @Order(10)
    @DisplayName("T2-01: deactivateTenant changes status to INACTIVE")
    void deactivateTenant_changesStatus() {
        assertThat(tenantId).as("tenantId must be set by T1-01").isNotNull();

        boolean result = tenantService.deactivateTenant(tenantId);

        assertThat(result).isTrue();
        Tenant updated = tenantService.getById(tenantId);
        assertThat(updated.getStatus()).isEqualTo("inactive");
    }

    @Test
    @Order(11)
    @DisplayName("T2-02: activateTenant restores status to ACTIVE")
    void activateTenant_restoresStatus() {
        assertThat(tenantId).as("tenantId must be set by T1-01").isNotNull();

        boolean result = tenantService.activateTenant(tenantId);

        assertThat(result).isTrue();
        Tenant updated = tenantService.getById(tenantId);
        assertThat(updated.getStatus()).isEqualTo("active");
    }

    @Test
    @Order(12)
    @DisplayName("T2-03: suspendTenant + resumeTenant round-trip")
    void suspendTenant_then_resumeTenant_roundTrip() {
        assertThat(tenantId).as("tenantId must be set by T1-01").isNotNull();

        tenantService.suspendTenant(tenantId, "Integration test suspension");
        Tenant suspended = tenantService.getById(tenantId);
        assertThat(suspended.getStatus()).isEqualTo("suspended");

        tenantService.resumeTenant(tenantId);
        Tenant resumed = tenantService.getById(tenantId);
        assertThat(resumed.getStatus()).isEqualTo("active");
    }

    // ==================== T3: query helpers ====================

    @Test
    @Order(20)
    @DisplayName("T3-01: findTenants pagination returns non-empty result")
    void findTenants_pagination_returnsResults() {
        Page<Tenant> page = tenantService.findTenants(1, 10, null, null, "active", null);

        assertThat(page).isNotNull();
        assertThat(page.getTotal()).isGreaterThan(0);
    }

    @Test
    @Order(21)
    @DisplayName("T3-02: countByStatus counts active tenants correctly")
    void countByStatus_countActiveTenantsCorrectly() {
        long count = tenantService.countByStatus("active");
        assertThat(count).isGreaterThan(0);
    }

    @Test
    @Order(22)
    @DisplayName("T3-03: getActiveTenants includes the test tenant")
    void getActiveTenants_includesTestTenant() {
        List<Tenant> active = tenantService.getActiveTenants();

        assertThat(active).isNotNull().isNotEmpty();
        boolean found = active.stream().anyMatch(t -> t.getId().equals(tenantId));
        assertThat(found).isTrue();
    }

    // ==================== T4: validation ====================

    @Test
    @Order(30)
    @DisplayName("T4-01: isNameAvailable returns false for existing name")
    void isNameAvailable_existingName_returnsFalse() {
        boolean available = tenantService.isNameAvailable("tenant-" + runId);
        assertThat(available).isFalse();
    }

    @Test
    @Order(31)
    @DisplayName("T4-02: isNameAvailable returns true for new name")
    void isNameAvailable_newName_returnsTrue() {
        boolean available = tenantService.isNameAvailable("non-existent-tenant-" + runId + "-xyz");
        assertThat(available).isTrue();
    }

    // ==================== helpers ====================

    private Tenant buildTenant(String name, String displayName) {
        Tenant tenant = new Tenant();
        tenant.setPid(UniqueIdGenerator.generate());
        tenant.setName(name);
        tenant.setDisplayName(displayName);
        tenant.setStatus("active");
        tenant.setContactEmail("test+" + runId + "@tenant.test");
        tenant.setDescription("Integration test tenant");
        tenant.setDeletedFlag(false);
        tenant.setCreatedAt(Instant.now());
        tenant.setUpdatedAt(Instant.now());
        return tenant;
    }
}
