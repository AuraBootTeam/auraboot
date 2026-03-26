package com.auraboot.framework.integration;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.permission.dto.PermissionCreateRequest;
import com.auraboot.framework.permission.dto.PermissionDTO;
import com.auraboot.framework.permission.service.PermissionService;
import com.auraboot.framework.permission.service.RolePermissionService;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.service.RoleService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for RolePermissionService.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RolePermissionServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private RolePermissionService rolePermissionService;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private RoleService roleService;

    private static final String RUN_ID = String.valueOf(System.currentTimeMillis() % 100000);
    private static Long testRoleId;
    private static Long permId1;
    private static Long permId2;

    // ======================================================================
    // Helpers
    // ======================================================================

    private PermissionCreateRequest buildPermRequest(String suffix) {
        PermissionCreateRequest req = new PermissionCreateRequest();
        req.setCode("rp_" + RUN_ID + "_" + suffix);
        req.setName("RolePermTest " + suffix);
        req.setDescription("Integration test permission for role-permission tests");
        req.setResourceType("model");
        req.setResourceCode("rp_model_" + suffix + "_" + RUN_ID);
        req.setAction("read");
        req.setSource("integration_test");
        return req;
    }

    private Role createRole(String suffix) {
        Role role = new Role();
        role.setPid(UniqueIdGenerator.generate());
        role.setName("RP Test Role " + suffix);
        role.setCode("rp_test_role_" + suffix.toLowerCase() + "_" + RUN_ID);
        role.setDescription("RolePermission integration test role");
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
    // Setup
    // ======================================================================

    @Test
    @Order(1)
    void setup_createRoleAndPermissions() {
        Role role = createRole("rp01");
        testRoleId = role.getId();
        assertThat(testRoleId).isNotNull();

        PermissionDTO p1 = permissionService.create(buildPermRequest("P1"));
        PermissionDTO p2 = permissionService.create(buildPermRequest("P2"));
        permId1 = p1.getId();
        permId2 = p2.getId();
        assertThat(permId1).isNotNull();
        assertThat(permId2).isNotNull();
    }

    // ======================================================================
    // assignPermissionsToRole
    // ======================================================================

    @Test
    @Order(2)
    void assignPermissionsToRole_success() {
        assertThat(testRoleId).isNotNull();
        boolean result = rolePermissionService.assignPermissionsToRole(
                testRoleId, List.of(permId1, permId2));
        assertThat(result).isTrue();

        Set<Long> ids = rolePermissionService.getPermissionIdsByRoleId(testRoleId);
        assertThat(ids).contains(permId1, permId2);
    }

    @Test
    @Order(3)
    void assignPermissionsToRole_idempotent() {
        // Assign same permissions twice — should not duplicate
        rolePermissionService.assignPermissionsToRole(testRoleId, List.of(permId1));
        rolePermissionService.assignPermissionsToRole(testRoleId, List.of(permId1));

        Set<Long> ids = rolePermissionService.getPermissionIdsByRoleId(testRoleId);
        long count = ids.stream().filter(id -> id.equals(permId1)).count();
        assertThat(count).isEqualTo(1);
    }

    // ======================================================================
    // getPermissionIdsByRoleId / getPermissionPidsByRoleId
    // ======================================================================

    @Test
    @Order(4)
    void getPermissionIdsByRoleId_returnsAssignedIds() {
        Set<Long> ids = rolePermissionService.getPermissionIdsByRoleId(testRoleId);
        assertThat(ids).isNotNull();
        assertThat(ids).contains(permId1, permId2);
    }

    @Test
    @Order(5)
    void getPermissionPidsByRoleId_returnsPids() {
        List<String> pids = rolePermissionService.getPermissionPidsByRoleId(testRoleId);
        assertThat(pids).isNotNull();
        assertThat(pids).isNotEmpty();
    }

    @Test
    @Order(6)
    void getPermissionIdsByRoleId_noPermissions_returnsEmpty() {
        Role emptyRole = createRole("rp_empty");
        Set<Long> ids = rolePermissionService.getPermissionIdsByRoleId(emptyRole.getId());
        assertThat(ids).isEmpty();
        roleService.deleteRole(emptyRole.getId());
    }

    // ======================================================================
    // getRolePermissionStatistics
    // ======================================================================

    @Test
    @Order(7)
    void getRolePermissionStatistics_returnsStats() {
        Map<String, Object> stats = rolePermissionService.getRolePermissionStatistics(testRoleId);
        assertThat(stats).isNotNull();
        assertThat(stats).containsKey("totalPermissions");
        assertThat(stats).containsKey("byResource");
        assertThat(stats).containsKey("byAction");

        Integer total = (Integer) stats.get("totalPermissions");
        assertThat(total).isGreaterThanOrEqualTo(2);
    }

    @Test
    @Order(8)
    void getRolePermissionStatistics_noPermissions_totalZero() {
        Role emptyRole = createRole("rp_stat_empty");
        Map<String, Object> stats = rolePermissionService.getRolePermissionStatistics(emptyRole.getId());
        assertThat(stats).isNotNull();
        assertThat((Integer) stats.get("totalPermissions")).isEqualTo(0);
        roleService.deleteRole(emptyRole.getId());
    }

    // ======================================================================
    // copyRolePermissions
    // ======================================================================

    @Test
    @Order(9)
    void copyRolePermissions_copiesBindings() {
        Role targetRole = createRole("rp_copy_target");
        boolean result = rolePermissionService.copyRolePermissions(testRoleId, targetRole.getId());
        assertThat(result).isTrue();

        Set<Long> targetIds = rolePermissionService.getPermissionIdsByRoleId(targetRole.getId());
        assertThat(targetIds).contains(permId1, permId2);

        roleService.deleteRole(targetRole.getId());
    }

    @Test
    @Order(10)
    void copyRolePermissions_emptySource_returnsTrue() {
        Role sourceRole = createRole("rp_copy_empty_src");
        Role targetRole = createRole("rp_copy_empty_tgt");

        boolean result = rolePermissionService.copyRolePermissions(sourceRole.getId(), targetRole.getId());
        assertThat(result).isTrue();

        Set<Long> targetIds = rolePermissionService.getPermissionIdsByRoleId(targetRole.getId());
        assertThat(targetIds).isEmpty();

        roleService.deleteRole(sourceRole.getId());
        roleService.deleteRole(targetRole.getId());
    }

    // ======================================================================
    // syncRolePermissionsByPids
    // ======================================================================

    @Test
    @Order(11)
    void syncRolePermissionsByPids_replacesBindings() {
        // Get pids for perm1 only
        PermissionDTO p1dto = permissionService.findById(permId1);
        List<String> pids = List.of(p1dto.getPid());

        boolean result = rolePermissionService.syncRolePermissionsByPids(testRoleId, pids, "grant");
        assertThat(result).isTrue();

        Set<Long> ids = rolePermissionService.getPermissionIdsByRoleId(testRoleId);
        assertThat(ids).contains(permId1);
        assertThat(ids).doesNotContain(permId2);
    }

    // ======================================================================
    // removePermission / removePermissionsFromRoleByPids
    // ======================================================================

    @Test
    @Order(12)
    void removePermission_removesBinding() {
        // Re-assign both first
        rolePermissionService.assignPermissionsToRole(testRoleId, List.of(permId1, permId2));

        boolean result = rolePermissionService.removePermission(testRoleId, permId2);
        assertThat(result).isTrue();

        Set<Long> ids = rolePermissionService.getPermissionIdsByRoleId(testRoleId);
        assertThat(ids).doesNotContain(permId2);
    }

    @Test
    @Order(13)
    void removePermissionsFromRoleByPids_removesBindings() {
        // Ensure perm1 is assigned
        rolePermissionService.assignPermissionsToRole(testRoleId, List.of(permId1));

        PermissionDTO p1dto = permissionService.findById(permId1);
        boolean result = rolePermissionService.removePermissionsFromRoleByPids(
                testRoleId, List.of(p1dto.getPid()));
        assertThat(result).isTrue();

        Set<Long> ids = rolePermissionService.getPermissionIdsByRoleId(testRoleId);
        assertThat(ids).doesNotContain(permId1);
    }

    // ======================================================================
    // removeAllPermissionsByRoleId
    // ======================================================================

    @Test
    @Order(14)
    void removeAllPermissionsByRoleId_removesAll() {
        // Re-assign both
        rolePermissionService.assignPermissionsToRole(testRoleId, List.of(permId1, permId2));

        boolean result = rolePermissionService.removeAllPermissionsByRoleId(testRoleId);
        assertThat(result).isTrue();

        Set<Long> ids = rolePermissionService.getPermissionIdsByRoleId(testRoleId);
        assertThat(ids).doesNotContain(permId1, permId2);
    }

    // ======================================================================
    // Cleanup
    // ======================================================================

    @Test
    @Order(99)
    void cleanup() {
        if (testRoleId != null) {
            try { rolePermissionService.removeAllPermissionsByRoleId(testRoleId); } catch (Exception ignored) {}
            try { roleService.deleteRole(testRoleId); } catch (Exception ignored) {}
        }
        if (permId1 != null) {
            try { permissionService.delete(permId1); } catch (Exception ignored) {}
        }
        if (permId2 != null) {
            try { permissionService.delete(permId2); } catch (Exception ignored) {}
        }
    }
}
