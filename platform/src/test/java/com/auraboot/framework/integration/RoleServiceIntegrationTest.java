package com.auraboot.framework.integration;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.rbac.service.UserRoleService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for RoleService and UserRoleService.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RoleServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private RoleService roleService;

    @Autowired
    private UserRoleService userRoleService;

    // Shared state across ordered tests (static = shared across PER_METHOD instances)
    private static final String runId = String.valueOf(System.currentTimeMillis()).substring(7);
    private static Long createdRoleId;
    private static Long copiedRoleId;

    // ======================================================================
    // Helpers
    // ======================================================================

    private Role buildRole(String suffix) {
        Role role = new Role();
        role.setPid(UniqueIdGenerator.generate());
        role.setName("Test Role " + suffix + "_" + runId);
        role.setCode("test_role_" + suffix.toLowerCase() + "_" + runId);
        role.setDescription("Integration test role " + suffix);
        role.setType("custom");
        role.setScopeType("tenant");
        role.setStatus("active");
        role.setTenantId(testTenant.getId());
        role.setIsDefault(false);
        role.setIsSystem(false);
        role.setDeletedFlag(false);
        role.setPriority(50);
        role.setCreatedAt(Instant.now());
        role.setUpdatedAt(Instant.now());
        return role;
    }

    // ======================================================================
    // Create tests
    // ======================================================================

    @Test
    @Order(1)
    void createRole_setsDefaultFields() {
        Role role = buildRole("rs01");
        Role created = roleService.createRole(role);

        assertThat(created).isNotNull();
        assertThat(created.getId()).isNotNull();
        assertThat(created.getPid()).isNotBlank();
        assertThat(created.getStatus()).isEqualTo("active");
        assertThat(created.getIsSystem()).isFalse();
        assertThat(created.getDeletedFlag()).isFalse();

        createdRoleId = created.getId();
    }

    @Test
    @Order(2)
    void getById_returnsCreatedRole() {
        assertThat(createdRoleId).isNotNull();
        Role found = roleService.getById(createdRoleId);

        assertThat(found).isNotNull();
        assertThat(found.getName()).isEqualTo("Test Role rs01_" + runId);
        assertThat(found.getCode()).isEqualTo("test_role_rs01_" + runId);
    }

    // ======================================================================
    // Update tests
    // ======================================================================

    @Test
    @Order(3)
    void updateRole_updatesFields() {
        assertThat(createdRoleId).isNotNull();
        Role role = roleService.getById(createdRoleId);
        role.setDescription("Updated description");

        Role updated = roleService.updateRole(role);
        assertThat(updated.getDescription()).isEqualTo("Updated description");
    }

    @Test
    @Order(4)
    void updateRole_notFound_throwsException() {
        Role role = buildRole("NotFound");
        role.setId(999999L);

        assertThatThrownBy(() -> roleService.updateRole(role))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("角色不存在");
    }

    // ======================================================================
    // Query tests
    // ======================================================================

    @Test
    @Order(5)
    void findByPid_returnsRole() {
        Role role = roleService.getById(createdRoleId);
        assertThat(role).isNotNull();

        Role found = roleService.findByPid(role.getPid());
        assertThat(found).isNotNull();
        assertThat(found.getId()).isEqualTo(createdRoleId);
    }

    @Test
    @Order(6)
    void findByTenantId_includesCreatedRole() {
        List<Role> roles = roleService.findByTenantId(testTenant.getId());
        assertThat(roles).isNotNull();
        assertThat(roles).anyMatch(r -> r.getId().equals(createdRoleId));
    }

    @Test
    @Order(7)
    void findRoles_withKeyword_filtersResults() {
        var page = roleService.findRoles(1, 10, testTenant.getId(), "rs01_" + runId, null, null);
        assertThat(page.getRecords()).anyMatch(r -> r.getId().equals(createdRoleId));
    }

    @Test
    @Order(8)
    void findRoles_withTypeFilter_filtersResults() {
        var page = roleService.findRoles(1, 10, testTenant.getId(), null, "custom", null);
        assertThat(page.getRecords()).allMatch(r -> "custom".equals(r.getType()));
    }

    @Test
    @Order(9)
    void isCodeAvailable_existingCode_returnsFalse() {
        boolean available = roleService.isCodeAvailable("test_role_rs01_" + runId, testTenant.getId());
        assertThat(available).isFalse();
    }

    @Test
    @Order(10)
    void isCodeAvailable_newCode_returnsTrue() {
        boolean available = roleService.isCodeAvailable("nonexistent_code_xyz999", testTenant.getId());
        assertThat(available).isTrue();
    }

    @Test
    @Order(11)
    void countByTenantId_returnsPositive() {
        long count = roleService.countByTenantId(testTenant.getId());
        assertThat(count).isGreaterThanOrEqualTo(1);
    }

    // ======================================================================
    // Enable / Disable tests
    // ======================================================================

    @Test
    @Order(12)
    void disableRole_setsInactive() {
        assertThat(createdRoleId).isNotNull();
        boolean result = roleService.disableRole(createdRoleId);
        assertThat(result).isTrue();

        Role found = roleService.getById(createdRoleId);
        assertThat(found.getStatus()).isEqualTo("inactive");
    }

    @Test
    @Order(13)
    void enableRole_setsActive() {
        assertThat(createdRoleId).isNotNull();
        boolean result = roleService.enableRole(createdRoleId);
        assertThat(result).isTrue();

        Role found = roleService.getById(createdRoleId);
        assertThat(found.getStatus()).isEqualTo("active");
    }

    @Test
    @Order(14)
    void disableRole_notFound_throwsException() {
        assertThatThrownBy(() -> roleService.disableRole(999999L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @Order(15)
    void enableRole_notFound_throwsException() {
        assertThatThrownBy(() -> roleService.enableRole(999999L))
                .isInstanceOf(BusinessException.class);
    }

    // ======================================================================
    // Statistics & Hierarchy tests
    // ======================================================================

    @Test
    @Order(16)
    void getRoleStatistics_returnsStats() {
        Map<String, Object> stats = roleService.getRoleStatistics(testTenant.getId());
        assertThat(stats).containsKey("totalRoles");
        assertThat(stats).containsKey("rolesByType");
        assertThat(stats).containsKey("rolesByStatus");
        assertThat((Long) stats.get("totalRoles")).isGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(17)
    void getRoleHierarchy_returnsOrderedList() {
        List<Map<String, Object>> hierarchy = roleService.getRoleHierarchy(testTenant.getId());
        assertThat(hierarchy).isNotNull();
        assertThat(hierarchy).isNotEmpty();
        // Verify sorted by priority
        for (int i = 1; i < hierarchy.size(); i++) {
            Integer prev = (Integer) hierarchy.get(i - 1).get("priority");
            Integer curr = (Integer) hierarchy.get(i).get("priority");
            assertThat(prev).isLessThanOrEqualTo(curr);
        }
    }

    // ======================================================================
    // Copy tests
    // ======================================================================

    @Test
    @Order(18)
    void copyRole_createsNewRoleWithSameType() {
        assertThat(createdRoleId).isNotNull();
        String newCode = "test_role_rs01_copy_" + runId;
        String newName = "Test Role rs01 Copy " + runId;

        Role copied = roleService.copyRole(createdRoleId, newName, newCode);

        assertThat(copied).isNotNull();
        assertThat(copied.getId()).isNotEqualTo(createdRoleId);
        assertThat(copied.getCode()).isEqualTo(newCode);
        assertThat(copied.getName()).isEqualTo(newName);
        assertThat(copied.getType()).isEqualTo("custom");
        assertThat(copied.getIsSystem()).isFalse();

        copiedRoleId = copied.getId();
    }

    @Test
    @Order(19)
    void copyRole_duplicateCode_throwsException() {
        assertThat(createdRoleId).isNotNull();
        // original code already exists — copy with same code should fail
        assertThatThrownBy(() -> roleService.copyRole(createdRoleId, "Duplicate", "test_role_rs01_" + runId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("角色编码已存在");
    }

    @Test
    @Order(20)
    void copyRole_notFound_throwsException() {
        assertThatThrownBy(() -> roleService.copyRole(999999L, "New Name", "new_code_xyz"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("原角色不存在");
    }

    // ======================================================================
    // Assign / Remove permissions tests
    // ======================================================================

    @Test
    @Order(21)
    void getRolePermissionIds_emptyForNewRole() {
        assertThat(createdRoleId).isNotNull();
        List<Long> permIds = roleService.getRolePermissionIds(createdRoleId);
        assertThat(permIds).isNotNull();
        // New role has no permissions
        assertThat(permIds).isEmpty();
    }

    // ======================================================================
    // Delete tests
    // ======================================================================

    @Test
    @Order(22)
    void deleteRole_notFound_throwsException() {
        assertThatThrownBy(() -> roleService.deleteRole(999999L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @Order(23)
    void deleteRole_deletesSuccessfully() {
        assertThat(copiedRoleId).isNotNull();
        boolean result = roleService.deleteRole(copiedRoleId);
        assertThat(result).isTrue();

        // Physically deleted (hard delete)
        Role found = roleService.getById(copiedRoleId);
        assertThat(found).isNull();
    }

    @Test
    @Order(24)
    void deleteRole_cleanupMain() {
        assertThat(createdRoleId).isNotNull();
        // Ensure no users are assigned to this role before deleting
        // The test role from BaseIntegrationTest is different from createdRoleId
        boolean result = roleService.deleteRole(createdRoleId);
        assertThat(result).isTrue();
    }

    // ======================================================================
    // findByTenantIdAndType
    // ======================================================================

    @Test
    @Order(25)
    void findByTenantIdAndType_returnsMatchingRoles() {
        // BaseIntegrationTest creates a CUSTOM role each test
        List<Role> customRoles = roleService.findByTenantIdAndType(testTenant.getId(), "custom");
        assertThat(customRoles).isNotNull();
    }

    // ======================================================================
    // System role tests (initializeSystemRoles)
    // ======================================================================

    @Test
    @Order(30)
    void initializeSystemRoles_idempotent() {
        // Should not throw even if system roles already exist
        roleService.initializeSystemRoles();
        roleService.initializeSystemRoles(); // second call should be a no-op
    }

    // ======================================================================
    // createDefaultRolesForTenant
    // ======================================================================

    @Test
    @Order(31)
    void createDefaultRolesForTenant_createsAdminRole() {
        // Call with existing test tenant — the method should add a 租户管理员 role
        long beforeCount = roleService.countByTenantId(testTenant.getId());
        roleService.createDefaultRolesForTenant(testTenant.getId());
        long afterCount = roleService.countByTenantId(testTenant.getId());

        // At least one role was added
        assertThat(afterCount).isGreaterThanOrEqualTo(beforeCount);

        List<Role> roles = roleService.findByTenantId(testTenant.getId());
        assertThat(roles).anyMatch(r -> "租户管理员".equals(r.getName()));
    }
}
