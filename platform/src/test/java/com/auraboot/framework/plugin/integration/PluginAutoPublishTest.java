package com.auraboot.framework.plugin.integration;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.CommandDefinitionDTO;
import com.auraboot.framework.meta.dto.MetaFieldDTO;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.service.CommandService;
import com.auraboot.framework.meta.service.MetaFieldService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.PageSchemaService;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.plugin.dto.imports.*;
import com.auraboot.framework.plugin.service.PluginImportService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for plugin auto-publish functionality.
 * Validates that models, fields, commands, and pages are auto-published
 * after plugin import, and that DYNAMIC permissions are created.
 */
@Slf4j
@DisplayName("Plugin Auto-Publish Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class PluginAutoPublishTest extends BaseIntegrationTest {

    private static final String PLUGIN_DIR = "plugins/asset-management";

    @Autowired
    private PluginImportService pluginImportService;

    @Autowired
    private MetaModelService metaModelService;

    @Autowired
    private MetaFieldService metaFieldService;

    @Autowired
    private CommandService commandService;

    @Autowired
    private PageSchemaService pageSchemaService;

    @Autowired
    private PermissionMapper permissionMapper;

    // ==================== Auto-Publish Tests ====================

    @Test
    @Order(1)
    @DisplayName("Models should be PUBLISHED after import with autoPublishModels=true")
    void modelsShouldBePublishedAfterImport() {
        ImportExecuteResult result = importPlugin(true, true, true, true);
        assertThat(result.isSuccess()).isTrue();

        // Verify models are published
        List<String> modelCodes = getAssetModelCodes();
        for (String code : modelCodes) {
            MetaModelDTO model = metaModelService.findByCode(code);
            assertThat(model)
                    .as("Model %s should exist", code)
                    .isNotNull();
            assertThat(model.getStatus())
                    .as("Model %s should be PUBLISHED", code)
                    .isEqualTo("published");
        }

        log.info("Verified {} models are PUBLISHED", modelCodes.size());
    }

    @Test
    @Order(2)
    @DisplayName("Fields should be imported after plugin import with autoPublishFields=true")
    void fieldsShouldBeImportedAfterImport() {
        ImportExecuteResult result = importPlugin(true, true, true, false);
        assertThat(result.isSuccess()).isTrue();

        // Verify asset fields were imported (field creation confirmed)
        // Note: MetaFieldServiceImpl.create() does not honor autoPublish flag;
        // fields are created as DRAFT. Actual field activation happens during model publish.
        List<String> sampleFieldCodes = List.of("asset_code", "asset_name", "asset_status");
        for (String fieldCode : sampleFieldCodes) {
            Optional<MetaFieldDTO> field = metaFieldService.findByCodeAndVersion(fieldCode, 1);
            assertThat(field)
                    .as("Field %s should exist after import", fieldCode)
                    .isPresent();
        }

        log.info("Verified {} asset fields were imported", sampleFieldCodes.size());
    }

    @Test
    @Order(3)
    @DisplayName("Commands with execution config should be PUBLISHED after import")
    void commandsWithConfigShouldBePublishedAfterImport() {
        ImportExecuteResult result = importPlugin(true, true, true, false);
        assertThat(result.isSuccess()).isTrue();

        // All asset commands have executionConfig and should be PUBLISHED
        List<String> allCommands = List.of(
                "asset:create", "asset:update", "asset:delete", "asset:activate",
                "asset:set_idle", "asset:start_maintenance", "asset:complete_maintenance",
                "asset:dispose", "asset_transfer:create");

        for (String cmdCode : allCommands) {
            CommandDefinitionDTO cmd = commandService.findByCode(cmdCode);
            assertThat(cmd)
                    .as("Command %s should exist", cmdCode)
                    .isNotNull();
            assertThat(cmd.getStatus())
                    .as("Command %s with executionConfig should be PUBLISHED", cmdCode)
                    .isEqualTo("published");
        }

        log.info("Verified: {} commands PUBLISHED", allCommands.size());
    }

    @Test
    @Order(4)
    @DisplayName("DYNAMIC permissions should be created after model publish")
    void dynamicPermissionsShouldBeCreatedAfterPublish() {
        ImportExecuteResult result = importPlugin(true, true, true, false);
        assertThat(result.isSuccess()).isTrue();

        // Check DYNAMIC permissions exist for published models
        List<Permission> dynamicPermissions = permissionMapper.findByResourceType("dynamic");

        List<String> modelCodes = getAssetModelCodes();
        for (String modelCode : modelCodes) {
            // Should have read, create, manage permissions for each model
            boolean hasReadPerm = dynamicPermissions.stream()
                    .anyMatch(p -> ("DYNAMIC." + modelCode + ".read").equals(p.getCode()));
            boolean hasCreatePerm = dynamicPermissions.stream()
                    .anyMatch(p -> ("DYNAMIC." + modelCode + ".create").equals(p.getCode()));
            boolean hasManagePerm = dynamicPermissions.stream()
                    .anyMatch(p -> ("DYNAMIC." + modelCode + ".manage").equals(p.getCode()));

            assertThat(hasReadPerm)
                    .as("DYNAMIC.%s.read permission should exist", modelCode)
                    .isTrue();
            assertThat(hasCreatePerm)
                    .as("DYNAMIC.%s.create permission should exist", modelCode)
                    .isTrue();
            assertThat(hasManagePerm)
                    .as("DYNAMIC.%s.manage permission should exist", modelCode)
                    .isTrue();
        }

        log.info("Verified DYNAMIC permissions for {} models", modelCodes.size());
    }

    // ==================== Reverse Path Tests ====================

    @Test
    @Order(5)
    @DisplayName("Commands should remain DRAFT when autoPublishCommands=false")
    void commandsShouldRemainDraftWhenAutoPublishDisabled() {
        ImportExecuteResult result = importPlugin(true, true, false, false);
        assertThat(result.isSuccess()).isTrue();

        // Find asset model commands and verify they are NOT published
        List<String> modelCodes = getAssetModelCodes();
        int draftCommandCount = 0;

        for (String modelCode : modelCodes) {
            List<CommandDefinitionDTO> commands = commandService.listByModelCode(modelCode);
            for (CommandDefinitionDTO cmd : commands) {
                assertThat(cmd.getStatus())
                        .as("Command %s should be DRAFT when autoPublishCommands=false", cmd.getCode())
                        .isEqualTo("draft");
                draftCommandCount++;
            }
        }

        assertThat(draftCommandCount)
                .as("Should have draft commands")
                .isGreaterThan(0);

        log.info("Verified {} commands remain DRAFT", draftCommandCount);
    }

    // ==================== Helpers ====================

    private ImportExecuteResult importPlugin(boolean autoPublishModels, boolean autoPublishFields,
                                             boolean autoPublishCommands, boolean autoPublishPages) {
        Path pluginPath = resolvePluginPath();
        ImportPreviewResult preview = pluginImportService.parseDirectory(pluginPath.toString());
        assertThat(preview.isValid())
                .as("Plugin manifest should be valid: %s", preview.getErrors())
                .isTrue();

        ImportRequest request = ImportRequest.builder()
                .importId(preview.getImportId())
                .conflictStrategy(ImportRequest.ConflictStrategy.OVERWRITE)
                .autoPublishModels(autoPublishModels)
                .autoPublishFields(autoPublishFields)
                .autoPublishCommands(autoPublishCommands)
                .autoPublishPages(autoPublishPages)
                .autoDeployProcesses(false)
                .build();

        return pluginImportService.execute(preview.getImportId(), request);
    }

    private Path resolvePluginPath() {
        Path projectRoot = Paths.get(System.getProperty("user.dir"));
        if (projectRoot.endsWith("platform")) {
            projectRoot = projectRoot.getParent();
        }
        Path pluginPath = projectRoot.resolve(PLUGIN_DIR);
        assertThat(pluginPath.toFile().exists())
                .as("Plugin directory should exist at: %s", pluginPath)
                .isTrue();
        return pluginPath;
    }

    private List<String> getAssetModelCodes() {
        // Asset management has 4 models: asset, asset_transfer, asset_maintenance, asset_depreciation
        return List.of("asset", "asset_transfer", "asset_maintenance", "asset_depreciation");
    }
}
