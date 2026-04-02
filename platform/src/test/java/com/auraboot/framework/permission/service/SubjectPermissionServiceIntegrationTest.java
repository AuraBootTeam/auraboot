package com.auraboot.framework.permission.service;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.permission.dto.SubjectPermissionCreateRequest;
import com.auraboot.framework.permission.dto.SubjectPermissionDTO;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.entity.SubjectPermission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.mapper.SubjectPermissionMapper;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * SubjectPermissionService Integration Test
 * 
 * Tests SubjectPermission CRUD operations and visibility evaluation.
 * Uses real database, no mocking.
 * 
 * @author AuraBoot Platform
 * @since V4
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@DisplayName("SubjectPermissionService Integration Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SubjectPermissionServiceIntegrationTest {

    @Autowired
    private SubjectPermissionService subjectPermissionService;

    @Autowired
    private SubjectPermissionMapper subjectPermissionMapper;

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
    private org.springframework.cache.CacheManager cacheManager;

    // Test context data
    private String testSuffix;
    private User testUser;
    private Tenant testTenant;
    private TenantMember testTenantMember;
    private Role testRole;
    private Permission testPermission1;
    private Permission testPermission2;

    @BeforeAll
    void initTestSuffix() {
        testSuffix = "_" + System.currentTimeMillis();
    }

    @BeforeEach
    void setupTestData() {
        testUser = ensureTestUser();
        testTenant = ensureTestTenant();
        testTenantMember = ensureTestTenantMember();
        
        // Set MetaContext BEFORE creating any entities that need tenant_id
        MetaContext.setContext(
            testTenant.getId(),
            testUser.getId(),
            testUser.getPid(),
            testUser.getUserName()
        );
        MetaContext.setMemberId(testTenantMember.getId());
        
        testRole = createFreshRole();
        ensureUserRoleBinding();
        testPermission1 = createTestPermission("perm_a");
        testPermission2 = createTestPermission("perm_b");
        
        // Clear user permission cache to avoid stale data between tests
        evictUserPermissionCache();
    }
    
    private void evictUserPermissionCache() {
        org.springframework.cache.Cache cache = cacheManager.getCache("user-permissions");
        if (cache != null) {
            // Cache key format: "{tenantId}:{userId}" — must match @Cacheable in UserPermissionServiceImpl
            String cacheKey = com.auraboot.framework.meta.cache.MetaCacheKeyGenerator.getTenantContextSuffix()
                    + ":" + testUser.getId();
            cache.evict(cacheKey);
        }
    }

    @AfterEach
    void clearContext() {
        MetaContext.clear();
    }

    private User ensureTestUser() {
        String email = "subject-perm-test" + testSuffix + "@auraboot.com";
        User existing = userService.findByEmail(email);
        if (existing != null) {
            return existing;
        }
        return userService.signUp(email, "test-password-123");
    }

    private Tenant ensureTestTenant() {
        String tenantName = "subject-perm-tenant" + testSuffix;
        Tenant existing = tenantService.findByName(tenantName);
        if (existing != null) {
            return existing;
        }

        Tenant tenant = new Tenant();
        tenant.setPid(UniqueIdGenerator.generate());
        tenant.setName(tenantName);
        tenant.setDisplayName("Subject Permission Test Tenant");
        tenant.setStatus("active");
        tenant.setContactEmail("admin@subject-perm-test.com");
        tenant.setDescription("Subject permission integration test tenant");
        tenant.setDeletedFlag(false);
        tenant.setCreatedAt(Instant.now());
        tenant.setUpdatedAt(Instant.now());
        return tenantService.createTenant(tenant);
    }

    private TenantMember ensureTestTenantMember() {
        TenantMember existing = tenantMemberService.findByTenantIdAndUserId(
            testTenant.getId(), testUser.getId());
        if (existing == null) {
            return tenantMemberService.addMember(testUser.getId(), testTenant.getId(), "active");
        }
        return existing;
    }

    private Role createFreshRole() {
        // Keep name/code under 50 chars (DB constraint)
        String uniqueSuffix = String.valueOf(System.nanoTime()).substring(8); // Last 11 digits
        Role role = new Role();
        role.setPid(UniqueIdGenerator.generate());
        role.setName("sp_test_" + uniqueSuffix);
        role.setCode("sp_test_" + uniqueSuffix);
        role.setDescription("Subject permission test role");
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
        userRoleService.assignRolesToMember(
            testTenantMember.getId(),
            Arrays.asList(testRole.getId()),
            testTenant.getId(),
            null
        );
    }

    private Permission createTestPermission(String code) {
        String uniqueCode = code + "_" + System.nanoTime();
        Permission permission = new Permission();
        permission.setPid(UniqueIdGenerator.generate());
        permission.setCode(uniqueCode);
        permission.setName(code + " Test Permission");
        permission.setDescription("Test permission for subject permission tests");
        permission.setResourceType("test");
        permission.setResourceCode("test");
        permission.setAction("test");
        permission.setSource("system");
        permission.setStatus("active");
        permission.setDeletedFlag(false);
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

    // ==================== CRUD Tests ====================

    @Test
    @Order(1)
    @DisplayName("Should add permission declaration to subject")
    void testAddPermission() {
        Long subjectId = System.nanoTime();

        SubjectPermissionCreateRequest request = new SubjectPermissionCreateRequest();
        request.setSubjectType("button");
        request.setSubjectId(subjectId);
        request.setPermissionId(testPermission1.getId());
        request.setLogicGroup(1);
        request.setGroupLogicType("and");
        request.setIsNegated(false);
        request.setLogicOrder(1);

        SubjectPermissionDTO result = subjectPermissionService.addPermission(request);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isNotNull();
        assertThat(result.getSubjectType()).isEqualTo("button");
        assertThat(result.getSubjectId()).isEqualTo(subjectId);
    }

    @Test
    @Order(2)
    @DisplayName("Should find declarations by subject")
    void testFindBySubject() {
        Long subjectId = System.nanoTime();

        // Add declaration
        SubjectPermissionCreateRequest request = new SubjectPermissionCreateRequest();
        request.setSubjectType("menu");
        request.setSubjectId(subjectId);
        request.setPermissionId(testPermission1.getId());
        request.setLogicGroup(1);
        request.setGroupLogicType("and");
        request.setIsNegated(false);
        request.setLogicOrder(1);
        subjectPermissionService.addPermission(request);

        // Find
        List<SubjectPermissionDTO> result = subjectPermissionService.findBySubject("menu", subjectId);

        assertThat(result).isNotEmpty();
        assertThat(result.get(0).getSubjectId()).isEqualTo(subjectId);
    }

    @Test
    @Order(3)
    @DisplayName("Should remove permission declaration")
    void testRemovePermission() {
        Long subjectId = System.nanoTime();

        // Add declaration
        SubjectPermissionCreateRequest request = new SubjectPermissionCreateRequest();
        request.setSubjectType("button");
        request.setSubjectId(subjectId);
        request.setPermissionId(testPermission1.getId());
        request.setLogicGroup(1);
        request.setGroupLogicType("and");
        request.setIsNegated(false);
        request.setLogicOrder(1);
        SubjectPermissionDTO created = subjectPermissionService.addPermission(request);

        // Remove
        subjectPermissionService.removePermission(created.getId());

        // Verify removed - after soft delete, findBySubject should not return the record
        List<SubjectPermissionDTO> remaining = subjectPermissionService.findBySubject("button", subjectId);
        assertThat(remaining).isEmpty();
    }

    @Test
    @Order(4)
    @DisplayName("Should remove all declarations for a subject")
    void testRemoveAllPermissions() {
        Long subjectId = System.nanoTime();

        // Add multiple declarations
        SubjectPermissionCreateRequest request1 = new SubjectPermissionCreateRequest();
        request1.setSubjectType("menu");
        request1.setSubjectId(subjectId);
        request1.setPermissionId(testPermission1.getId());
        request1.setLogicGroup(1);
        request1.setGroupLogicType("and");
        request1.setIsNegated(false);
        request1.setLogicOrder(1);

        SubjectPermissionCreateRequest request2 = new SubjectPermissionCreateRequest();
        request2.setSubjectType("menu");
        request2.setSubjectId(subjectId);
        request2.setPermissionId(testPermission2.getId());
        request2.setLogicGroup(1);
        request2.setGroupLogicType("and");
        request2.setIsNegated(false);
        request2.setLogicOrder(2);

        subjectPermissionService.addPermission(request1);
        subjectPermissionService.addPermission(request2);

        // Remove all
        subjectPermissionService.removeAllPermissions("menu", subjectId);

        // Verify all removed
        List<SubjectPermissionDTO> remaining = subjectPermissionService.findBySubject("menu", subjectId);
        assertThat(remaining).isEmpty();
    }

    // ==================== Visibility Evaluation Tests ====================

    @Test
    @Order(10)
    @DisplayName("Should evaluate visibility - user has required permission")
    void testEvaluateVisibility_HasPermission() {
        Long subjectId = System.nanoTime();

        // Bind permission to user's role
        bindPermissionToRole(testPermission1.getId());

        // Add subject permission declaration
        SubjectPermissionCreateRequest request = new SubjectPermissionCreateRequest();
        request.setSubjectType("button");
        request.setSubjectId(subjectId);
        request.setPermissionId(testPermission1.getId());
        request.setLogicGroup(1);
        request.setGroupLogicType("and");
        request.setIsNegated(false);
        request.setLogicOrder(1);
        subjectPermissionService.addPermission(request);

        // Evaluate
        boolean result = subjectPermissionService.evaluateVisibility("button", subjectId, testUser.getId());

        assertThat(result).isTrue();
    }

    @Test
    @Order(11)
    @DisplayName("Should evaluate visibility - user lacks required permission")
    void testEvaluateVisibility_LacksPermission() {
        Long subjectId = System.nanoTime();

        // Do NOT bind permission to user's role

        // Add subject permission declaration
        SubjectPermissionCreateRequest request = new SubjectPermissionCreateRequest();
        request.setSubjectType("button");
        request.setSubjectId(subjectId);
        request.setPermissionId(testPermission1.getId());
        request.setLogicGroup(1);
        request.setGroupLogicType("and");
        request.setIsNegated(false);
        request.setLogicOrder(1);
        subjectPermissionService.addPermission(request);

        // Evaluate
        boolean result = subjectPermissionService.evaluateVisibility("button", subjectId, testUser.getId());

        assertThat(result).isFalse();
    }

    @Test
    @Order(12)
    @DisplayName("Should evaluate visibility - no declarations means visible")
    void testEvaluateVisibility_NoDeclarations() {
        Long subjectId = System.nanoTime();

        // No declarations added

        // Evaluate
        boolean result = subjectPermissionService.evaluateVisibility("button", subjectId, testUser.getId());

        assertThat(result).isTrue();
    }

    // ==================== Batch Evaluation Tests ====================

    @Test
    @Order(20)
    @DisplayName("Should batch evaluate visibility for multiple subjects")
    void testBatchEvaluateVisibility() {
        Long subjectId1 = System.nanoTime();
        Long subjectId2 = subjectId1 + 1;
        Long subjectId3 = subjectId1 + 2;

        // Bind permission1 to user's role
        bindPermissionToRole(testPermission1.getId());

        // Subject 1: requires permission1 (user has it)
        SubjectPermissionCreateRequest request1 = new SubjectPermissionCreateRequest();
        request1.setSubjectType("menu");
        request1.setSubjectId(subjectId1);
        request1.setPermissionId(testPermission1.getId());
        request1.setLogicGroup(1);
        request1.setGroupLogicType("and");
        request1.setIsNegated(false);
        request1.setLogicOrder(1);
        subjectPermissionService.addPermission(request1);

        // Subject 2: requires permission2 (user does NOT have it)
        SubjectPermissionCreateRequest request2 = new SubjectPermissionCreateRequest();
        request2.setSubjectType("menu");
        request2.setSubjectId(subjectId2);
        request2.setPermissionId(testPermission2.getId());
        request2.setLogicGroup(1);
        request2.setGroupLogicType("and");
        request2.setIsNegated(false);
        request2.setLogicOrder(1);
        subjectPermissionService.addPermission(request2);

        // Subject 3: no declarations (default visible)

        // Batch evaluate
        Map<Long, Boolean> result = subjectPermissionService.batchEvaluateVisibility(
            "menu",
            Arrays.asList(subjectId1, subjectId2, subjectId3),
            testUser.getId()
        );

        assertThat(result).hasSize(3);
        assertThat(result.get(subjectId1)).isTrue();   // Has permission1
        assertThat(result.get(subjectId2)).isFalse();  // Lacks permission2
        assertThat(result.get(subjectId3)).isTrue();   // No declarations
    }

    // ==================== Logic Group Validation Tests ====================

    @Test
    @Order(30)
    @DisplayName("Should validate logic group consistency")
    void testValidateLogicGroupConsistency() {
        Long subjectId = System.nanoTime();

        // Add declarations with consistent logic type
        SubjectPermissionCreateRequest request1 = new SubjectPermissionCreateRequest();
        request1.setSubjectType("button");
        request1.setSubjectId(subjectId);
        request1.setPermissionId(testPermission1.getId());
        request1.setLogicGroup(1);
        request1.setGroupLogicType("and");
        request1.setIsNegated(false);
        request1.setLogicOrder(1);

        SubjectPermissionCreateRequest request2 = new SubjectPermissionCreateRequest();
        request2.setSubjectType("button");
        request2.setSubjectId(subjectId);
        request2.setPermissionId(testPermission2.getId());
        request2.setLogicGroup(1);
        request2.setGroupLogicType("and");
        request2.setIsNegated(false);
        request2.setLogicOrder(2);

        subjectPermissionService.addPermission(request1);
        subjectPermissionService.addPermission(request2);

        // Validate
        boolean result = subjectPermissionService.validateLogicGroupConsistency("button", subjectId, 1);

        assertThat(result).isTrue();
    }

    // ==================== Cache Eviction Tests ====================

    @Test
    @Order(40)
    @DisplayName("Should evict subject evaluations from cache")
    void testEvictSubjectEvaluations() {
        Long subjectId = System.nanoTime();

        org.springframework.cache.Cache cache = cacheManager.getCache("subject-evaluation");
        assertThat(cache).isNotNull();

        String cacheKey = "BUTTON:" + subjectId + ":" + testUser.getId();
        cache.put(cacheKey, Boolean.TRUE);
        assertThat(cache.get(cacheKey)).isNotNull();

        subjectPermissionService.evictSubjectEvaluations("button", subjectId);

        assertThat(cache.get(cacheKey)).isNull();
    }
}
