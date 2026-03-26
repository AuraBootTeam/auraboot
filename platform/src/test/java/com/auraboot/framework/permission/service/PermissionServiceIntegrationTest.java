package com.auraboot.framework.permission.service;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.exception.DuplicateException;
import com.auraboot.framework.application.exception.ResourceNotFoundException;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.permission.dto.PermissionCreateRequest;
import com.auraboot.framework.permission.dto.PermissionDTO;
import com.auraboot.framework.permission.dto.PermissionUpdateRequest;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * PermissionService Integration Test
 *
 * Covers CRUD lifecycle, role binding/unbinding, findByCode, findAllActive,
 * findByResourceType, deprecate, delete, and duplicate-code rejection.
 *
 * Uses real PostgreSQL – no mocks, no H2.
 *
 * @author AuraBoot Platform
 * @since V4
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@DisplayName("PermissionService Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PermissionServiceIntegrationTest {

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private PermissionMapper permissionMapper;

    @Autowired
    private RolePermissionMapper rolePermissionMapper;

    @Autowired
    private UserService userService;

    @Autowired
    private TenantService tenantService;

    @Autowired
    private TenantMemberService tenantMemberService;

    @Autowired
    private RoleService roleService;

    @Autowired
    private UserRoleService userRoleService;

    // Test context data – created once per class
    private String testSuffix;
    private User testUser;
    private Tenant testTenant;
    private Role testRole;

    // State shared between ordered tests
    private PermissionDTO createdPermission;

    @BeforeAll
    void initTestContext() {
        testSuffix = "_" + System.currentTimeMillis();
        testUser = ensureTestUser();
        testTenant = ensureTestTenant();
        ensureTestTenantMember();

        MetaContext.setContext(
            testTenant.getId(),
            testUser.getId(),
            testUser.getPid(),
            testUser.getUserName()
        );

        testRole = createFreshRole();
        ensureUserRoleBinding();
    }

    @AfterAll
    void clearContext() {
        MetaContext.clear();
    }

    // ==================== Setup helpers ====================

    private User ensureTestUser() {
        String email = "ps-test" + testSuffix + "@auraboot.com";
        User existing = userService.findByEmail(email);
        if (existing != null) {
            return existing;
        }
        return userService.signUp(email, "test-password-123");
    }

    private Tenant ensureTestTenant() {
        String tenantName = "ps-test-tenant" + testSuffix;
        Tenant existing = tenantService.findByName(tenantName);
        if (existing != null) {
            return existing;
        }
        Tenant tenant = new Tenant();
        tenant.setPid(UniqueIdGenerator.generate());
        tenant.setName(tenantName);
        tenant.setDisplayName("PermissionService Test Tenant");
        tenant.setStatus("active");
        tenant.setContactEmail("admin@ps-test.com");
        tenant.setDescription("PermissionService integration test tenant");
        tenant.setDeletedFlag(false);
        tenant.setCreatedAt(Instant.now());
        tenant.setUpdatedAt(Instant.now());
        return tenantService.createTenant(tenant);
    }

    private void ensureTestTenantMember() {
        TenantMember existing = tenantMemberService.findByTenantIdAndUserId(
            testTenant.getId(), testUser.getId());
        if (existing == null) {
            tenantMemberService.addMember(testUser.getId(), testTenant.getId(), "active");
        }
    }

    private Role createFreshRole() {
        // Keep name/code under 50 chars (DB constraint)
        String uniqueSuffix = String.valueOf(System.nanoTime()).substring(8);
        Role role = new Role();
        role.setPid(UniqueIdGenerator.generate());
        role.setName("ps_test_" + uniqueSuffix);
        role.setCode("ps_test_" + uniqueSuffix);
        role.setDescription("PermissionService test role");
        role.setType("custom");
        role.setScopeType("tenant");
        role.setStatus("active");
        role.setTenantId(testTenant.getId());
        role.setIsDefault(false);
        role.setIsSystem(false);
        role.setDeletedFlag(false);
        role.setPriority(100);
        role.setCreatedAt(Instant.now());
        role.setUpdatedAt(Instant.now());
        return roleService.createRole(role);
    }

    private void ensureUserRoleBinding() {
        userRoleService.assignRolesToUser(
            testUser.getId(),
            Arrays.asList(testRole.getId()),
            testTenant.getId(),
            null
        );
    }

    private PermissionCreateRequest buildCreateRequest(String code) {
        PermissionCreateRequest req = new PermissionCreateRequest();
        req.setCode(code);
        req.setName("Permission " + code);
        req.setDescription("Integration test permission: " + code);
        req.setResourceType("model");
        req.setResourceCode("test_model");
        req.setAction("read");
        req.setSource("system");
        return req;
    }

    // ==================== Tests ====================

    /**
     * PS-01: create permission with valid request persists with correct fields.
     */
    @Test
    @Order(1)
    @DisplayName("PS-01: create permission with valid request persists with correct fields")
    void ps01_createPermissionPersistsCorrectFields() {
        String code = "test_perm_" + System.currentTimeMillis();
        PermissionCreateRequest request = buildCreateRequest(code);

        PermissionDTO result = permissionService.create(request);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isNotNull();
        assertThat(result.getPid()).isNotBlank();
        assertThat(result.getCode()).isEqualTo(code);
        assertThat(result.getName()).isEqualTo("Permission " + code);
        assertThat(result.getDescription()).isEqualTo("Integration test permission: " + code);
        assertThat(result.getResourceType()).isEqualTo("model");
        assertThat(result.getResourceCode()).isEqualTo("test_model");
        assertThat(result.getAction()).isEqualTo("read");
        assertThat(result.getStatus()).isEqualTo("active");
        assertThat(result.getCreatedAt()).isNotNull();
        assertThat(result.getUpdatedAt()).isNotNull();

        // Persist reference for subsequent tests
        createdPermission = result;
    }

    /**
     * PS-02: findByCode returns the permission created in PS-01.
     */
    @Test
    @Order(2)
    @DisplayName("PS-02: findByCode returns the created permission")
    void ps02_findByCodeReturnsCreatedPermission() {
        assertThat(createdPermission).as("createdPermission from PS-01 must not be null").isNotNull();

        PermissionDTO result = permissionService.findByCode(createdPermission.getCode());

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(createdPermission.getId());
        assertThat(result.getCode()).isEqualTo(createdPermission.getCode());
        assertThat(result.getStatus()).isEqualTo("active");
    }

    /**
     * PS-03: findById returns the permission created in PS-01.
     */
    @Test
    @Order(3)
    @DisplayName("PS-03: findById returns the created permission")
    void ps03_findByIdReturnsCreatedPermission() {
        assertThat(createdPermission).as("createdPermission from PS-01 must not be null").isNotNull();

        PermissionDTO result = permissionService.findById(createdPermission.getId());

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(createdPermission.getId());
        assertThat(result.getCode()).isEqualTo(createdPermission.getCode());
    }

    /**
     * PS-04: update changes the description field and persists the change.
     */
    @Test
    @Order(4)
    @DisplayName("PS-04: update changes the description")
    void ps04_updateChangesDescription() {
        assertThat(createdPermission).as("createdPermission from PS-01 must not be null").isNotNull();

        String updatedDescription = "Updated description at " + System.currentTimeMillis();
        PermissionUpdateRequest updateRequest = new PermissionUpdateRequest();
        updateRequest.setName(createdPermission.getName());
        updateRequest.setDescription(updatedDescription);

        PermissionDTO result = permissionService.update(createdPermission.getId(), updateRequest);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(createdPermission.getId());
        assertThat(result.getDescription()).isEqualTo(updatedDescription);

        // Verify persisted
        PermissionDTO reloaded = permissionService.findById(createdPermission.getId());
        assertThat(reloaded.getDescription()).isEqualTo(updatedDescription);
    }

    /**
     * PS-05: findAllActive includes the permission created in PS-01.
     */
    @Test
    @Order(5)
    @DisplayName("PS-05: findAllActive includes the created permission")
    void ps05_findAllActiveIncludesCreatedPermission() {
        assertThat(createdPermission).as("createdPermission from PS-01 must not be null").isNotNull();

        List<PermissionDTO> result = permissionService.findAllActive();

        assertThat(result).isNotEmpty();
        assertThat(result).anyMatch(p -> p.getId().equals(createdPermission.getId()));
    }

    /**
     * PS-06: findByResourceType returns at least the permission with resourceType="model".
     */
    @Test
    @Order(6)
    @DisplayName("PS-06: findByResourceType returns matching permissions")
    void ps06_findByResourceTypeReturnsMatchingPermissions() {
        assertThat(createdPermission).as("createdPermission from PS-01 must not be null").isNotNull();

        List<PermissionDTO> result = permissionService.findByResourceType("model");

        assertThat(result).isNotEmpty();
        assertThat(result).anyMatch(p -> p.getId().equals(createdPermission.getId()));
        assertThat(result).allMatch(p -> "model".equals(p.getResourceType()));
    }

    /**
     * PS-07: bindToRole + findRolePermissions shows the binding.
     */
    @Test
    @Order(7)
    @DisplayName("PS-07: bindToRole + findRolePermissions shows the binding")
    void ps07_bindToRoleAndFindRolePermissionsShowsBinding() {
        assertThat(createdPermission).as("createdPermission from PS-01 must not be null").isNotNull();

        permissionService.bindToRole(testRole.getId(), createdPermission.getId());

        List<PermissionDTO> rolePerms = permissionService.findRolePermissions(testRole.getId());
        assertThat(rolePerms).isNotEmpty();
        assertThat(rolePerms).anyMatch(p -> p.getId().equals(createdPermission.getId()));

        // Verify via mapper directly as well
        boolean bound = rolePermissionMapper.hasPermission(testRole.getId(), createdPermission.getId());
        assertThat(bound).isTrue();
    }

    /**
     * PS-07b: bindToRole with duplicate is idempotent — no exception, no duplicate binding.
     */
    @Test
    @Order(8)
    @DisplayName("PS-07b: duplicate bindToRole is idempotent (no exception)")
    void ps07b_duplicateBindToRoleIsIdempotent() {
        assertThat(createdPermission).as("createdPermission from PS-01 must not be null").isNotNull();

        // Binding was already done in PS-07; repeat should silently skip (idempotent)
        assertThatCode(() -> permissionService.bindToRole(testRole.getId(), createdPermission.getId()))
            .doesNotThrowAnyException();

        // The binding should still exist exactly once
        List<PermissionDTO> rolePerms = permissionService.findRolePermissions(testRole.getId());
        long count = rolePerms.stream().filter(p -> p.getId().equals(createdPermission.getId())).count();
        assertThat(count).isEqualTo(1);
    }

    /**
     * PS-08: unbindFromRole removes the binding created in PS-07.
     */
    @Test
    @Order(9)
    @DisplayName("PS-08: unbindFromRole removes the binding")
    void ps08_unbindFromRoleRemovesBinding() {
        assertThat(createdPermission).as("createdPermission from PS-01 must not be null").isNotNull();

        permissionService.unbindFromRole(testRole.getId(), createdPermission.getId());

        boolean stillBound = rolePermissionMapper.hasPermission(testRole.getId(), createdPermission.getId());
        assertThat(stillBound).isFalse();

        // findRolePermissions must no longer include this permission
        List<PermissionDTO> rolePerms = permissionService.findRolePermissions(testRole.getId());
        assertThat(rolePerms).noneMatch(p -> p.getId().equals(createdPermission.getId()));
    }

    /**
     * PS-09: deprecate sets status to DEPRECATED and records deprecatedAt.
     */
    @Test
    @Order(10)
    @DisplayName("PS-09: deprecate sets status to DEPRECATED")
    void ps09_deprecateSetsStatusToDeprecated() {
        // Create a fresh permission to deprecate (independent of PS-01 lifecycle)
        String code = "test_perm_dep_" + System.nanoTime();
        PermissionDTO toDeprecate = permissionService.create(buildCreateRequest(code));

        permissionService.deprecate(toDeprecate.getId());

        Permission raw = permissionMapper.selectById(toDeprecate.getId());
        assertThat(raw).isNotNull();
        assertThat(raw.getStatus()).isEqualTo("deprecated");
        assertThat(raw.getDeprecatedAt()).isNotNull();
    }

    /**
     * PS-09b: deprecating an already-DEPRECATED permission throws IllegalStateException.
     */
    @Test
    @Order(11)
    @DisplayName("PS-09b: double-deprecate throws IllegalStateException")
    void ps09b_doubleDeprecateThrowsIllegalStateException() {
        String code = "test_perm_dep2_" + System.nanoTime();
        PermissionDTO toDeprecate = permissionService.create(buildCreateRequest(code));
        permissionService.deprecate(toDeprecate.getId());

        assertThatThrownBy(() -> permissionService.deprecate(toDeprecate.getId()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("deprecated");
    }

    /**
     * PS-10: delete (soft-delete) removes the permission from active results.
     */
    @Test
    @Order(12)
    @DisplayName("PS-10: delete soft-deletes the permission")
    void ps10_deleteSoftDeletesPermission() {
        String code = "test_perm_del_" + System.nanoTime();
        PermissionDTO toDelete = permissionService.create(buildCreateRequest(code));

        // Confirm it exists before deletion
        assertThat(permissionService.findByCode(code)).isNotNull();

        permissionService.delete(toDelete.getId());

        // findByCode must throw ResourceNotFoundException after soft-delete
        assertThatThrownBy(() -> permissionService.findByCode(code))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining(code);

        // findById must also throw
        assertThatThrownBy(() -> permissionService.findById(toDelete.getId()))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    /**
     * PS-10b: findById with non-existent ID throws ResourceNotFoundException.
     */
    @Test
    @Order(13)
    @DisplayName("PS-10b: findById with non-existent ID throws ResourceNotFoundException")
    void ps10b_findByIdNonExistentThrowsResourceNotFoundException() {
        assertThatThrownBy(() -> permissionService.findById(Long.MAX_VALUE))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining(String.valueOf(Long.MAX_VALUE));
    }

    /**
     * PS-11: create with duplicate code throws DuplicateException.
     */
    @Test
    @Order(14)
    @DisplayName("PS-11: create with duplicate code throws DuplicateException")
    void ps11_createWithDuplicateCodeThrowsDuplicateException() {
        String code = "test_perm_dup_" + System.nanoTime();
        permissionService.create(buildCreateRequest(code));

        // Second creation with the same code must fail
        assertThatThrownBy(() -> permissionService.create(buildCreateRequest(code)))
            .isInstanceOf(DuplicateException.class)
            .hasMessageContaining(code);
    }

    /**
     * PS-12: create with blank code throws IllegalArgumentException.
     */
    @Test
    @Order(15)
    @DisplayName("PS-12: create with blank code throws IllegalArgumentException")
    void ps12_createWithBlankCodeThrowsIllegalArgumentException() {
        PermissionCreateRequest request = new PermissionCreateRequest();
        request.setCode("  ");
        request.setName("Name");
        request.setResourceType("model");
        request.setResourceCode("test_model");
        request.setAction("read");

        assertThatThrownBy(() -> permissionService.create(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("code");
    }
}
