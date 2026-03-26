package com.auraboot.framework.permission.service;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.cache.MetaCacheKeyGenerator;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.impl.UserPermissionServiceImpl;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.entity.RolePermission;
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
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * UserPermissionService Integration Test
 *
 * Covers permission ID resolution via RBAC, hasPermission by ID and code,
 * cache eviction, and batch operations.
 *
 * Uses real PostgreSQL and Redis – no mocks, no H2.
 *
 * @author AuraBoot Platform
 * @since V4
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@DisplayName("UserPermissionService Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserPermissionServiceIntegrationTest {

    @Autowired
    private UserPermissionService userPermissionService;

    @Autowired
    private UserPermissionServiceImpl userPermissionServiceImpl;

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

    @Autowired
    private CacheManager cacheManager;

    private static final String CACHE_NAME = "user-permissions";

    // Test context data – created once per class
    private String testSuffix;
    private User testUser;
    private Tenant testTenant;
    private Role testRole;
    private Permission testPermission;

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
        testPermission = createTestPermission("up_test");
        bindPermissionToRole(testPermission.getId());
    }

    @BeforeEach
    void evictCacheBeforeEachTest() {
        // Ensure a clean cache state before each test to avoid cross-test interference
        evictUserPermissionCache();
        userPermissionServiceImpl.clearPermissionCodeCache();
    }

    @AfterAll
    void clearContext() {
        MetaContext.clear();
    }

    // ==================== Setup helpers ====================

    private User ensureTestUser() {
        String email = "up-test" + testSuffix + "@auraboot.com";
        User existing = userService.findByEmail(email);
        if (existing != null) {
            return existing;
        }
        return userService.signUp(email, "test-password-123");
    }

    private Tenant ensureTestTenant() {
        String tenantName = "up-test-tenant" + testSuffix;
        Tenant existing = tenantService.findByName(tenantName);
        if (existing != null) {
            return existing;
        }
        Tenant tenant = new Tenant();
        tenant.setPid(UniqueIdGenerator.generate());
        tenant.setName(tenantName);
        tenant.setDisplayName("UserPermissionService Test Tenant");
        tenant.setStatus("active");
        tenant.setContactEmail("admin@up-test.com");
        tenant.setDescription("UserPermissionService integration test tenant");
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
        role.setName("up_test_" + uniqueSuffix);
        role.setCode("up_test_" + uniqueSuffix);
        role.setDescription("UserPermissionService test role");
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

    private Permission createTestPermission(String codePrefix) {
        String uniqueCode = codePrefix + "_" + System.nanoTime();
        Permission permission = new Permission();
        permission.setPid(UniqueIdGenerator.generate());
        permission.setCode(uniqueCode);
        permission.setName(codePrefix + " Test Permission");
        permission.setDescription("Test permission for UserPermissionService tests");
        permission.setResourceType("model");
        permission.setResourceCode("test_model");
        permission.setAction("read");
        permission.setSource("system");
        permission.setStatus("active");
        permission.setDeletedFlag(false);
        permission.setTenantId(testTenant.getId());
        permission.setCreatedAt(Instant.now());
        permission.setUpdatedAt(Instant.now());
        permissionMapper.insert(permission);
        return permission;
    }

    private void bindPermissionToRole(Long permissionId) {
        RolePermission binding = new RolePermission();
        binding.setPid(UniqueIdGenerator.generate());
        binding.setRoleId(testRole.getId());
        binding.setPermissionId(permissionId);
        binding.setGrantType("grant");
        binding.setPriority(100);
        binding.setStatus("active");
        binding.setDeletedFlag(false);
        binding.setCreatedAt(Instant.now());
        binding.setUpdatedAt(Instant.now());
        rolePermissionMapper.insert(binding);
    }

    private void evictUserPermissionCache() {
        Cache cache = cacheManager.getCache(CACHE_NAME);
        if (cache != null) {
            String key = MetaCacheKeyGenerator.getTenantContextSuffix() + ":" + testUser.getId();
            cache.evict(key);
        }
    }

    // ==================== Tests ====================

    /**
     * UP-01: getUserPermissionIds for test user returns a non-empty set
     *        that includes the permission bound via the test role.
     */
    @Test
    @Order(1)
    @DisplayName("UP-01: getUserPermissionIds returns set containing bound permission")
    void up01_getUserPermissionIdsContainsBoundPermission() {
        Set<Long> permissionIds = userPermissionService.getUserPermissionIds(testUser.getId());

        assertThat(permissionIds).isNotNull();
        assertThat(permissionIds).isNotEmpty();
        assertThat(permissionIds).contains(testPermission.getId());
    }

    /**
     * UP-02: hasPermission(userId, permissionId) returns true for a bound permission
     *        and false for an unbound one.
     */
    @Test
    @Order(2)
    @DisplayName("UP-02: hasPermission by ID returns true for bound, false for unbound")
    void up02_hasPermissionByIdReturnsCorrectResult() {
        // Bound permission → must return true
        boolean hasBound = userPermissionService.hasPermission(testUser.getId(), testPermission.getId());
        assertThat(hasBound).isTrue();

        // Non-existent / unbound permission ID → must return false
        boolean hasUnbound = userPermissionService.hasPermission(testUser.getId(), Long.MAX_VALUE);
        assertThat(hasUnbound).isFalse();
    }

    /**
     * UP-02b: hasPermission(userId, permissionCode) returns true for a bound code
     *         and false for an unknown code.
     */
    @Test
    @Order(3)
    @DisplayName("UP-02b: hasPermission by code returns true for bound code, false for unknown")
    void up02b_hasPermissionByCodeReturnsCorrectResult() {
        boolean hasBoundCode = userPermissionService.hasPermission(testUser.getId(), testPermission.getCode());
        assertThat(hasBoundCode).isTrue();

        boolean hasUnknownCode = userPermissionService.hasPermission(testUser.getId(), "nonexistent_code_" + System.nanoTime());
        assertThat(hasUnknownCode).isFalse();
    }

    /**
     * UP-03: evictUserPermissions does not throw and clears the cache entry
     *        (subsequent call still returns correct data via DB fallback).
     */
    @Test
    @Order(4)
    @DisplayName("UP-03: evictUserPermissions does not throw and subsequent load still works")
    void up03_evictUserPermissionsDoesNotThrow() {
        // Prime the cache
        userPermissionService.getUserPermissionIds(testUser.getId());

        // Evict must not throw
        assertDoesNotThrow(() -> userPermissionService.evictUserPermissions(testUser.getId()));

        // After eviction the service must still return correct data from DB
        Set<Long> permissionIds = userPermissionService.getUserPermissionIds(testUser.getId());
        assertThat(permissionIds).contains(testPermission.getId());
    }

    /**
     * UP-04: clearPermissionCodeCache does not throw and the cache is cleared.
     */
    @Test
    @Order(5)
    @DisplayName("UP-04: clearPermissionCodeCache does not throw")
    void up04_clearPermissionCodeCacheDoesNotThrow() {
        // Prime the code cache by calling hasPermission by code
        userPermissionService.hasPermission(testUser.getId(), testPermission.getCode());

        // Clear must not throw
        assertDoesNotThrow(() -> userPermissionServiceImpl.clearPermissionCodeCache());

        // After clearing, hasPermission must still work (re-fetches from DB + rebuilds cache)
        boolean result = userPermissionService.hasPermission(testUser.getId(), testPermission.getCode());
        assertThat(result).isTrue();
    }

    /**
     * UP-05: evictRoleUsers does not throw and evicts caches for all users of the role.
     */
    @Test
    @Order(6)
    @DisplayName("UP-05: evictRoleUsers does not throw")
    void up05_evictRoleUsersDoesNotThrow() {
        // Prime the cache
        userPermissionService.getUserPermissionIds(testUser.getId());

        assertDoesNotThrow(() -> userPermissionService.evictRoleUsers(testRole.getId()));

        // After eviction the service must still return correct data
        Set<Long> permissionIds = userPermissionService.getUserPermissionIds(testUser.getId());
        assertThat(permissionIds).contains(testPermission.getId());
    }

    /**
     * UP-06: hasAllPermissions returns true when user has all listed IDs,
     *        false when at least one is missing.
     */
    @Test
    @Order(7)
    @DisplayName("UP-06: hasAllPermissions returns correct AND logic result")
    void up06_hasAllPermissionsReturnsCorrectResult() {
        // User has testPermission → single-element list must return true
        boolean hasAll = userPermissionService.hasAllPermissions(
            testUser.getId(), Collections.singletonList(testPermission.getId()));
        assertThat(hasAll).isTrue();

        // User does NOT have Long.MAX_VALUE → must return false
        boolean hasMissing = userPermissionService.hasAllPermissions(
            testUser.getId(), Arrays.asList(testPermission.getId(), Long.MAX_VALUE));
        assertThat(hasMissing).isFalse();
    }

    /**
     * UP-07: hasAnyPermission returns true when user has at least one,
     *        false when none match.
     */
    @Test
    @Order(8)
    @DisplayName("UP-07: hasAnyPermission returns correct OR logic result")
    void up07_hasAnyPermissionReturnsCorrectResult() {
        // User has testPermission → must return true even if second ID is missing
        boolean hasAny = userPermissionService.hasAnyPermission(
            testUser.getId(), Arrays.asList(testPermission.getId(), Long.MAX_VALUE));
        assertThat(hasAny).isTrue();

        // Neither ID is bound → must return false
        boolean hasNone = userPermissionService.hasAnyPermission(
            testUser.getId(), Arrays.asList(Long.MAX_VALUE - 1, Long.MAX_VALUE));
        assertThat(hasNone).isFalse();
    }

    /**
     * UP-08: batchGetUserPermissionIds returns correct map for a list of user IDs.
     */
    @Test
    @Order(9)
    @DisplayName("UP-08: batchGetUserPermissionIds returns correct map")
    void up08_batchGetUserPermissionIdsReturnsCorrectMap() {
        Map<Long, Set<Long>> result = userPermissionService.batchGetUserPermissionIds(
            Collections.singletonList(testUser.getId()));

        assertThat(result).isNotNull();
        assertThat(result).containsKey(testUser.getId());
        assertThat(result.get(testUser.getId())).contains(testPermission.getId());
    }

    /**
     * UP-09: batchGetUserPermissionIds with empty list returns empty map.
     */
    @Test
    @Order(10)
    @DisplayName("UP-09: batchGetUserPermissionIds with empty input returns empty map")
    void up09_batchGetUserPermissionIdsEmptyInputReturnsEmptyMap() {
        Map<Long, Set<Long>> result = userPermissionService.batchGetUserPermissionIds(
            Collections.emptyList());

        assertThat(result).isNotNull();
        assertThat(result).isEmpty();
    }

    /**
     * UP-10: hasPermission with null arguments returns false without throwing.
     */
    @Test
    @Order(11)
    @DisplayName("UP-10: hasPermission with null arguments returns false gracefully")
    void up10_hasPermissionWithNullArgumentsReturnsFalse() {
        assertThat(userPermissionService.hasPermission((Long) null, testPermission.getId())).isFalse();
        assertThat(userPermissionService.hasPermission(testUser.getId(), (Long) null)).isFalse();
        assertThat(userPermissionService.hasPermission((Long) null, (Long) null)).isFalse();
    }
}
