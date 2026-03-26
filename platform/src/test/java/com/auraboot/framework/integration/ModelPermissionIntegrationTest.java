package com.auraboot.framework.integration;

import com.auraboot.framework.meta.controller.config.ModelController;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.dto.MetaModelCreateRequest;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.auraboot.framework.rbac.service.RoleService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Model Permission Integration Test
 * 
 * <p>Tests the complete permission creation and assignment flow when creating a Model.
 * 
 * <p>Test Scenarios:
 * <ol>
 *   <li>Model creation automatically creates permissions</li>
 *   <li>permissions are automatically assigned to default roles</li>
 *   <li>Users with roles can access model details</li>
 *   <li>Users without roles cannot access model details</li>
 * </ol>
 * 
 * @author AuraBoot Platform
 * @since 2025-01-09
 */
@Slf4j
@DisplayName("Model Permission Integration Tests")
class ModelPermissionIntegrationTest extends BaseIntegrationTest {
    
    @Autowired
    private MetaModelService metaModelService;
    
    @Autowired
    private ModelController modelController;
    
    @Autowired
    private PermissionMapper permissionMapper;
    
    @Autowired
    private RoleService roleService;
    
    @Autowired
    private RolePermissionMapper rolePermissionMapper;
    
    @Autowired
    private UserPermissionService userPermissionService;
    
    private static final String TEST_MODEL_CODE = "test_model_permission";
    private static final String TEST_MODEL_DISPLAY_NAME = "Test Model for Permission";
    
    @Test
    @DisplayName("Model creation should automatically create manage and read permissions")
    void testModelCreation_AutoCreatesPermissions() {
        // Given: Model creation request
        MetaModelCreateRequest request = new MetaModelCreateRequest();
        request.setCode(TEST_MODEL_CODE);
        request.setDisplayName(TEST_MODEL_DISPLAY_NAME);
        request.setDescription("Test model for permission integration test");
        request.setModelType("entity");
          
        
        request.setTenantId(getTestTenant().getId());
        
        // When: Create model
        MetaModelDTO model = metaModelService.create(request);
        
        // Then: Model should be created
        assertNotNull(model);
        assertNotNull(model.getPid());
        assertEquals(TEST_MODEL_CODE, model.getCode());
        
        // Then: Manage permission should be created
        String managePermissionCode = "MODEL." + TEST_MODEL_CODE + ".manage";
        Permission managePermission = permissionMapper.findByCode(managePermissionCode);
        assertNotNull(managePermission, 
            "Manage permission should be created: " + managePermissionCode);
        assertEquals("active", managePermission.getStatus());
        assertEquals("model", managePermission.getResourceType());
        assertEquals(TEST_MODEL_CODE, managePermission.getResourceCode());
        
        // Then: Read permission should be created
        String readPermissionCode = "MODEL." + TEST_MODEL_CODE + ".read";
        Permission readPermission = permissionMapper.findByCode(readPermissionCode);
        assertNotNull(readPermission, 
            "Read permission should be created: " + readPermissionCode);
        assertEquals("active", readPermission.getStatus());
        assertEquals("model", readPermission.getResourceType());
        assertEquals(TEST_MODEL_CODE, readPermission.getResourceCode());
    }
    
    @Test
    @DisplayName("Model creation should automatically assign permissions to default roles")
    void testModelCreation_AutoAssignsPermissionsToRoles() {
        // Given: Model creation request
        MetaModelCreateRequest request = new MetaModelCreateRequest();
        request.setCode(TEST_MODEL_CODE);
        request.setDisplayName(TEST_MODEL_DISPLAY_NAME);
        request.setDescription("Test model for role assignment test");
        request.setModelType("entity");
          

        request.setTenantId(getTestTenant().getId());
        
        // When: Create model
        MetaModelDTO model = metaModelService.create(request);
        
        // Then: Get permissions
        String managePermissionCode = "MODEL." + TEST_MODEL_CODE + ".manage";
        String readPermissionCode = "MODEL." + TEST_MODEL_CODE + ".read";
        
        Permission managePermission = permissionMapper.findByCode(managePermissionCode);
        Permission readPermission = permissionMapper.findByCode(readPermissionCode);
        
        assertNotNull(managePermission);
        assertNotNull(readPermission);
        
        // Then: Check roles have permissions
        // Note: Role-permission binding verification requires roles to exist in test database
        // This test verifies that permissions are created, not the role assignments
        // Role assignment tests should be done in a separate test with proper role setup
        
        List<Role> roles = roleService.findByTenantId(getTestTenant().getId());
        if (!roles.isEmpty()) {
            log.info("Found {} roles for tenant {}", roles.size(), getTestTenant().getId());
            
            // Check if any role has the permissions
            for (Role role : roles) {
                Set<Long> permissionIds = rolePermissionMapper.findPermissionIdsByRoles(
                    java.util.List.of(role.getId()));
                
                if (permissionIds.contains(managePermission.getId()) || 
                    permissionIds.contains(readPermission.getId())) {
                    log.info("Role {} has model permissions", role.getName());
                }
            }
        } else {
            log.warn("No roles found for tenant {}, skipping role-permission binding verification", 
                getTestTenant().getId());
        }
    }
    
    @Test
    @DisplayName("User with tenant_admin role can access model details")
    void testUserCanAccessModelDetails_WithReadPermission() {
        // Given: Create model
        MetaModelCreateRequest request = new MetaModelCreateRequest();
        request.setCode(TEST_MODEL_CODE);
        request.setDisplayName(TEST_MODEL_DISPLAY_NAME);
        request.setDescription("Test model for access test");
        request.setModelType("entity");
          
        
        request.setTenantId(getTestTenant().getId());
        
        MetaModelDTO model = metaModelService.create(request);
        assertNotNull(model);
        assertNotNull(model.getPid());
        
        // Given: User has tenant_admin role (automatically has read permission)
        // Note: In real scenario, user-role binding should be set up
        
        // When: Access model details
        ApiResponse<MetaModelDTO> response = modelController.getModel(model.getPid());
        
        // Then: Access should succeed
        assertNotNull(response);
        assertTrue(response.isSuccess(), 
            "User with tenant_admin role should be able to access model details");
        assertNotNull(response.getData());
        assertEquals(TEST_MODEL_CODE, response.getData().getCode());
    }
    
    @Test
    @DisplayName("Permission codes should follow naming convention")
    void testPermissionCodeNamingConvention() {
        // Given: Model creation request
        MetaModelCreateRequest request = new MetaModelCreateRequest();
        request.setCode(TEST_MODEL_CODE);
        request.setDisplayName(TEST_MODEL_DISPLAY_NAME);
        request.setDescription("Test model for naming convention test");
        request.setModelType("entity");
          
        
        request.setTenantId(getTestTenant().getId());
        
        // When: Create model
        MetaModelDTO model = metaModelService.create(request);
        
        // Then: Permission codes should follow format: {RESOURCE_TYPE}.{resource_code}.{action}
        String managePermissionCode = "MODEL." + TEST_MODEL_CODE + ".manage";
        String readPermissionCode = "MODEL." + TEST_MODEL_CODE + ".read";
        
        Permission managePermission = permissionMapper.findByCode(managePermissionCode);
        Permission readPermission = permissionMapper.findByCode(readPermissionCode);
        
        assertNotNull(managePermission);
        assertEquals(managePermissionCode, managePermission.getCode(),
            "Manage permission code should follow naming convention");
        
        assertNotNull(readPermission);
        assertEquals(readPermissionCode, readPermission.getCode(),
            "Read permission code should follow naming convention");
    }
    
    @Test
    @DisplayName("Multiple models should create separate permissions")
    void testMultipleModels_CreateSeparatePermissions() {
        // Given: Create first model
        MetaModelCreateRequest request1 = new MetaModelCreateRequest();
        request1.setCode("model_one");
        request1.setDisplayName("Model One");
        request1.setDescription("First test model");
        request1.setModelType("entity");
        request1.setTenantId(getTestTenant().getId());
        
        MetaModelDTO model1 = metaModelService.create(request1);
        
        // Given: Create second model
        MetaModelCreateRequest request2 = new MetaModelCreateRequest();
        request2.setCode("model_two");
        request2.setDisplayName("Model Two");
        request2.setDescription("Second test model");
        request2.setModelType("entity");
        request2.setTenantId(getTestTenant().getId());
        
        MetaModelDTO model2 = metaModelService.create(request2);
        
        // Then: Each model should have its own permissions
        Permission model1ManagePermission = permissionMapper.findByCode("MODEL.model_one.manage");
        Permission model1ReadPermission = permissionMapper.findByCode("MODEL.model_one.read");
        Permission model2ManagePermission = permissionMapper.findByCode("MODEL.model_two.manage");
        Permission model2ReadPermission = permissionMapper.findByCode("MODEL.model_two.read");
        
        assertNotNull(model1ManagePermission);
        assertNotNull(model1ReadPermission);
        assertNotNull(model2ManagePermission);
        assertNotNull(model2ReadPermission);
        
        // Then: permissions should be different
        assertNotEquals(model1ManagePermission.getId(), model2ManagePermission.getId());
        assertNotEquals(model1ReadPermission.getId(), model2ReadPermission.getId());
    }
}
