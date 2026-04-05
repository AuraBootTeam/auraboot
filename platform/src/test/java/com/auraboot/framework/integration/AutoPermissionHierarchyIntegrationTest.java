package com.auraboot.framework.integration;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.mapper.CommandDefinitionMapper;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.AutoPermissionAssignmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for hierarchical fine-grained permission generation.
 *
 * Verifies:
 * - 3-level hierarchy: Module (level=1) → Resource (level=2) → Action (level=3)
 * - Actions derived from CommandActionDeriver (not hardcoded)
 * - parent_id chain integrity
 * - Idempotency (calling twice doesn't create duplicates)
 * - moduleCode derivation from modelCode prefix
 * - No "manage" action at level=3 (unless a command produces it)
 */
class AutoPermissionHierarchyIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AutoPermissionAssignmentService autoPermissionAssignmentService;

    @Autowired
    private PermissionMapper permissionMapper;

    @Autowired
    private CommandDefinitionMapper commandDefinitionMapper;

    private String testModelCode;
    private String testModuleCode;

    @BeforeEach
    void setupCommands() {
        testModuleCode = "tmod";
        testModelCode = "tmod_perm_" + System.currentTimeMillis();

        // Insert CRUD + state transition commands
        insertCommand("tmod:create_" + testModelCode, testModelCode, "{\"type\": \"create\"}");
        insertCommand("tmod:update_" + testModelCode, testModelCode, "{\"type\": \"update\"}");
        insertCommand("tmod:delete_" + testModelCode, testModelCode, "{\"type\": \"delete\"}");
        insertCommand("tmod:list_" + testModelCode, testModelCode, "{\"type\": \"query\"}");
        insertCommand("tmod:qualify_" + testModelCode, testModelCode,
                "{\"type\": \"state_transition\", \"fromStates\": [\"new\"], \"toState\": \"qualified\"}");
    }

    @Test
    void autoAssign_createsModuleAtLevel1() {
        autoPermissionAssignmentService.autoAssignPermissions(testModelCode, testModuleCode);

        Permission module = permissionMapper.findByCode("module." + testModuleCode + ".manage");
        assertThat(module).isNotNull();
        assertThat(module.getLevel()).isEqualTo(1);
        assertThat(module.getParentId()).isNull();
        assertThat(module.getResourceType()).isEqualTo("module");
        assertThat(module.getResourceCode()).isEqualTo(testModuleCode);
        assertThat(module.getSourceRef()).isEqualTo(testModuleCode);
        assertThat(module.getSource()).isEqualTo("generated");
    }

    @Test
    void autoAssign_createsResourceAtLevel2_withParentPointingToModule() {
        autoPermissionAssignmentService.autoAssignPermissions(testModelCode, testModuleCode);

        Permission module = permissionMapper.findByCode("module." + testModuleCode + ".manage");
        Permission resource = permissionMapper.findByCode("model." + testModelCode + ".manage");

        assertThat(resource).isNotNull();
        assertThat(resource.getLevel()).isEqualTo(2);
        assertThat(resource.getParentId()).isEqualTo(module.getId());
        assertThat(resource.getResourceType()).isEqualTo("model");
        assertThat(resource.getResourceCode()).isEqualTo(testModelCode);
        assertThat(resource.getSourceRef()).isEqualTo(testModuleCode);
    }

    @Test
    void autoAssign_createsActionNodesAtLevel3_withDerivedActions() {
        autoPermissionAssignmentService.autoAssignPermissions(testModelCode, testModuleCode);

        Permission resource = permissionMapper.findByCode("model." + testModelCode + ".manage");

        // Verify action permissions exist with correct hierarchy
        for (String action : List.of("read", "create", "update", "delete", "qualify")) {
            Permission actionPerm = permissionMapper.findByCode("model." + testModelCode + "." + action);
            assertThat(actionPerm)
                    .as("Action permission for '%s' should exist", action)
                    .isNotNull();
            assertThat(actionPerm.getLevel()).isEqualTo(3);
            assertThat(actionPerm.getParentId()).isEqualTo(resource.getId());
            assertThat(actionPerm.getResourceType()).isEqualTo("model");
            assertThat(actionPerm.getAction()).isEqualTo(action);
            assertThat(actionPerm.getSourceRef()).isEqualTo(testModuleCode);
        }
    }

    @Test
    void autoAssign_noManageActionAtLevel3() {
        autoPermissionAssignmentService.autoAssignPermissions(testModelCode, testModuleCode);

        // "manage" exists at level=2 (resource node), but should NOT exist as a separate level=3 action
        // unless a command explicitly produces "manage" as a verb
        Permission resourceManage = permissionMapper.findByCode("model." + testModelCode + ".manage");
        assertThat(resourceManage).isNotNull();
        assertThat(resourceManage.getLevel()).isEqualTo(2);
        // No separate "manage" action at level=3 since no command produces "manage"
    }

    @Test
    void autoAssign_idempotent_noDuplicatesOnSecondCall() {
        autoPermissionAssignmentService.autoAssignPermissions(testModelCode, testModuleCode);

        // Count permissions after first call
        Permission module1 = permissionMapper.findByCode("module." + testModuleCode + ".manage");
        Permission resource1 = permissionMapper.findByCode("model." + testModelCode + ".manage");
        Permission read1 = permissionMapper.findByCode("model." + testModelCode + ".read");

        assertThat(module1).isNotNull();
        assertThat(resource1).isNotNull();
        assertThat(read1).isNotNull();

        Long moduleId1 = module1.getId();
        Long resourceId1 = resource1.getId();
        Long readId1 = read1.getId();

        // Call again — should be idempotent
        autoPermissionAssignmentService.autoAssignPermissions(testModelCode, testModuleCode);

        Permission module2 = permissionMapper.findByCode("module." + testModuleCode + ".manage");
        Permission resource2 = permissionMapper.findByCode("model." + testModelCode + ".manage");
        Permission read2 = permissionMapper.findByCode("model." + testModelCode + ".read");

        // Same IDs = no duplicates
        assertThat(module2.getId()).isEqualTo(moduleId1);
        assertThat(resource2.getId()).isEqualTo(resourceId1);
        assertThat(read2.getId()).isEqualTo(readId1);
    }

    @Test
    void autoAssign_derivesModuleFromModelCode_whenModuleCodeIsNull() {
        autoPermissionAssignmentService.autoAssignPermissions(testModelCode, null);

        // testModelCode starts with "tmod_" so module should be "tmod"
        Permission module = permissionMapper.findByCode("module.tmod.manage");
        assertThat(module).isNotNull();
        assertThat(module.getResourceCode()).isEqualTo("tmod");
    }

    @Test
    void autoAssign_derivesModuleCorrectly_forVariousPatterns() {
        // Model with underscore: "crm_test_xxx" → module "crm"
        String modelWithPrefix = "crm_test_" + System.currentTimeMillis();
        autoPermissionAssignmentService.autoAssignPermissions(modelWithPrefix, null);
        Permission crmModule = permissionMapper.findByCode("module.crm.manage");
        assertThat(crmModule).isNotNull();
        assertThat(crmModule.getResourceCode()).isEqualTo("crm");

        // Model without underscore: use modelCode itself as module
        String standaloneModel = "standalone_t" + System.currentTimeMillis();
        // Note: "standalone_t..." has underscore so module = "standalone"
        // For truly no-underscore: use a simple code
        String noUnderscoreModel = "noprefix";
        autoPermissionAssignmentService.autoAssignPermissions(noUnderscoreModel, null);
        Permission noUnderscoreModule = permissionMapper.findByCode("module.noprefix.manage");
        assertThat(noUnderscoreModule).isNotNull();
        assertThat(noUnderscoreModule.getResourceCode()).isEqualTo("noprefix");
    }

    @Test
    void autoAssign_invalidModelCode_skipsWithoutError() {
        // Should not throw, just warn and skip
        autoPermissionAssignmentService.autoAssignPermissions("Invalid-Code!", null);
        autoPermissionAssignmentService.autoAssignPermissions("", null);
        autoPermissionAssignmentService.autoAssignPermissions(null, null);
        // No exception = success
    }

    @Test
    void autoAssign_multipleModelsShareModuleNode() {
        String modelCode2 = "tmod_other_" + System.currentTimeMillis();
        insertCommand("tmod:create_" + modelCode2, modelCode2, "{\"type\": \"create\"}");

        autoPermissionAssignmentService.autoAssignPermissions(testModelCode, testModuleCode);
        autoPermissionAssignmentService.autoAssignPermissions(modelCode2, testModuleCode);

        // Both models share the same module node
        Permission module = permissionMapper.findByCode("module." + testModuleCode + ".manage");
        Permission resource1 = permissionMapper.findByCode("model." + testModelCode + ".manage");
        Permission resource2 = permissionMapper.findByCode("model." + modelCode2 + ".manage");

        assertThat(resource1.getParentId()).isEqualTo(module.getId());
        assertThat(resource2.getParentId()).isEqualTo(module.getId());
    }

    private void insertCommand(String code, String modelCode, String executionConfig) {
        CommandDefinition cmd = new CommandDefinition();
        cmd.setPid(UniqueIdGenerator.generate());
        cmd.setTenantId(getTestTenant().getId());
        cmd.setCode(code);
        cmd.setDisplayName(code);
        cmd.setModelCode(modelCode);
        cmd.setInputSchema("{}");
        cmd.setTargetModels("[]");
        cmd.setExecutionConfig(executionConfig);
        cmd.setExtension(new ExtensionBean());
        cmd.setVersion(1);
        cmd.setSemver("1.0.0");
        cmd.setIsCurrent(true);
        cmd.setRowVersion(1);
        cmd.setStatus("published");
        cmd.setDeletedFlag(false);
        cmd.setCreatedAt(Instant.now());
        cmd.setUpdatedAt(Instant.now());
        commandDefinitionMapper.insertIdempotent(cmd);
    }
}
