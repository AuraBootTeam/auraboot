package com.auraboot.framework.plugin.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateRegistryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("listAll discovers metadata templates and legacy template directories")
    void listAllDiscoversMetadataAndLegacyTemplates() throws IOException {
        createPlugin(
                tempDir.resolve("plugins/crm-quick-start"),
                """
                {
                  "pluginId": "com.auraboot.template.crm-quick-start",
                  "namespace": "tcrm",
                  "version": "1.0.0",
                  "displayName": "CRM Quick Start",
                  "pluginType": "config",
                  "catalogType": "template"
                }
                """
        );
        createPlugin(
                tempDir.resolve("plugins/regular-plugin"),
                """
                {
                  "pluginId": "com.auraboot.regular-plugin",
                  "namespace": "regular",
                  "version": "1.0.0",
                  "displayName": "Regular Plugin",
                  "pluginType": "config"
                }
                """
        );
        createPlugin(
                tempDir.resolve("plugins/templates/hr-essentials"),
                """
                {
                  "pluginId": "com.auraboot.template.hr-essentials",
                  "namespace": "thr",
                  "version": "1.0.0",
                  "displayName": "HR Essentials",
                  "pluginType": "config"
                }
                """
        );

        TemplateRegistry registry = new TemplateRegistry(objectMapper, List.of(tempDir));

        List<TemplateRegistry.TemplateDef> templates = registry.listAll();

        assertThat(templates).extracting(TemplateRegistry.TemplateDef::id)
                .containsExactly("crm-quick-start", "hr-essentials");
        assertThat(registry.resolveAbsolutePath("crm-quick-start"))
                .endsWith("plugins/crm-quick-start");
        assertThat(registry.resolveAbsolutePath("hr-essentials"))
                .endsWith("plugins/templates/hr-essentials");
        assertThat(registry.getTemplate("regular-plugin")).isNull();
    }

    @Test
    @DisplayName("metadata template in plugins root wins over legacy duplicate")
    void metadataTemplateWinsOverLegacyDuplicate() throws IOException {
        createPlugin(
                tempDir.resolve("plugins/project-management"),
                """
                {
                  "pluginId": "com.auraboot.template.project-management",
                  "namespace": "tpm",
                  "version": "1.0.0",
                  "displayName": "Project Management",
                  "pluginType": "config",
                  "catalogType": "template"
                }
                """
        );
        createPlugin(
                tempDir.resolve("plugins/templates/project-management"),
                """
                {
                  "pluginId": "com.auraboot.template.project-management-legacy",
                  "namespace": "legacy",
                  "version": "1.0.0",
                  "displayName": "Legacy Project Management",
                  "pluginType": "config"
                }
                """
        );

        TemplateRegistry registry = new TemplateRegistry(objectMapper, List.of(tempDir));

        TemplateRegistry.TemplateDef template = registry.getTemplate("project-management");

        assertThat(template).isNotNull();
        assertThat(template.namespace()).isEqualTo("tpm");
        assertThat(template.relativePath()).isEqualTo("plugins/project-management");
    }

    // ---- A1: nullable description/category/icon ----

    @Test
    @DisplayName("readTemplate reads description/category/icon and falls back to null when absent")
    void readTemplate_readsDescriptionCategoryIcon_andFallsBackToNull() throws IOException {
        // Manifest WITH all three fields
        createPlugin(
                tempDir.resolve("plugins/rich-tpl"),
                """
                {
                  "catalogType": "template",
                  "displayName": "Rich Template",
                  "namespace": "rtpl",
                  "description": "A helpful template",
                  "category": "CRM",
                  "icon": "package"
                }
                """
        );
        // Manifest WITHOUT description/category/icon
        createPlugin(
                tempDir.resolve("plugins/bare-tpl"),
                """
                {
                  "catalogType": "template",
                  "displayName": "Bare Template",
                  "namespace": "btpl"
                }
                """
        );

        TemplateRegistry registry = new TemplateRegistry(objectMapper, List.of(tempDir));

        TemplateRegistry.TemplateDef rich = registry.getTemplate("rich-tpl");
        assertThat(rich).isNotNull();
        assertThat(rich.name()).isEqualTo("Rich Template");
        assertThat(rich.description()).isEqualTo("A helpful template");
        assertThat(rich.category()).isEqualTo("CRM");
        assertThat(rich.icon()).isEqualTo("package");

        TemplateRegistry.TemplateDef bare = registry.getTemplate("bare-tpl");
        assertThat(bare).isNotNull();
        assertThat(bare.description()).isNull();
        assertThat(bare.category()).isNull();
        assertThat(bare.icon()).isNull();
    }

    @Test
    @DisplayName("legacyTemplatesDirStillDiscovered via plugins/templates subdir")
    void legacyTemplatesDirStillDiscovered() throws IOException {
        createPlugin(
                tempDir.resolve("plugins/templates/legacy-x"),
                """
                {
                  "displayName": "Legacy X",
                  "namespace": "lx"
                }
                """
        );

        TemplateRegistry registry = new TemplateRegistry(objectMapper, List.of(tempDir));
        assertThat(registry.listAll()).extracting(TemplateRegistry.TemplateDef::id)
                .contains("legacy-x");
    }

    // ---- helpers ----

    private static void createPlugin(Path pluginDir, String pluginJson) throws IOException {
        Files.createDirectories(pluginDir);
        Files.writeString(pluginDir.resolve("plugin.json"), pluginJson);
    }
}
