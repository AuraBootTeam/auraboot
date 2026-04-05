package com.auraboot.framework.integration;

import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.enums.RolePermissionTemplate;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.SystemPermissionInitializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration test for system permission hierarchy (Module → Resource → Action).
 *
 * Verifies:
 * - Module nodes exist at level=1
 * - Resource nodes at level=2 with parent_id → module
 * - Action nodes at level=3 with parent_id → resource
 * - No standalone "manage" actions (replaced by create/read/update/delete)
 * - Special actions preserved (execute, admin, generate)
 * - RolePermissionTemplate filter-based assignment works correctly
 */
class SystemPermissionHierarchyIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private SystemPermissionInitializer systemPermissionInitializer;

    @Autowired
    private PermissionMapper permissionMapper;

    private List<Permission> createdPermissions;

    @BeforeEach
    void initPermissions() {
        createdPermissions = systemPermissionInitializer.initializeSystemPermissions(getTestTenant().getId());
    }

    @Test
    @Order(1)
    void moduleNodesExistAtLevel1() {
        List<Permission> modules = createdPermissions.stream()
            .filter(p -> p.getLevel() != null && p.getLevel() == 1)
            .toList();

        assertThat(modules).isNotEmpty();

        Set<String> moduleCodes = modules.stream()
            .map(Permission::getCode)
            .collect(Collectors.toSet());

        // All 6 module groups must exist
        assertThat(moduleCodes).containsExactlyInAnyOrder(
            "module.platform",
            "module.rbac",
            "module.tenant",
            "module.devops",
            "module.automation",
            "module.analytics"
        );

        // Module nodes have no action
        for (Permission module : modules) {
            assertThat(module.getAction()).isNull();
            assertThat(module.getParentId()).isNull();
            assertThat(module.getSource()).isEqualTo("system");
            assertThat(module.getResourceType()).isEqualTo("module");
        }
    }

    @Test
    @Order(2)
    void resourceNodesExistAtLevel2WithModuleParent() {
        List<Permission> resources = createdPermissions.stream()
            .filter(p -> p.getLevel() != null && p.getLevel() == 2)
            .toList();

        assertThat(resources).isNotEmpty();

        // Each resource node must have a parent that is a module (level=1)
        Map<Long, Permission> byId = createdPermissions.stream()
            .collect(Collectors.toMap(Permission::getId, p -> p, (a, b) -> a));

        for (Permission resource : resources) {
            assertThat(resource.getParentId()).isNotNull();
            Permission parent = byId.get(resource.getParentId());
            assertThat(parent)
                .as("Resource %s should have a valid module parent", resource.getCode())
                .isNotNull();
            assertThat(parent.getLevel()).isEqualTo(1);
            assertThat(resource.getAction()).isNull();
            assertThat(resource.getSource()).isEqualTo("system");
        }

        // Verify some specific resources exist
        Set<String> resourceCodes = resources.stream()
            .map(Permission::getCode)
            .collect(Collectors.toSet());

        assertThat(resourceCodes).contains(
            "system.model",
            "system.page",
            "system.field",
            "system.rbac_role",
            "system.rbac_user_role",
            "system.permission",
            "system.tenant",
            "system.menu",
            "system.git_repo",
            "system.git_release",
            "system.automation",
            "system.workflow_process",
            "system.report_template",
            "system.dashboard"
        );
    }

    @Test
    @Order(3)
    void actionNodesExistAtLevel3WithResourceParent() {
        List<Permission> actions = createdPermissions.stream()
            .filter(p -> p.getLevel() != null && p.getLevel() == 3)
            .toList();

        assertThat(actions).isNotEmpty();

        Map<Long, Permission> byId = createdPermissions.stream()
            .collect(Collectors.toMap(Permission::getId, p -> p, (a, b) -> a));

        for (Permission action : actions) {
            assertThat(action.getParentId()).isNotNull();
            Permission parent = byId.get(action.getParentId());
            assertThat(parent)
                .as("Action %s should have a valid resource parent", action.getCode())
                .isNotNull();
            assertThat(parent.getLevel()).isEqualTo(2);
            assertThat(action.getAction()).isNotNull();
            assertThat(action.getSource()).isEqualTo("system");
        }
    }

    @Test
    @Order(4)
    void noManageActionsExist() {
        // "manage" has been replaced by create/read/update/delete
        List<Permission> manageActions = createdPermissions.stream()
            .filter(p -> "manage".equals(p.getAction()))
            .toList();

        assertThat(manageActions)
            .as("No 'manage' action should exist — replaced by CRUD actions")
            .isEmpty();
    }

    @Test
    @Order(5)
    void specialActionsPreserved() {
        Set<String> allActions = createdPermissions.stream()
            .map(Permission::getAction)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());

        // Special actions must be preserved
        assertThat(allActions).contains("execute", "admin", "generate");
        // CRUD actions must exist
        assertThat(allActions).contains("create", "read", "update", "delete");
    }

    @Test
    @Order(6)
    void commandHasExecuteAction() {
        List<Permission> commandActions = createdPermissions.stream()
            .filter(p -> p.getLevel() != null && p.getLevel() == 3)
            .filter(p -> p.getCode().startsWith("system.command."))
            .toList();

        Set<String> commandActionNames = commandActions.stream()
            .map(Permission::getAction)
            .collect(Collectors.toSet());

        assertThat(commandActionNames).contains("execute", "create", "read", "update", "delete");
    }

    @Test
    @Order(7)
    void templateGenerateActionExists() {
        List<Permission> templateActions = createdPermissions.stream()
            .filter(p -> p.getLevel() != null && p.getLevel() == 3)
            .filter(p -> p.getCode().startsWith("system.template."))
            .toList();

        assertThat(templateActions).hasSize(1);
        assertThat(templateActions.get(0).getAction()).isEqualTo("generate");
    }

    @Test
    @Order(8)
    void eventStoreHasOnlyReadAndAdmin() {
        List<Permission> esActions = createdPermissions.stream()
            .filter(p -> p.getLevel() != null && p.getLevel() == 3)
            .filter(p -> p.getCode().startsWith("system.event_store."))
            .toList();

        Set<String> esActionNames = esActions.stream()
            .map(Permission::getAction)
            .collect(Collectors.toSet());

        assertThat(esActionNames).containsExactlyInAnyOrder("read", "admin");
    }

    @Test
    @Order(9)
    void idempotentRerun() {
        // Running again should not create duplicates
        List<Permission> secondRun = systemPermissionInitializer.initializeSystemPermissions(getTestTenant().getId());

        // Same total count (all existing permissions are returned even if skipped)
        assertThat(secondRun).hasSameSizeAs(createdPermissions);
    }

    @Test
    @Order(10)
    void totalPermissionCountIsReasonable() {
        // 6 modules + ~26 resources + ~100 actions = ~130+ total
        long moduleCount = createdPermissions.stream()
            .filter(p -> p.getLevel() != null && p.getLevel() == 1).count();
        long resourceCount = createdPermissions.stream()
            .filter(p -> p.getLevel() != null && p.getLevel() == 2).count();
        long actionCount = createdPermissions.stream()
            .filter(p -> p.getLevel() != null && p.getLevel() == 3).count();

        assertThat(moduleCount).isEqualTo(6);
        assertThat(resourceCount).isGreaterThanOrEqualTo(20);
        assertThat(actionCount).isGreaterThanOrEqualTo(70);
        assertThat(createdPermissions.size()).isEqualTo(moduleCount + resourceCount + actionCount);
    }

    // ========================================================================
    // RolePermissionTemplate filter tests
    // ========================================================================

    @Test
    @Order(11)
    void tenantAdminGetsAllPermissions() {
        RolePermissionTemplate admin = RolePermissionTemplate.TENANT_ADMIN;
        long assigned = createdPermissions.stream()
            .filter(admin::shouldAssign)
            .count();

        assertThat(assigned).isEqualTo(createdPermissions.size());
    }

    @Test
    @Order(12)
    void viewerGetsReadActionsAndNonLeafNodes() {
        RolePermissionTemplate viewer = RolePermissionTemplate.VIEWER;

        List<Permission> viewerPermissions = createdPermissions.stream()
            .filter(viewer::shouldAssign)
            .toList();

        // Viewer gets all modules (level=1) and resources (level=2)
        long modules = viewerPermissions.stream()
            .filter(p -> p.getLevel() != null && p.getLevel() == 1).count();
        long resources = viewerPermissions.stream()
            .filter(p -> p.getLevel() != null && p.getLevel() == 2).count();

        assertThat(modules).isEqualTo(6);
        assertThat(resources).isGreaterThanOrEqualTo(20);

        // Viewer's action nodes should only be "read"
        List<Permission> viewerActions = viewerPermissions.stream()
            .filter(p -> p.getLevel() != null && p.getLevel() == 3)
            .toList();

        for (Permission action : viewerActions) {
            assertThat(action.getAction())
                .as("Viewer should only get 'read' actions, found: %s", action.getCode())
                .isEqualTo("read");
        }

        // Viewer should NOT get non-read actions
        long totalActions = createdPermissions.stream()
            .filter(p -> p.getLevel() != null && p.getLevel() == 3).count();
        assertThat(viewerActions.size()).isLessThan((int) totalActions);
    }

    @Test
    @Order(13)
    void viewerDoesNotGetExecuteOrAdminActions() {
        RolePermissionTemplate viewer = RolePermissionTemplate.VIEWER;

        List<Permission> viewerActions = createdPermissions.stream()
            .filter(viewer::shouldAssign)
            .filter(p -> p.getLevel() != null && p.getLevel() == 3)
            .toList();

        Set<String> viewerActionNames = viewerActions.stream()
            .map(Permission::getAction)
            .collect(Collectors.toSet());

        assertThat(viewerActionNames).doesNotContain("execute", "admin", "create", "update", "delete", "generate");
    }

    @Test
    @Order(14)
    void findByRoleCodeWorks() {
        assertThat(RolePermissionTemplate.findByRoleCode("tenant_admin")).isEqualTo(RolePermissionTemplate.TENANT_ADMIN);
        assertThat(RolePermissionTemplate.findByRoleCode("developer")).isEqualTo(RolePermissionTemplate.DEVELOPER);
        assertThat(RolePermissionTemplate.findByRoleCode("viewer")).isEqualTo(RolePermissionTemplate.VIEWER);
        assertThat(RolePermissionTemplate.findByRoleCode("nonexistent")).isNull();
        assertThat(RolePermissionTemplate.findByRoleCode(null)).isNull();
    }
}
