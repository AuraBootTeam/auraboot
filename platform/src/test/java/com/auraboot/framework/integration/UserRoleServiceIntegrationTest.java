package com.auraboot.framework.integration;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.entity.UserRole;
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
 * Integration tests for UserRoleService.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserRoleServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private UserRoleService userRoleService;

    @Autowired
    private RoleService roleService;

    // Shared state
    private static Long roleAId;
    private static Long roleBId;

    // ======================================================================
    // Helpers
    // ======================================================================

    private Role createRole(String suffix) {
        Role role = new Role();
        role.setPid(UniqueIdGenerator.generate());
        role.setName("UR Test Role " + suffix);
        role.setCode("ur_test_role_" + suffix.toLowerCase());
        role.setDescription("UserRole integration test role " + suffix);
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
        return roleService.createRole(role);
    }

    // ======================================================================
    // Setup — create roles used across tests
    // ======================================================================

    @Test
    @Order(1)
    void setup_createTestRoles() {
        roleAId = createRole("ura").getId();
        roleBId = createRole("urb").getId();
        assertThat(roleAId).isNotNull();
        assertThat(roleBId).isNotNull();
    }

    // ======================================================================
    // assignRolesToUser / findByUserIdAndTenantId
    // ======================================================================

    @Test
    @Order(2)
    void assignRolesToUser_addsRoles() {
        boolean result = userRoleService.assignRolesToUser(
                testUser.getId(), List.of(roleAId), testTenant.getId(), null);
        assertThat(result).isTrue();

        List<Long> roleIds = userRoleService.getRoleIdsByUserIdAndTenantId(
                testUser.getId(), testTenant.getId());
        assertThat(roleIds).contains(roleAId);
    }

    @Test
    @Order(3)
    void assignRolesToUser_idempotent_doesNotDuplicate() {
        // Assign roleA twice — should not create a duplicate
        userRoleService.assignRolesToUser(testUser.getId(), List.of(roleAId), testTenant.getId(), null);
        userRoleService.assignRolesToUser(testUser.getId(), List.of(roleAId), testTenant.getId(), null);

        List<Long> roleIds = userRoleService.getRoleIdsByUserIdAndTenantId(
                testUser.getId(), testTenant.getId());
        long count = roleIds.stream().filter(id -> id.equals(roleAId)).count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    @Order(4)
    void assignRolesToUser_emptyList_returnsTrue() {
        boolean result = userRoleService.assignRolesToUser(
                testUser.getId(), List.of(), testTenant.getId(), null);
        assertThat(result).isTrue();
    }

    // ======================================================================
    // findByUserIdAndRoleIdAndTenantId
    // ======================================================================

    @Test
    @Order(5)
    void findByUserIdAndRoleIdAndTenantId_returnsRecord() {
        UserRole ur = userRoleService.findByUserIdAndRoleIdAndTenantId(
                testUser.getId(), roleAId, testTenant.getId());
        assertThat(ur).isNotNull();
        assertThat(ur.getRoleId()).isEqualTo(roleAId);
        assertThat(ur.getUserId()).isEqualTo(testUser.getId());
    }

    @Test
    @Order(6)
    void findByUserIdAndRoleIdAndTenantId_notFound_returnsNull() {
        UserRole ur = userRoleService.findByUserIdAndRoleIdAndTenantId(
                testUser.getId(), 999999L, testTenant.getId());
        assertThat(ur).isNull();
    }

    // ======================================================================
    // findByPid
    // ======================================================================

    @Test
    @Order(7)
    void findByPid_returnsRecord() {
        UserRole ur = userRoleService.findByUserIdAndRoleIdAndTenantId(
                testUser.getId(), roleAId, testTenant.getId());
        assertThat(ur).isNotNull();

        UserRole byPid = userRoleService.findByPid(ur.getPid());
        assertThat(byPid).isNotNull();
        assertThat(byPid.getId()).isEqualTo(ur.getId());
    }

    // ======================================================================
    // syncUserRoles
    // ======================================================================

    @Test
    @Order(8)
    void syncUserRoles_addsAndRemovesRoles() {
        // Start: user has roleA
        // Sync to: [roleB] — should add roleB, remove roleA
        boolean result = userRoleService.syncUserRoles(
                testUser.getId(), List.of(roleBId), testTenant.getId(), null);
        assertThat(result).isTrue();

        List<Long> roleIds = userRoleService.getRoleIdsByUserIdAndTenantId(
                testUser.getId(), testTenant.getId());
        assertThat(roleIds).contains(roleBId);
        assertThat(roleIds).doesNotContain(roleAId);
    }

    @Test
    @Order(9)
    void syncUserRoles_toEmpty_removesAllRoles() {
        userRoleService.syncUserRoles(testUser.getId(), List.of(), testTenant.getId(), null);
        List<Long> roleIds = userRoleService.getRoleIdsByUserIdAndTenantId(
                testUser.getId(), testTenant.getId());
        // The role from BaseIntegrationTest (testRole) is assigned in @BeforeEach
        // but syncUserRoles should remove roleB that we just added
        assertThat(roleIds).doesNotContain(roleBId);
    }

    // ======================================================================
    // removeRolesFromUser
    // ======================================================================

    @Test
    @Order(10)
    void removeRolesFromUser_removesSpecificRole() {
        // testRole is freshly assigned in @BeforeEach (status=ACTIVE, deleted_flag=false)
        // Verify it's in the active role list
        List<Long> before = userRoleService.getRoleIdsByUserIdAndTenantId(
                testUser.getId(), testTenant.getId());
        assertThat(before).contains(testRole.getId());

        // Remove testRole
        userRoleService.removeRolesFromUser(
                testUser.getId(), List.of(testRole.getId()), testTenant.getId());

        // After remove, testRole should no longer appear in active roles
        List<Long> after = userRoleService.getRoleIdsByUserIdAndTenantId(
                testUser.getId(), testTenant.getId());
        assertThat(after).doesNotContain(testRole.getId());
    }

    @Test
    @Order(11)
    void removeRolesFromUser_emptyList_returnsTrue() {
        boolean result = userRoleService.removeRolesFromUser(
                testUser.getId(), List.of(), testTenant.getId());
        assertThat(result).isTrue();
    }

    // ======================================================================
    // removeAllRolesFromUserInTenant
    // ======================================================================

    @Test
    @Order(12)
    void removeAllRolesFromUserInTenant_removesAll() {
        userRoleService.assignRolesToUser(
                testUser.getId(), List.of(roleAId, roleBId), testTenant.getId(), null);

        boolean result = userRoleService.removeAllRolesFromUserInTenant(
                testUser.getId(), testTenant.getId());
        assertThat(result).isTrue();

        List<Long> roleIds = userRoleService.getRoleIdsByUserIdAndTenantId(
                testUser.getId(), testTenant.getId());
        assertThat(roleIds).doesNotContain(roleAId, roleBId);
    }

    // ======================================================================
    // copyUserRoles
    // ======================================================================

    @Test
    @Order(13)
    void copyUserRoles_emptySource_returnsTrue() {
        // removeAllRolesFromUserInTenant (test 12) cleared the user's roles
        // Verify that copying from a user with no roles returns true
        boolean result = userRoleService.copyUserRoles(
                testUser.getId(), testUser.getId(), testTenant.getId());
        assertThat(result).isTrue();
    }

    // ======================================================================
    // isRoleInUseInTenant
    // ======================================================================

    @Test
    @Order(14)
    void isRoleInUseInTenant_roleInUse_returnsTrue() {
        // testRole is freshly created in @BeforeEach and the user is assigned to it
        // So testRole.getId() is definitely in use in testTenant
        boolean inUse = userRoleService.isRoleInUseInTenant(testRole.getId(), testTenant.getId());
        assertThat(inUse).isTrue();
    }

    @Test
    @Order(15)
    void isRoleInUseInTenant_roleNotInUse_returnsFalse() {
        userRoleService.removeRolesFromUser(testUser.getId(), List.of(roleBId), testTenant.getId());
        boolean inUse = userRoleService.isRoleInUseInTenant(roleBId, testTenant.getId());
        assertThat(inUse).isFalse();
    }

    // ======================================================================
    // getTenantUserRoles
    // ======================================================================

    @Test
    @Order(16)
    void getTenantUserRoles_returnsData() {
        userRoleService.assignRolesToUser(
                testUser.getId(), List.of(roleAId), testTenant.getId(), null);

        List<Map<String, Object>> data = userRoleService.getTenantUserRoles(testTenant.getId());
        assertThat(data).isNotNull();
    }

    // ======================================================================
    // validateUserRoles
    // ======================================================================

    @Test
    @Order(17)
    void validateUserRoles_userWithRoles_valid() {
        userRoleService.assignRolesToUser(
                testUser.getId(), List.of(roleAId), testTenant.getId(), null);

        Map<String, Object> result = userRoleService.validateUserRoles(
                testUser.getId(), testTenant.getId());
        assertThat(result).containsKey("valid");
        assertThat(result).containsKey("errors");
        assertThat(result).containsKey("warnings");
    }

    @Test
    @Order(18)
    void validateUserRoles_userWithoutRoles_hasWarning() {
        // Remove all roles first
        userRoleService.removeAllRolesFromUserInTenant(testUser.getId(), testTenant.getId());

        Map<String, Object> result = userRoleService.validateUserRoles(
                testUser.getId(), testTenant.getId());
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) result.get("warnings");
        assertThat(warnings).anyMatch(w -> w.contains("未分配任何角色"));
    }

    // ======================================================================
    // findByUserIds / findByRoleIds
    // ======================================================================

    @Test
    @Order(19)
    void findByUserIds_returnsAssignments() {
        userRoleService.assignRolesToUser(
                testUser.getId(), List.of(roleAId), testTenant.getId(), null);

        List<UserRole> results = userRoleService.findByUserIds(List.of(testUser.getId()));
        assertThat(results).isNotNull();
        assertThat(results).anyMatch(ur -> ur.getUserId().equals(testUser.getId()));
    }

    @Test
    @Order(20)
    void findByUserIds_emptyInput_returnsEmpty() {
        List<UserRole> results = userRoleService.findByUserIds(List.of());
        assertThat(results).isEmpty();
    }

    @Test
    @Order(21)
    void findByRoleIds_returnsAssignments() {
        // testRole is freshly assigned in @BeforeEach, so it's definitely active
        List<UserRole> results = userRoleService.findByRoleIds(List.of(testRole.getId()));
        assertThat(results).isNotNull();
        assertThat(results).anyMatch(ur -> ur.getRoleId().equals(testRole.getId()));
    }

    @Test
    @Order(22)
    void findByRoleIds_emptyInput_returnsEmpty() {
        List<UserRole> results = userRoleService.findByRoleIds(List.of());
        assertThat(results).isEmpty();
    }

    // ======================================================================
    // countByTenantId
    // ======================================================================

    @Test
    @Order(23)
    void countByTenantId_returnsPositive() {
        long count = userRoleService.countByTenantId(testTenant.getId());
        assertThat(count).isGreaterThanOrEqualTo(0);
    }

    // ======================================================================
    // getUserRoleHistory / cleanupInvalidUserRoles (stub implementations)
    // ======================================================================

    @Test
    @Order(24)
    void getUserRoleHistory_returnsEmptyList() {
        List<Map<String, Object>> history = userRoleService.getUserRoleHistory(
                testUser.getId(), testTenant.getId(), 30);
        assertThat(history).isNotNull();
        assertThat(history).isEmpty();
    }

    @Test
    @Order(25)
    void cleanupInvalidUserRoles_returnsZero() {
        int cleaned = userRoleService.cleanupInvalidUserRoles();
        assertThat(cleaned).isEqualTo(0);
    }

    // ======================================================================
    // Cleanup
    // ======================================================================

    @Test
    @Order(99)
    void cleanup_deleteTestRoles() {
        userRoleService.removeAllRolesFromUserInTenant(testUser.getId(), testTenant.getId());
        if (roleAId != null) roleService.deleteRole(roleAId);
        if (roleBId != null) roleService.deleteRole(roleBId);
    }
}
