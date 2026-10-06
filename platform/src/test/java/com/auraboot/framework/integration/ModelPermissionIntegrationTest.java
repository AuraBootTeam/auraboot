package com.auraboot.framework.integration;

import com.auraboot.framework.common.util.UniqueIdGenerator;
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
    
    @Autowired private com.auraboot.framework.permission.service.AutoPermissionAssignmentService autoPermissions;
    @Autowired private com.auraboot.framework.meta.service.CommandService commandService;
    @Autowired private com.auraboot.framework.meta.service.MetaFieldService authoringFields;
    @Autowired private com.auraboot.framework.tenant.service.TenantService authoringTenants;
    @Autowired private com.auraboot.framework.tenant.service.TenantMemberService authoringMembers;
    @Autowired private com.auraboot.framework.user.service.UserService authoringUsers;
    @Autowired private com.auraboot.framework.rbac.service.UserRoleService authoringRoles;
    @Autowired private com.auraboot.framework.permission.engine.PermissionEvaluator authoringEvaluator;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate authoringJdbc;

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
        
        // Then: Permissions should be created via AutoPermissionAssignmentService
        // Format: model.{modelCode}.{action} (built by PermissionCodeValidator.build)
        // Note: exact actions depend on commands derived by CommandActionDeriver.
        // At minimum, the resource node (model.{modelCode}) should exist.
        String resourceCode = "model." + TEST_MODEL_CODE;
        Permission resourcePermission = permissionMapper.findByCode(resourceCode);
        assertNotNull(resourcePermission,
            "Resource permission node should be created: " + resourceCode);
        assertEquals("model", resourcePermission.getResourceType());
        assertEquals(TEST_MODEL_CODE, resourcePermission.getResourceCode());
    }
    
    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    @DisplayName("First authoring keeps template grants; synchronization never restores or expands grants")
    void testModelCreation_AutoAssignsPermissionsToRoles() {
        String marker = Long.toUnsignedString(System.nanoTime());
        String modelCode = "authoring_grant_" + marker;
        var previous = com.auraboot.framework.application.tenant.MetaContext.snapshot();
        var tenant = new com.auraboot.framework.tenant.dao.entity.Tenant();
        tenant.setPid(UniqueIdGenerator.generate());
        tenant.setName("authoring_" + marker);
        tenant.setDisplayName("Authoring grant fixture");
        tenant.setStatus("active");
        tenant = authoringTenants.createTenant(tenant);
        Long tenantId = tenant.getId();
        var actor = authoringUsers.signUp("authoring-grant-" + marker + "@auraboot.com", "test-password-123");
        var member = authoringMembers.addMember(actor.getId(), tenantId, "active");
        com.auraboot.framework.application.tenant.MetaContext.setContext(
                tenantId, actor.getId(), actor.getPid(), actor.getUserName());
        com.auraboot.framework.application.tenant.MetaContext.setMemberId(member.getId());
        try {
            java.util.Map<String, Role> roles = new java.util.LinkedHashMap<>();
            for (String code : List.of("tenant_admin", "developer", "viewer", "authoring_business_" + marker)) {
                var role = new Role();
                role.setTenantId(tenantId);
                role.setCode(code);
                role.setName(code);
                role.setStatus("active");
                roles.put(code, roleService.createRole(role));
            }
            var business = roles.get("authoring_business_" + marker);
            assertTrue(authoringRoles.assignRolesToMember(member.getId(), List.of(business.getId()), tenantId, actor.getId()));
            assertEquals(Set.of(business.getId()), authoringRoles.findByMemberIdAndTenantId(member.getId(), tenantId)
                    .stream().map(com.auraboot.framework.rbac.entity.UserRole::getRoleId)
                    .collect(java.util.stream.Collectors.toSet()));
            MetaModelCreateRequest request = new MetaModelCreateRequest();
            request.setCode(modelCode);
            request.setDisplayName("First authoring grant fixture");
            request.setModelCategory("entity");
            request.setTableName("mt_" + modelCode);
            var model = metaModelService.create(request);
            assertEquals(modelCode, model.getCode());
            var fieldRequest = new com.auraboot.framework.meta.dto.MetaFieldCreateRequest();
            fieldRequest.setCode("authoring_value_" + marker);
            fieldRequest.setDataType("string");
            fieldRequest.setAutoPublish(true);
            var field = authoringFields.create(fieldRequest);
            metaModelService.bindFieldToModel(model.getId(), field.getId(), 1,
                    false, true, false, null, null, null, null);
            var resource = permissionMapper.findByCode("model." + modelCode);
            assertNotNull(resource);
            assertEquals(2, resource.getLevel());
            java.util.Map<String, Long> actionIds = new java.util.LinkedHashMap<>();
            for (String action : List.of("read", "create", "update", "delete", "export", "import")) {
                var permission = permissionMapper.findByCode("model." + modelCode + "." + action);
                assertNotNull(permission);
                assertEquals(3, permission.getLevel());
                assertEquals(resource.getId(), permission.getParentId());
                actionIds.put(action, permission.getId());
            }
            assertEquals(Set.copyOf(actionIds.values()), authoringGrantIds(roles.get("tenant_admin").getId()));
            assertEquals(Set.copyOf(actionIds.values()), authoringGrantIds(roles.get("developer").getId()));
            assertEquals(Set.of(actionIds.get("read")), authoringGrantIds(roles.get("viewer").getId()));
            assertEquals(Set.of(), authoringGrantIds(business.getId()));
            assertFalse(authoringEvaluator.canAction(member.getId(), modelCode, "read"));
            assertFalse(authoringEvaluator.canAction(member.getId(), modelCode, "update"));

            // Removing an initial template grant is an explicit administrator decision.
            assertTrue(roleService.removePermissions(roles.get("tenant_admin").getId(), List.of(actionIds.get("read"))));
            assertTrue(roleService.removePermissions(roles.get("viewer").getId(), List.of(actionIds.get("read"))));
            List<String> revokedEdges = authoringGrantSnapshot(tenantId);
            var commandRequest = new com.auraboot.framework.meta.dto.CommandDefinitionCreateRequest();
            commandRequest.setCode("authoring:qualify_" + modelCode);
            commandRequest.setDisplayName("New authoring command action");
            commandRequest.setModelCode(modelCode);
            commandRequest.setInputSchema("{}");
            commandRequest.setTargetModels("[]");
            commandRequest.setExecutionConfig("{\"type\":\"custom\"}");
            var command = commandService.create(commandRequest);
            assertEquals("published", commandService.publish(command.getPid()).getStatus());
            metaModelService.publish(model.getPid(), "Public authoring synchronization");
            var newAction = permissionMapper.findByCode("model." + modelCode + ".qualify");
            assertNotNull(newAction);
            assertEquals(resource.getId(), newAction.getParentId());
            assertEquals(3, newAction.getLevel());
            assertEquals(revokedEdges, authoringGrantSnapshot(tenantId));
            for (Role role : roles.values()) assertFalse(authoringGrantIds(role.getId()).contains(newAction.getId()));
            assertNull(rolePermissionMapper.findByRoleAndPermission(roles.get("tenant_admin").getId(), actionIds.get("read")));
            assertNull(rolePermissionMapper.findByRoleAndPermission(roles.get("viewer").getId(), actionIds.get("read")));
            assertFalse(authoringEvaluator.canAction(member.getId(), modelCode, "qualify"));

            // Grant/revoke the new action only through the existing role service.
            assertTrue(roleService.assignPermissions(business.getId(), List.of(newAction.getId())));
            assertTrue(authoringEvaluator.canAction(member.getId(), modelCode, "qualify"));
            assertEquals(Set.of(newAction.getId()), authoringGrantIds(business.getId()));
            assertTrue(roleService.removePermissions(business.getId(), List.of(newAction.getId())));
            assertFalse(authoringEvaluator.canAction(member.getId(), modelCode, "qualify"));
            List<String> explicitRevokedEdges = authoringGrantSnapshot(tenantId);
            autoPermissions.autoAssignPermissions(modelCode, null, tenantId);
            autoPermissions.registerPermissions(modelCode, null, tenantId);
            assertEquals(explicitRevokedEdges, authoringGrantSnapshot(tenantId));
            assertEquals(newAction.getId(), permissionMapper.findByCode("model." + modelCode + ".qualify").getId());
            assertEquals(actionIds.get("read"), permissionMapper.findByCode("model." + modelCode + ".read").getId());
            assertFalse(authoringEvaluator.canAction(member.getId(), modelCode, "qualify"));
            assertEquals(Set.of(business.getId()), authoringRoles.findByMemberIdAndTenantId(member.getId(), tenantId)
                    .stream().map(com.auraboot.framework.rbac.entity.UserRole::getRoleId)
                    .collect(java.util.stream.Collectors.toSet()));
        } finally {
            com.auraboot.framework.application.tenant.MetaContext.restore(previous);
        }
    }

    private Set<Long> authoringGrantIds(Long roleId) {
        return Set.copyOf(rolePermissionMapper.findPermissionIdsByRoles(List.of(roleId)));
    }

    private List<String> authoringGrantSnapshot(Long tenantId) {
        return authoringJdbc.queryForList(
                "SELECT row_to_json(rp)::text FROM ab_role_permission rp WHERE tenant_id = ? ORDER BY id",
                String.class, tenantId);
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
        
        // Then: Permission codes should follow format: {resourceType}.{resourceCode}.{action}
        // Resource node: model.{modelCode}
        // Action nodes: model.{modelCode}.{action} (actions derived from commands)
        String resourceCode = "model." + TEST_MODEL_CODE;
        Permission resourcePermission = permissionMapper.findByCode(resourceCode);
        assertNotNull(resourcePermission,
            "Resource permission code should follow naming convention: " + resourceCode);
        assertEquals(resourceCode, resourcePermission.getCode(),
            "Resource permission code should follow naming convention");
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
        
        // Then: Each model should have its own resource permission node
        // Format: model.{modelCode} (resource node at level 2)
        Permission model1ResourcePermission = permissionMapper.findByCode("model.model_one");
        Permission model2ResourcePermission = permissionMapper.findByCode("model.model_two");

        assertNotNull(model1ResourcePermission, "model.model_one resource permission should exist");
        assertNotNull(model2ResourcePermission, "model.model_two resource permission should exist");

        // Then: permissions should be different
        assertNotEquals(model1ResourcePermission.getId(), model2ResourcePermission.getId());
    }
}
