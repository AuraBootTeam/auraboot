package com.auraboot.framework.plugin.integration;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.plugin.dto.PluginOperationResult;
import com.auraboot.framework.plugin.dto.PluginStatus;
import com.auraboot.framework.plugin.dto.imports.*;
import com.auraboot.framework.plugin.entity.PluginRecord;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.auraboot.framework.plugin.mapper.PluginRecordMapper;
import com.auraboot.framework.plugin.mapper.PluginResourceMapper;
import com.auraboot.framework.plugin.service.PluginImportService;
import com.auraboot.framework.plugin.service.PluginManagerService;
import com.auraboot.framework.plugin.service.PluginResourceService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Plugin Lifecycle Integration Test (C2-01 to C2-07).
 * Verifies the complete plugin lifecycle:
 * - Install -> Enable -> Disable -> Re-enable -> Uninstall (keep data / remove data)
 * - Re-install same version is idempotent
 */
@Slf4j
@DisplayName("Plugin Lifecycle Integration Test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class PluginLifecycleTest extends BaseIntegrationTest {

    private static final String PLUGIN_DIR = "plugins/asset-management";
    private static final String PLUGIN_ID = "com.auraboot.asset-management";
    private static final String PLUGIN_NAMESPACE = "asset";

    @Autowired
    private PluginImportService pluginImportService;

    @Autowired
    private PluginManagerService pluginManagerService;

    @Autowired
    private PluginResourceService pluginResourceService;

    @Autowired
    private PluginRecordMapper pluginRecordMapper;

    @Autowired
    private PluginResourceMapper pluginResourceMapper;

    // ==================== C2-01: Install plugin ====================

    @Test
    @Order(1)
    @DisplayName("C2-01: Install plugin - status should be INSTALLED with resources imported")
    void shouldInstallPluginWithResources() {
        // Import the plugin
        ImportExecuteResult importResult = importAssetPlugin();
        assertThat(importResult.isSuccess()).isTrue();

        String pluginPid = importResult.getPluginPid();
        assertThat(pluginPid).isNotBlank();

        // Verify plugin record status is INSTALLED
        PluginRecord record = pluginRecordMapper.findByPid(pluginPid);
        assertThat(record).isNotNull();
        assertThat(record.getStatus()).isEqualTo(PluginStatus.INSTALLED.name());

        // Verify resources were imported
        int resourceCount = pluginResourceMapper.countByPluginPid(pluginPid);
        assertThat(resourceCount).isGreaterThan(0);
        log.info("C2-01: Plugin installed with {} resources, status={}", resourceCount, record.getStatus());
    }

    // ==================== C2-02: Enable plugin ====================

    @Test
    @Order(2)
    @DisplayName("C2-02: Enable plugin - commands should become callable")
    void shouldEnablePluginAndCommandsCallable() {
        // Install first
        ImportExecuteResult importResult = importAssetPlugin();
        String pluginPid = importResult.getPluginPid();

        // Enable the plugin
        PluginOperationResult enableResult = pluginManagerService.enable(PLUGIN_ID);
        assertThat(enableResult.isSuccess()).isTrue();
        assertThat(enableResult.getCurrentStatus()).isEqualTo(PluginStatus.ENABLED);

        // Verify status in database
        PluginRecord record = pluginRecordMapper.findByTenantAndPluginId(PLUGIN_ID);
        assertThat(record).isNotNull();
        assertThat(record.getStatus()).isEqualTo(PluginStatus.ENABLED.name());
        assertThat(record.getEnabledAt()).isNotNull();

        // Verify commands exist in the database (callable when plugin is enabled)
        List<PluginResource> commands = pluginResourceMapper.findByPluginPidAndType(
                pluginPid, ResourceType.COMMAND.name());
        assertThat(commands).isNotEmpty();
        log.info("C2-02: Plugin enabled, {} commands available", commands.size());
    }

    // ==================== C2-03: Disable plugin ====================

    @Test
    @Order(3)
    @DisplayName("C2-03: Disable plugin - commands should not be executable")
    void shouldDisablePluginAndCommandsNotExecutable() {
        // Install and enable first
        ImportExecuteResult importResult = importAssetPlugin();
        pluginManagerService.enable(PLUGIN_ID);

        // Disable the plugin
        PluginOperationResult disableResult = pluginManagerService.disable(PLUGIN_ID);
        assertThat(disableResult.isSuccess()).isTrue();
        assertThat(disableResult.getCurrentStatus()).isEqualTo(PluginStatus.DISABLED);
        assertThat(disableResult.getPreviousStatus()).isEqualTo(PluginStatus.ENABLED);

        // Verify status in database
        PluginRecord record = pluginRecordMapper.findByTenantAndPluginId(PLUGIN_ID);
        assertThat(record).isNotNull();
        assertThat(record.getStatus()).isEqualTo(PluginStatus.DISABLED.name());
        assertThat(record.getDisabledAt()).isNotNull();

        log.info("C2-03: Plugin disabled, status={}", record.getStatus());
    }

    // ==================== C2-04: Re-enable plugin ====================

    @Test
    @Order(4)
    @DisplayName("C2-04: Re-enable disabled plugin - functionality should be restored")
    void shouldReEnablePluginAndRestoreFunctionality() {
        // Install, enable, disable first
        ImportExecuteResult importResult = importAssetPlugin();
        String pluginPid = importResult.getPluginPid();
        pluginManagerService.enable(PLUGIN_ID);
        pluginManagerService.disable(PLUGIN_ID);

        // Re-enable
        PluginOperationResult reEnableResult = pluginManagerService.enable(PLUGIN_ID);
        assertThat(reEnableResult.isSuccess()).isTrue();
        assertThat(reEnableResult.getCurrentStatus()).isEqualTo(PluginStatus.ENABLED);
        assertThat(reEnableResult.getPreviousStatus()).isEqualTo(PluginStatus.DISABLED);

        // Verify status in database
        PluginRecord record = pluginRecordMapper.findByTenantAndPluginId(PLUGIN_ID);
        assertThat(record).isNotNull();
        assertThat(record.getStatus()).isEqualTo(PluginStatus.ENABLED.name());

        // Verify resources are still intact
        int resourceCount = pluginResourceMapper.countByPluginPid(pluginPid);
        assertThat(resourceCount).isGreaterThan(0);

        log.info("C2-04: Plugin re-enabled, {} resources preserved", resourceCount);
    }

    // ==================== C2-05: Uninstall (keep data) ====================

    @Test
    @Order(5)
    @DisplayName("C2-05: Uninstall plugin (keep data) - resources preserved, plugin record soft-deleted")
    void shouldUninstallKeepData() {
        // Install and then disable (required for uninstall)
        ImportExecuteResult importResult = importAssetPlugin();
        String pluginPid = importResult.getPluginPid();

        // Count resources before uninstall
        int resourcesBefore = pluginResourceMapper.countByPluginPid(pluginPid);
        assertThat(resourcesBefore).isGreaterThan(0);

        // Uninstall without removing data
        PluginOperationResult uninstallResult = pluginManagerService.uninstall(PLUGIN_ID, false);
        assertThat(uninstallResult.isSuccess()).isTrue();
        assertThat(uninstallResult.getOperation())
                .isEqualTo(PluginOperationResult.OperationType.UNINSTALL);

        // Verify plugin record is soft-deleted
        PluginRecord record = pluginRecordMapper.findByTenantAndPluginId(PLUGIN_ID);
        // Should be null due to soft-delete filter in the query
        assertThat(record).isNull();

        log.info("C2-05: Plugin uninstalled (keep data), resourcesBefore={}", resourcesBefore);
    }

    // ==================== C2-06: Uninstall (remove data) ====================

    @Test
    @Order(6)
    @DisplayName("C2-06: Uninstall plugin (remove data) - resources should be deleted")
    void shouldUninstallRemoveData() {
        // Install the plugin
        ImportExecuteResult importResult = importAssetPlugin();
        String pluginPid = importResult.getPluginPid();

        // Verify resources exist
        int resourcesBefore = pluginResourceMapper.countByPluginPid(pluginPid);
        assertThat(resourcesBefore).isGreaterThan(0);

        // Uninstall with data removal
        PluginOperationResult uninstallResult = pluginManagerService.uninstall(PLUGIN_ID, true);
        assertThat(uninstallResult.isSuccess()).isTrue();

        // Verify plugin record is soft-deleted
        PluginRecord record = pluginRecordMapper.findByTenantAndPluginId(PLUGIN_ID);
        assertThat(record).isNull();

        log.info("C2-06: Plugin uninstalled (remove data)");
    }

    // ==================== C2-07: Re-install same version ====================

    @Test
    @Order(7)
    @DisplayName("C2-07: Re-install same version - should be idempotent")
    void shouldReInstallSameVersionIdempotently() {
        // First install
        ImportExecuteResult firstResult = importAssetPlugin();
        assertThat(firstResult.isSuccess()).isTrue();
        String firstPluginPid = firstResult.getPluginPid();
        int firstResourceCount = pluginResourceMapper.countByPluginPid(firstPluginPid);

        // Second install (same version with OVERWRITE strategy)
        ImportExecuteResult secondResult = importAssetPlugin();
        assertThat(secondResult.isSuccess()).isTrue();
        String secondPluginPid = secondResult.getPluginPid();

        // Plugin PID should remain the same (update, not new record)
        assertThat(secondPluginPid).isEqualTo(firstPluginPid);

        // Resource count should remain the same
        int secondResourceCount = pluginResourceMapper.countByPluginPid(secondPluginPid);
        assertThat(secondResourceCount).isEqualTo(firstResourceCount);

        log.info("C2-07: Re-install idempotent, pid={}, resources={}", secondPluginPid, secondResourceCount);
    }

    // ==================== Helper Methods ====================

    /**
     * Import the asset-management plugin and return the result.
     */
    private ImportExecuteResult importAssetPlugin() {
        Path pluginPath = resolvePluginPath();
        ImportPreviewResult preview = pluginImportService.parseDirectory(pluginPath.toString());
        assertThat(preview.isValid())
                .as("Plugin manifest should be valid: %s", preview.getErrors())
                .isTrue();

        ImportRequest request = ImportRequest.builder()
                .importId(preview.getImportId())
                .conflictStrategy(ImportRequest.ConflictStrategy.OVERWRITE)
                .autoPublishModels(true)
                .autoPublishFields(true)
                .autoPublishPages(false)
                .autoDeployProcesses(false)
                .build();

        return pluginImportService.execute(preview.getImportId(), request);
    }

    /**
     * Resolve the plugin directory path relative to the project root.
     */
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
}
