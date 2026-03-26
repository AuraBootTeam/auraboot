package com.auraboot.framework.i18n;

import com.auraboot.framework.i18n.compiler.I18nCompiler;
import com.auraboot.framework.i18n.entity.I18nResource;
import com.auraboot.framework.i18n.service.I18nResourceService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.plugin.dto.imports.*;
import com.auraboot.framework.plugin.service.PluginImportService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for i18n resource import via plugin system.
 * Tests that i18n.json in a plugin is correctly imported, upserted,
 * and triggers compilation.
 */
@Slf4j
@DisplayName("I18n Plugin Import Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class I18nPluginImportTest extends BaseIntegrationTest {

    private static final String PLUGIN_DIR = "plugins/e2e-test-order";
    private static final String PM_PLUGIN_DIR = "plugins/project-management";

    @Autowired
    private PluginImportService pluginImportService;

    @Autowired
    private I18nResourceService i18nResourceService;

    @Autowired
    private I18nCompiler i18nCompiler;

    // ==================== i18n Import Tests ====================

    @Test
    @Order(1)
    @DisplayName("Should create i18n records after importing plugin with i18n.json")
    void shouldCreateI18nRecordsAfterImport() {
        // Import dependency first (project-management)
        importPlugin(PM_PLUGIN_DIR);

        // Import e2e-test-order plugin which has i18n.json
        ImportExecuteResult result = importPlugin(PLUGIN_DIR);
        assertThat(result.isSuccess()).isTrue();

        // Verify i18n records were created
        I18nResource zhOrder = i18nResourceService.findByKeyAndLang(
                "model.e2et_order._meta.label", "zh-CN");
        assertThat(zhOrder).isNotNull();
        assertThat(zhOrder.getValue()).isEqualTo("测试订单");
        assertThat(zhOrder.getSource()).isEqualTo("import");

        I18nResource enOrder = i18nResourceService.findByKeyAndLang(
                "model.e2et_order._meta.label", "en-US");
        assertThat(enOrder).isNotNull();
        assertThat(enOrder.getValue()).isEqualTo("Test Order");

        // Verify field i18n records
        I18nResource zhField = i18nResourceService.findByKeyAndLang(
                "model.e2et_order.e2et_order_no.label", "zh-CN");
        assertThat(zhField).isNotNull();
        assertThat(zhField.getValue()).isEqualTo("订单编号");

        log.info("Verified i18n records created after plugin import");
    }

    @Test
    @Order(2)
    @DisplayName("batchUpsert should be idempotent - second import should not duplicate records")
    void batchUpsertShouldBeIdempotent() {
        // Import dependency first
        importPlugin(PM_PLUGIN_DIR);

        // First import
        ImportExecuteResult first = importPlugin(PLUGIN_DIR);
        assertThat(first.isSuccess()).isTrue();

        // Count records after first import
        List<I18nResource> afterFirst = i18nResourceService.findBySource("import");
        int countAfterFirst = afterFirst.size();
        assertThat(countAfterFirst).isGreaterThan(0);

        // Second import (should upsert, not duplicate)
        ImportExecuteResult second = importPlugin(PLUGIN_DIR);
        assertThat(second.isSuccess()).isTrue();

        // Count should remain the same
        List<I18nResource> afterSecond = i18nResourceService.findBySource("import");
        assertThat(afterSecond).hasSize(countAfterFirst);

        log.info("Verified idempotent import: {} records unchanged after second import", countAfterFirst);
    }

    @Test
    @Order(3)
    @DisplayName("i18n compilation should be triggered after plugin import")
    void i18nCompilationShouldBeTriggeredAfterImport() {
        // Import dependency first
        importPlugin(PM_PLUGIN_DIR);

        // Import plugin with i18n
        ImportExecuteResult result = importPlugin(PLUGIN_DIR);
        assertThat(result.isSuccess()).isTrue();

        // Verify compilation produces non-empty results
        I18nCompiler.CompileResult compileResult = i18nCompiler.compileAll();
        assertThat(compileResult.isSuccess()).isTrue();
        assertThat(compileResult.getTotalKeys()).isGreaterThan(0);

        // Verify zh-CN compilation has the imported keys
        I18nCompiler.CompileResult.LangResult zhResult = compileResult.getLangResults().get("zh-CN");
        assertThat(zhResult).isNotNull();
        assertThat(zhResult.isSuccess()).isTrue();
        assertThat(zhResult.getFlatMap())
                .containsKey("model.e2et_order._meta.label");

        log.info("Verified i18n compilation: {} total keys across {} languages",
                compileResult.getTotalKeys(), compileResult.getLangResults().size());
    }

    // ==================== Helpers ====================

    private ImportExecuteResult importPlugin(String pluginDir) {
        Path pluginPath = resolvePluginPath(pluginDir);
        ImportPreviewResult preview = pluginImportService.parseDirectory(pluginPath.toString());
        assertThat(preview.isValid())
                .as("Plugin manifest should be valid for %s: %s", pluginDir, preview.getErrors())
                .isTrue();

        ImportRequest request = ImportRequest.builder()
                .importId(preview.getImportId())
                .conflictStrategy(ImportRequest.ConflictStrategy.OVERWRITE)
                .autoPublishModels(true)
                .autoPublishFields(true)
                .autoPublishCommands(true)
                .autoPublishPages(false)
                .autoDeployProcesses(false)
                .build();

        return pluginImportService.execute(preview.getImportId(), request);
    }

    private Path resolvePluginPath(String pluginDir) {
        Path projectRoot = Paths.get(System.getProperty("user.dir"));
        if (projectRoot.endsWith("platform")) {
            projectRoot = projectRoot.getParent();
        }
        Path pluginPath = projectRoot.resolve(pluginDir);
        assertThat(pluginPath.toFile().exists())
                .as("Plugin directory should exist at: %s", pluginPath)
                .isTrue();
        return pluginPath;
    }
}
