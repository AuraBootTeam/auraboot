package com.auraboot.framework.rbac.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.rbac.entity.Role;
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
 * RoleService integration tests.
 *
 * <p>Covers:
 * <ul>
 *   <li>R1-01 to R1-05: CRUD lifecycle</li>
 *   <li>R2-01 to R2-03: duplicate code, pagination, list by tenant</li>
 *   <li>R3-01 to R3-02: enable/disable</li>
 *   <li>R4-01: copy role</li>
 * </ul>
 */
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RoleServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private RoleService roleService;

    private final String runId = String.valueOf(System.currentTimeMillis());
    private Long roleId;
    private String rolePid;

    // ==================== R1: CRUD ====================

    @Test
    @Order(1)
    @DisplayName("R1-01: createRole persists with correct fields")
    void createRole_persistsWithCorrectFields() {
        Role role = buildRole("analyst-" + runId, "Analyst " + runId, "custom");

        Role saved = roleService.createRole(role);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getPid()).isNotBlank();
        assertThat(saved.getStatus()).isEqualTo("active");
        assertThat(saved.getDeletedFlag()).isFalse();
        roleId = saved.getId();
        rolePid = saved.getPid();
        log.info("R1-01: created role id={}", roleId);
    }

    @Test
    @Order(2)
    @DisplayName("R1-02: findByPid returns the created role")
    void findByPid_returnsRole() {
        assertThat(rolePid).as("rolePid must be set by R1-01").isNotBlank();

        Role found = roleService.findByPid(rolePid);

        assertThat(found).isNotNull();
        assertThat(found.getId()).isEqualTo(roleId);
        assertThat(found.getCode()).isEqualTo("analyst-" + runId);
    }

    @Test
    @Order(3)
    @DisplayName("R1-03: updateRole changes name")
    void updateRole_changesName() {
        assertThat(roleId).as("roleId must be set by R1-01").isNotNull();
        Role existing = roleService.getById(roleId);
        existing.setName("Senior Analyst " + runId);
        existing.setUpdatedAt(Instant.now());

        Role updated = roleService.updateRole(existing);

        assertThat(updated.getName()).isEqualTo("Senior Analyst " + runId);
    }

    @Test
    @Order(4)
    @DisplayName("R1-04: findByTenantId includes the created role")
    void findByTenantId_includesCreatedRole() {
        List<Role> roles = roleService.findByTenantId(getTestTenant().getId());

        assertThat(roles).isNotNull();
        boolean found = roles.stream().anyMatch(r -> r.getId().equals(roleId));
        assertThat(found).as("Created role should be in tenant role list").isTrue();
    }

    @Test
    @Order(5)
    @DisplayName("R1-05: deleteRole soft-deletes the role")
    void deleteRole_softDeletesRole() {
        assertThat(roleId).as("roleId must be set by R1-01").isNotNull();
        // Create a separate role to delete (don't delete the main test role yet)
        Role toDelete = buildRole("TO-DEL-" + runId, "To Delete", "custom");
        Role saved = roleService.createRole(toDelete);

        boolean result = roleService.deleteRole(saved.getId());

        assertThat(result).isTrue();
        Role deleted = roleService.getById(saved.getId());
        assertThat(deleted).isNull(); // soft-deleted, invisible via normal queries
    }

    // ==================== R2: constraints and queries ====================

    @Test
    @Order(10)
    @DisplayName("R2-01: isCodeAvailable returns false for existing code")
    void isCodeAvailable_existingCode_returnsFalse() {
        boolean available = roleService.isCodeAvailable("analyst-" + runId, getTestTenant().getId());
        assertThat(available).isFalse();
    }

    @Test
    @Order(11)
    @DisplayName("R2-02: findRoles pagination returns non-empty page")
    void findRoles_pagination_returnsResults() {
        Page<Role> page = roleService.findRoles(1, 10, getTestTenant().getId(), null, null, "active");

        assertThat(page).isNotNull();
        assertThat(page.getTotal()).isGreaterThan(0);
    }

    @Test
    @Order(12)
    @DisplayName("R2-03: findByTenantIdAndType filters by type")
    void findByTenantIdAndType_filtersCorrectly() {
        List<Role> customRoles = roleService.findByTenantIdAndType(getTestTenant().getId(), "custom");

        assertThat(customRoles).isNotNull();
        customRoles.forEach(r -> assertThat(r.getType()).isEqualTo("custom"));
    }

    @Test
    @Order(13)
    @DisplayName("R2-04: countByTenantId returns positive count")
    void countByTenantId_returnsPositiveCount() {
        long count = roleService.countByTenantId(getTestTenant().getId());
        assertThat(count).isGreaterThan(0);
    }

    // ==================== R3: enable/disable ====================

    @Test
    @Order(20)
    @DisplayName("R3-01: disableRole changes status to INACTIVE")
    void disableRole_changesStatus() {
        assertThat(roleId).as("roleId must be set by R1-01").isNotNull();

        boolean result = roleService.disableRole(roleId);

        assertThat(result).isTrue();
        Role updated = roleService.getById(roleId);
        assertThat(updated.getStatus()).isEqualTo("inactive");
    }

    @Test
    @Order(21)
    @DisplayName("R3-02: enableRole restores status to ACTIVE")
    void enableRole_restoresStatus() {
        assertThat(roleId).as("roleId must be set by R1-01").isNotNull();

        boolean result = roleService.enableRole(roleId);

        assertThat(result).isTrue();
        Role updated = roleService.getById(roleId);
        assertThat(updated.getStatus()).isEqualTo("active");
    }

    // ==================== R4: copy role ====================

    @Test
    @Order(30)
    @DisplayName("R4-01: copyRole creates a new role with a different code")
    void copyRole_createsNewRole() {
        assertThat(roleId).as("roleId must be set by R1-01").isNotNull();

        Role copy = roleService.copyRole(roleId, "Analyst Copy " + runId, "COPY-" + runId);

        assertThat(copy).isNotNull();
        assertThat(copy.getId()).isNotEqualTo(roleId);
        assertThat(copy.getCode()).isEqualTo("COPY-" + runId);
        assertThat(copy.getName()).isEqualTo("Analyst Copy " + runId);
    }

    // ==================== helpers ====================

    private Role buildRole(String code, String name, String type) {
        Role role = new Role();
        role.setCode(code);
        role.setName(name);
        role.setType(type);
        role.setTenantId(getTestTenant().getId());
        role.setScopeType("tenant");
        role.setStatus("active");
        role.setIsDefault(false);
        role.setIsSystem(false);
        role.setDeletedFlag(false);
        role.setPriority(10);
        role.setCreatedAt(Instant.now());
        role.setUpdatedAt(Instant.now());
        return role;
    }
}
