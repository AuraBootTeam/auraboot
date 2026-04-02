package com.auraboot.framework.permission.integration;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.permission.dto.PermissionCreateRequest;
import com.auraboot.framework.permission.dto.PermissionDTO;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.PermissionService;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.entity.RolePermission;
import com.auraboot.framework.rbac.entity.UserRole;
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
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * Permission Integration Test - Fully Self-Contained
 * 
 * Each test method creates its own test data to avoid conflicts
 * when running with other test classes.
 * 
 * @author AuraBoot Platform
 * @since V4
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@DisplayName("Permission Integration Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PermissionIntegrationTest {

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
    
    // Test-specific data created fresh for each test
    private String testSuffix;
    private User localUser;
    private Tenant localTenant;
    private TenantMember localTenantMember;
    private Role localRole;
    
    @BeforeAll
    void initTestSuffix() {
        testSuffix = "_" + System.currentTimeMillis();
    }
    
    @BeforeEach
    void setupTestData() {
        // Create fresh test data for each test
        localUser = ensureTestUser();
        localTenant = ensureTestTenant();
        localTenantMember = ensureTestTenantMember();
        localRole = createFreshRole();
        ensureUserRoleBinding();
        
        // Set MetaContext
        MetaContext.setContext(
            localTenant.getId(),
            localUser.getId(),
            localUser.getPid(),
            localUser.getUserName()
        );
    }
    
    private User ensureTestUser() {
        String email = "perm-test" + testSuffix + "@auraboot.com";
        User existing = userService.findByEmail(email);
        if (existing != null) {
            return existing;
        }
        return userService.signUp(email, "test-password-123");
    }
    
    private Tenant ensureTestTenant() {
        String tenantName = "perm-test-tenant" + testSuffix;
        Tenant existing = tenantService.findByName(tenantName);
        if (existing != null) {
            return existing;
        }
        
        Tenant tenant = new Tenant();
        tenant.setPid(UniqueIdGenerator.generate());
        tenant.setName(tenantName);
        tenant.setDisplayName("Permission Test Tenant");
        tenant.setStatus("active");
        tenant.setContactEmail("admin@perm-test.com");
        tenant.setDescription("Permission integration test tenant");
        tenant.setDeletedFlag(false);
        tenant.setCreatedAt(Instant.now());
        tenant.setUpdatedAt(Instant.now());
        return tenantService.createTenant(tenant);
    }
    
    private TenantMember ensureTestTenantMember() {
        TenantMember existing = tenantMemberService.findByTenantIdAndUserId(
            localTenant.getId(), localUser.getId());
        if (existing == null) {
            return tenantMemberService.addMember(localUser.getId(), localTenant.getId(), "active");
        }
        return existing;
    }
    
    private Role createFreshRole() {
        // Create a new role for each test run to avoid conflicts
        Role role = new Role();
        role.setPid(UniqueIdGenerator.generate());
        role.setName("perm_test_role" + testSuffix + "_" + System.nanoTime());
        role.setCode("perm_test_role" + testSuffix + "_" + System.nanoTime());
        role.setDescription("Permission test role");
        role.setType("custom");
        role.setScopeType("tenant");
        role.setStatus("active");
        role.setTenantId(localTenant.getId());
        role.setIsDefault(false);
        role.setIsSystem(false);
        role.setDeletedFlag(false);
        role.setPriority(100);
        role.setCreatedAt(Instant.now());
        role.setUpdatedAt(Instant.now());
        return roleService.createRole(role);
    }
    
    private void ensureUserRoleBinding() {
        UserRole existing = userRoleService.findByMemberIdAndRoleIdAndTenantId(
            localTenantMember.getId(), localRole.getId(), localTenant.getId());
        if (existing == null) {
            userRoleService.assignRolesToMember(
                localTenantMember.getId(),
                Arrays.asList(localRole.getId()),
                localTenant.getId(),
                null
            );
        }
    }
    
    /**
     * Helper method to create a unique permission
     */
    private PermissionDTO createUniquePermission(String prefix) {
        String uniqueCode = "MODEL." + prefix + "_" + System.nanoTime() + ".test";
        PermissionCreateRequest request = new PermissionCreateRequest();
        request.setCode(uniqueCode);
        request.setName(prefix + " Test Permission");
        request.setDescription("Permission for " + prefix + " test");
        request.setResourceType("model");
        request.setResourceCode(prefix);
        request.setAction("test");
        request.setSource("system");
        return permissionService.create(request);
    }
    
    /**
     * Helper method to bind permission to role
     */
    private void bindPermissionToRole(Long permissionId, Long roleId) {
        boolean alreadyBound = rolePermissionMapper.hasPermission(roleId, permissionId);
        if (alreadyBound) {
            return;
        }
        
        RolePermission binding = new RolePermission();
        binding.setPid(UniqueIdGenerator.generate());
        binding.setRoleId(roleId);
        binding.setPermissionId(permissionId);
        binding.setGrantType("grant");
        binding.setPriority(100);
        binding.setStatus("active");
        binding.setDeletedFlag(false);
        binding.setCreatedAt(Instant.now());
        binding.setUpdatedAt(Instant.now());
        rolePermissionMapper.insert(binding);
    }

    @Test
    @DisplayName("Should create permission successfully")
    void testCreatePermission() {
        String uniqueCode = "MODEL.create_test_" + System.nanoTime() + ".create";
        PermissionCreateRequest request = new PermissionCreateRequest();
        request.setCode(uniqueCode);
        request.setName("Create User Model");
        request.setDescription("Permission to create user model");
        request.setResourceType("model");
        request.setResourceCode("user_model");
        request.setAction("create");
        request.setSource("system");
        
        PermissionDTO result = permissionService.create(request);
        
        assertThat(result).isNotNull();
        assertThat(result.getId()).isNotNull();
        assertThat(result.getCode()).isEqualTo(uniqueCode);
        assertThat(result.getStatus()).isEqualTo("active");
    }

    @Test
    @DisplayName("Should find permission by code")
    void testFindByCode() {
        PermissionDTO created = createUniquePermission("find_by_code");
        
        PermissionDTO result = permissionService.findByCode(created.getCode());
        
        assertThat(result).isNotNull();
        assertThat(result.getCode()).isEqualTo(created.getCode());
    }

    @Test
    @DisplayName("Should find permissions by resource type")
    void testFindByResourceType() {
        PermissionDTO created = createUniquePermission("find_by_type");
        
        List<PermissionDTO> result = permissionService.findByResourceType("model");

        assertThat(result).isNotEmpty();
        assertThat(result).anyMatch(p -> created.getCode().equals(p.getCode()));
    }

    @Test
    @DisplayName("Should bind permission to role")
    void testBindPermissionToRole() {
        PermissionDTO permission = createUniquePermission("bind_test");
        
        bindPermissionToRole(permission.getId(), localRole.getId());
        
        boolean bound = rolePermissionMapper.hasPermission(localRole.getId(), permission.getId());
        assertThat(bound).isTrue();
    }
    
    @Test
    @DisplayName("Should find user permissions through RBAC")
    void testFindUserPermissions() {
        PermissionDTO permission = createUniquePermission("user_perm");
        bindPermissionToRole(permission.getId(), localRole.getId());
        
        List<PermissionDTO> result = permissionService.findUserPermissions(localUser.getId());
        
        assertThat(result).isNotEmpty();
        assertThat(result).anyMatch(p -> permission.getCode().equals(p.getCode()));
    }
    
    @Test
    @DisplayName("Should deprecate permission")
    void testDeprecatePermission() {
        PermissionDTO permission = createUniquePermission("deprecate_test");
        
        permissionService.deprecate(permission.getId());
        
        Permission updated = permissionMapper.selectById(permission.getId());
        assertThat(updated.getStatus()).isEqualTo("deprecated");
        assertThat(updated.getDeprecatedAt()).isNotNull();
    }
    
    @Test
    @DisplayName("Should find deprecated permissions for archiving")
    void testFindDeprecatedForArchive() {
        PermissionDTO permission = createUniquePermission("archive_test");
        permissionService.deprecate(permission.getId());
        
        List<PermissionDTO> result = permissionService.findDeprecatedForArchive(0);
        
        assertThat(result).isNotEmpty();
        assertThat(result).anyMatch(p -> p.getId().equals(permission.getId()));
    }

    @Test
    @DisplayName("Should query permission IDs by role")
    void testFindPermissionIdsByRole() {
        PermissionDTO permission = createUniquePermission("role_perm_ids");
        bindPermissionToRole(permission.getId(), localRole.getId());
        
        Set<Long> result = rolePermissionMapper.findPermissionIdsByRole(localRole.getId());
        
        assertThat(result).isNotEmpty();
        assertThat(result).contains(permission.getId());
    }
    
    @Test
    @DisplayName("Should check if role has permission")
    void testHasPermission() {
        PermissionDTO permission = createUniquePermission("has_perm_test");
        bindPermissionToRole(permission.getId(), localRole.getId());
        
        boolean result = rolePermissionMapper.hasPermission(localRole.getId(), permission.getId());
        
        assertThat(result).isTrue();
    }
    
    @Test
    @DisplayName("Should handle DENY binding correctly")
    void testDenyBinding() {
        PermissionDTO permission = createUniquePermission("deny_binding");

        RolePermission grantBinding = new RolePermission();
        grantBinding.setPid(UniqueIdGenerator.generate());
        grantBinding.setRoleId(localRole.getId());
        grantBinding.setPermissionId(permission.getId());
        grantBinding.setGrantType("grant");
        grantBinding.setPriority(100);
        grantBinding.setStatus("active");
        grantBinding.setDeletedFlag(false);
        grantBinding.setCreatedAt(Instant.now());
        grantBinding.setUpdatedAt(Instant.now());
        rolePermissionMapper.insert(grantBinding);

        // Update to DENY
        grantBinding.setGrantType("deny");
        grantBinding.setPriority(200);
        grantBinding.setUpdatedAt(Instant.now());
        rolePermissionMapper.updateById(grantBinding);

        boolean result = rolePermissionMapper.hasPermission(localRole.getId(), permission.getId());

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Should handle temporal control (effective_date, expiry_date)")
    void testTemporalControl() {
        PermissionDTO permission = createUniquePermission("temporal_ctrl");

        RolePermission futureBinding = new RolePermission();
        futureBinding.setPid(UniqueIdGenerator.generate());
        futureBinding.setRoleId(localRole.getId());
        futureBinding.setPermissionId(permission.getId());
        futureBinding.setGrantType("grant");
        futureBinding.setPriority(100);
        futureBinding.setEffectiveDate(LocalDate.now().plusDays(1));
        futureBinding.setStatus("active");
        futureBinding.setDeletedFlag(false);
        futureBinding.setCreatedAt(Instant.now());
        futureBinding.setUpdatedAt(Instant.now());
        rolePermissionMapper.insert(futureBinding);

        List<RolePermission> effectiveBindings = rolePermissionMapper.findEffectiveByRole(
            localRole.getId(), LocalDate.now());

        assertThat(effectiveBindings).noneMatch(binding ->
            binding.getId().equals(futureBinding.getId()));
    }
    
    @Test
    @DisplayName("Should soft delete permission")
    void testSoftDelete() {
        String uniqueCode = "MODEL.soft_delete_" + System.nanoTime() + ".delete";
        PermissionCreateRequest request = new PermissionCreateRequest();
        request.setCode(uniqueCode);
        request.setName("Delete Test Model");
        request.setResourceType("model");
        request.setResourceCode("test_model");
        request.setAction("delete");
        request.setSource("system");
        PermissionDTO created = permissionService.create(request);

        PermissionDTO beforeDelete = permissionService.findByCode(uniqueCode);
        assertThat(beforeDelete).isNotNull();

        permissionService.delete(created.getId());

        assertThatThrownBy(() -> permissionService.findByCode(uniqueCode))
            .isInstanceOf(com.auraboot.framework.application.exception.ResourceNotFoundException.class)
            .hasMessageContaining(uniqueCode);
    }
}
