package com.auraboot.framework.plugin.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.plugin.dto.imports.ImportExecuteResult;
import com.auraboot.framework.plugin.dto.imports.ImportPreviewResult;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.exception.PluginException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URL;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for the two-phase cross-plugin reference fix against a real PostgreSQL DB.
 *
 * <p>Reproduces the crm↔sales cold-reset cycle with two fixture plugins whose commands reference
 * each other's model, plus dangling fixtures. Proves end-to-end (parse → execute → sweep):
 * <ol>
 *   <li>The default (strict) mode still hard-errors on a cross-plugin command→model reference —
 *       the regression guard proving the fix did not silently weaken validation.</li>
 *   <li>With {@code deferReferenceValidation}, a cyclic plugin pair imports successfully even though
 *       no per-plugin order satisfies the strict rule, and the closing sweep then finds the cyclic
 *       references intact. Command→model is the deferral leg: it is not governed by the
 *       role-permission fail-closed reconcile.</li>
 *   <li>A command referencing a model no plugin provides still imports under deferral but is flagged
 *       by {@link PluginImportService#verifyImportReferenceIntegrity()}.</li>
 *   <li>Role→permission and menu→parent-menu references no plugin provides fail the IMPORT closed
 *       with locatable errors (role-permission fail-closed: the historic warn+skip silently
 *       degraded roles and silently re-parented menus; both surfaced rounds later as findings).
 *       The closing sweep therefore only ever sees the command→model leg.</li>
 * </ol>
 *
 * Uses real PostgreSQL (no H2/mock); extends {@link BaseIntegrationTest} for MetaContext + tenant.
 */
class PluginImportTwoPhaseReferenceIntegrationTest extends BaseIntegrationTest {

    private static final String PLUGIN_A = "plugin-test/twophase-cycle/plugin-a";
    private static final String PLUGIN_B = "plugin-test/twophase-cycle/plugin-b";
    private static final String PLUGIN_C_DANGLING = "plugin-test/twophase-cycle/plugin-c-dangling";
    private static final String PLUGIN_D_DANGLING_COMMAND = "plugin-test/twophase-cycle/plugin-d-dangling-command";
    private static final String PLUGIN_E_DANGLING_ROLE = "plugin-test/twophase-cycle/plugin-e-dangling-role";

    @Autowired
    private PluginImportService pluginImportService;
    @Autowired private com.auraboot.framework.meta.service.MetaModelService models;
    @Autowired private com.auraboot.framework.openplatform.service.OpenApiPublicationService publications;
    @Autowired private com.auraboot.framework.plugin.mapper.PluginResourceMapper resources;
    @Autowired private com.auraboot.framework.plugin.mapper.PluginRecordMapper plugins;
    @Autowired private com.auraboot.framework.plugin.mapper.PluginImportHistoryMapper histories;
    @Autowired private com.fasterxml.jackson.databind.ObjectMapper json;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private String dirOf(String resourcePath) throws Exception {
        URL resource = getClass().getClassLoader().getResource(resourcePath);
        assertThat(resource).as("fixture dir %s must exist in test resources", resourcePath).isNotNull();
        return Paths.get(resource.toURI()).toString();
    }

    private ImportExecuteResult importDir(String resourcePath, boolean defer) throws Exception {
        ImportPreviewResult preview = pluginImportService.parseDirectory(dirOf(resourcePath), defer);
        assertThat(preview.isValid())
                .as("preview must be valid (defer=%s) but had errors: %s", defer, preview.getErrors())
                .isTrue();
        return pluginImportService.execute(preview.getImportId(),
                ImportRequest.builder()
                        .importId(preview.getImportId())
                        .conflictStrategy(ImportRequest.ConflictStrategy.OVERWRITE)
                        .build());
    }

    @Test
    @DisplayName("strict mode hard-errors on a command->model reference no plugin provides (regression guard)")
    void strictMode_unresolvableCommandModel_isHardError() throws Exception {
        // tpc:dangling references tp_nonexistent_model, which NO fixture ever creates — so this is a
        // hard error in strict mode regardless of what else has been imported into the test tenant.
        ImportPreviewResult preview = pluginImportService.parseDirectory(dirOf(PLUGIN_C_DANGLING), false);

        assertThat(preview.isValid())
                .as("strict mode must reject an unresolvable cross-plugin reference")
                .isFalse();
        assertThat(preview.getErrors())
                .anyMatch(e -> e.contains("tpc:dangling") && e.contains("tp_nonexistent_model"));
    }

    /**
     * Regression guard for the pcba-solution ↔ pcba-compliance ↔ pcba-manufacturing cyclic fix:
     * strict mode must still hard-error on menu->parent-menu and role->permission references no
     * plugin provides (tpc_dangling_menu -> tp_nonexistent_menu, tpc_dangling_role ->
     * tp_nonexistent_permission), proving deferral did not silently weaken these checks either.
     */
    @Test
    @DisplayName("strict mode hard-errors on menu->parent-menu and role->permission references no plugin provides")
    void strictMode_unresolvableMenuParentAndRolePermission_isHardError() throws Exception {
        ImportPreviewResult preview = pluginImportService.parseDirectory(dirOf(PLUGIN_C_DANGLING), false);

        assertThat(preview.isValid())
                .as("strict mode must reject unresolvable cross-plugin menu/role references")
                .isFalse();
        assertThat(preview.getErrors())
                .anyMatch(e -> e.contains("tpc_dangling_menu") && e.contains("tp_nonexistent_menu"));
        assertThat(preview.getErrors())
                .anyMatch(e -> e.contains("tpc_dangling_role") && e.contains("tp_nonexistent_permission"));
    }

    @Test
    @DisplayName("deferral imports a cyclic plugin pair; closing sweep finds the cyclic refs intact")
    void deferral_importsCyclicPair_sweepFindsRefsIntact() throws Exception {
        // plugin-a's command references tp_b_model (owned by B, not yet imported): allowed by
        // deferral. Its menu is now self-rooted — a not-yet-imported parent menu would fail
        // closed under the role/menu fail-closed contract instead of silently re-parenting.
        ImportExecuteResult a = importDir(PLUGIN_A, true);
        assertThat(a.isSuccess()).as("plugin-a import failed: %s", a.getErrorMessage()).isTrue();

        // plugin-b's command references tp_a_model, now provided by A; plugin-b's role references
        // A's permission tpa:view, now provided by A (provider imported first — resolves in order).
        ImportExecuteResult b = importDir(PLUGIN_B, true);
        assertThat(b.isSuccess()).as("plugin-b import failed: %s", b.getErrorMessage()).isTrue();

        // Both tp_a_model and tp_b_model exist now → the cyclic command/model refs resolve.
        // tpa:view (B's role permission) exists too → resolves as well. (Residue-tolerant:
        // asserts only that the cyclic references are not flagged, not that the list is empty.)
        List<String> dangling = pluginImportService.verifyImportReferenceIntegrity();
        assertThat(dangling)
                .as("cyclic refs must resolve once both sides are imported, but got: %s", dangling)
                .noneMatch(s -> s.contains("tpa:use_b"))
                .noneMatch(s -> s.contains("tpb:use_a"))
                .noneMatch(s -> s.contains("tpb_role"));
    }

    /**
     * Command→model is the deferral leg: a command referencing a model no plugin provides still
     * imports under deferral and is flagged by the closing sweep.
     */
    @Test
    @DisplayName("closing sweep flags command->model references no plugin provides")
    void deferral_danglingCommandModel_importsAndIsFlaggedBySweep() throws Exception {
        ImportExecuteResult d = importDir(PLUGIN_D_DANGLING_COMMAND, true);
        assertThat(d.isSuccess()).as("plugin-d (deferred) import failed: %s", d.getErrorMessage()).isTrue();

        List<String> dangling = pluginImportService.verifyImportReferenceIntegrity();
        assertThat(dangling)
                .as("sweep must flag tpd:dangling -> tp_nonexistent_model, got: %s", dangling)
                .anyMatch(s -> s.contains("tpd:dangling") && s.contains("tp_nonexistent_model"));
    }

    /**
     * Role-permission fail-closed contract: a role declaring a permission no plugin provides
     * fails the import (rolled back) with a locatable error instead of the historic warn+skip
     * that silently degraded the role.
     */
    @Test
    @DisplayName("dangling role->permission reference fails the import closed with a locatable error")
    void deferral_danglingRolePermission_failsClosedWithLocatableError() throws Exception {
        ImportPreviewResult preview = pluginImportService.parseDirectory(dirOf(PLUGIN_E_DANGLING_ROLE), true);
        assertThat(preview.isValid())
                .as("deferral still skips the preview-level check, but had errors: %s", preview.getErrors())
                .isTrue();

        assertThatThrownBy(() -> pluginImportService.execute(preview.getImportId(),
                ImportRequest.builder()
                        .importId(preview.getImportId())
                        .conflictStrategy(ImportRequest.ConflictStrategy.OVERWRITE)
                        .build()))
                .isInstanceOf(PluginException.class)
                .hasMessageContaining("tpe_dangling_role")
                .hasMessageContaining("tp_nonexistent_permission")
                .hasMessageContaining("provider plugin");
    }

    /**
     * Menu fail-closed contract: a menu whose parentCode no plugin provides fails the import
     * instead of being silently re-parented to root (the old behavior permanently corrupted
     * navigation; the closing sweep only flagged it after the fact).
     */
    @Test
    @DisplayName("dangling menu->parent-menu reference fails the import closed with a locatable error")
    void deferral_danglingMenuParent_failsClosedWithLocatableError() throws Exception {
        ImportPreviewResult preview = pluginImportService.parseDirectory(dirOf(PLUGIN_C_DANGLING), true);
        assertThat(preview.isValid())
                .as("deferral still skips the preview-level check, but had errors: %s", preview.getErrors())
                .isTrue();

        // The menu import stage runs before the post-publication reconcile, so the menu
        // parent failure is the one that surfaces for this fixture (its role declaration
        // is covered by plugin-e).
        assertThatThrownBy(() -> pluginImportService.execute(preview.getImportId(),
                ImportRequest.builder()
                        .importId(preview.getImportId())
                        .conflictStrategy(ImportRequest.ConflictStrategy.OVERWRITE)
                        .build()))
                .isInstanceOf(PluginException.class)
                .hasMessageContaining("tpc_dangling_menu")
                .hasMessageContaining("tp_nonexistent_menu");
    }
    @org.junit.jupiter.params.ParameterizedTest(name = "public publication import and rollback via {0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"directory", "zip", "source"})
    void publicResourcePublishesOnlyAfterImportedModelAndBindingsAreReady(String entry,
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        grantCommittedPermissionToTestRole("sys.connector.update", "system", "connector", "update", "Manage public publications");
        String suffix = com.auraboot.framework.common.util.UniqueIdGenerator.generate().toLowerCase();
        String namespace = "pub" + suffix;
        String modelCode = namespace + "_record", fieldCode = namespace + "_name", publicCode = "import." + suffix;
        var manifest = json.createObjectNode().put("pluginId", "com.example.public-import-" + suffix)
                .put("namespace", namespace).put("version", "1.0.0").put("displayName", "Public import order fixture");
        manifest.putArray("models").addObject().put("code", modelCode).put("displayName", "Publication source")
                .put("modelType", "entity").putObject("capabilities").put("list", true).put("detail", true);
        manifest.putArray("fields").addObject().put("code", fieldCode).put("displayName", "Name").put("dataType", "string");
        manifest.putArray("modelFieldBindings").addObject().put("modelCode", modelCode).put("fieldCode", fieldCode).put("sequence", 10);
        try (var fixture = getClass().getResourceAsStream("/plugin/public-resource-contract.json")) {
            var contract = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(java.util.Objects.requireNonNull(fixture));
            contract.put("code", publicCode).put("modelCode", modelCode);
            manifest.putArray("publicResources").add(contract);
        }
        writePublicFixture(dir, manifest);
        var preview = parsePublicFixture(dir, entry);
        assertThat(preview.isValid()).as("public import preview errors: %s", preview.getErrors()).isTrue();
        assertThat(histories.findByImportId(preview.getImportId()).getSourceType()).isEqualTo(entry);
        try {
            com.auraboot.framework.application.tenant.MetaContext.setContext(testTenant.getId() + 1_000_000L,
                    testUser.getId(), testUser.getPid(), testUser.getUserName());
            assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> pluginImportService.execute(preview.getImportId(), new ImportRequest())))
                    .as("cached preview cannot execute under a different tenant")
                    .isInstanceOf(com.auraboot.framework.plugin.exception.PluginException.class)
                    .hasMessageContaining("tenant does not match preview owner");
        } finally {
            applyTestMetaContext();
        }
        assertThat(histories.findByImportId(preview.getImportId()).getStatus()).isEqualTo("previewing");
        assertThat(plugins.findByTenantAndPluginId(manifest.get("pluginId").asText())).isNull();
        var result = pluginImportService.execute(preview.getImportId(), new ImportRequest());
        assertThat(result.isSuccess()).as("public import failed: %s", result.getErrorMessage()).isTrue();
        var originalPluginManifest = json.readTree(jdbc.queryForObject("SELECT manifest::text FROM ab_plugin WHERE tenant_id = ? AND pid = ?", String.class, testTenant.getId(), result.getPluginPid()));
        var active = publications.active(testTenant.getId(), publicCode).orElseThrow();
        assertThat(active.modelCode()).isEqualTo(modelCode);
        assertThat(active.schemaVersion()).isEqualTo(1);
        assertThat(models.getModelDefinition(modelCode).orElseThrow().getStatus()).isEqualTo("published");
        assertThat(models.getModelDefinition(modelCode).orElseThrow().getCapabilities().isList()).isTrue();
        assertThat(models.getModelDefinition(modelCode).orElseThrow().getCapabilities().isCreate()).isFalse();
        assertThat(models.getModelDefinition(modelCode).orElseThrow().getFields()).extracting("code").contains(fieldCode);
        var owned = resources.findByTypeAndCode(testTenant.getId(),
                com.auraboot.framework.plugin.dto.imports.ResourceType.PUBLIC_RESOURCE.code(), publicCode);
        assertThat(owned.getImportId()).isEqualTo(preview.getImportId());
        assertThat(owned.getLastSyncVersion()).isEqualTo("1.0.0");
        assertThat(owned.getImportSnapshot()).containsEntry("status", "published");
        assertThat(result.getResourceCounts()).containsKey("PUBLIC_RESOURCE");
        assertThat(result.getResourceCounts().get("PUBLIC_RESOURCE")).containsEntry("CREATE", 1);

        var originalSources = resources.findByPluginPid(result.getPluginPid()).stream()
                .filter(resource -> !resource.getResourceTypeEnum().isPublicPublication()).toList();
        assertThat(originalSources).hasSize(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ab_meta_model WHERE code = ? AND (deleted_flag = FALSE OR deleted_flag IS NULL)", Long.class, modelCode)).isEqualTo(1L);
        manifest.remove(java.util.List.of("models", "fields", "modelFieldBindings"));
        manifest.put("version", "2.0.0").put("displayName", "Changed public import metadata").put("description", "Upgrade description");
        ((com.fasterxml.jackson.databind.node.ObjectNode) manifest.get("publicResources").get(0))
                .put("schemaVersion", 2).put("maxPageSize", 30);
        writePublicFixture(dir, manifest);
        var upgradePreview = parsePublicFixture(dir, entry);
        assertThat(upgradePreview.isValid()).isTrue();
        var upgraded = pluginImportService.execute(upgradePreview.getImportId(), new ImportRequest());
        assertThat(upgraded.isSuccess()).as("public upgrade failed: %s", upgraded.getErrorMessage()).isTrue();
        assertThat(publications.active(testTenant.getId(), publicCode).orElseThrow().schemaVersion()).isEqualTo(2);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> pluginImportService.rollback(preview.getImportId())))
                .isInstanceOf(com.auraboot.framework.plugin.exception.PluginException.class).hasMessageContaining("latest successful");
        assertThat(publications.active(testTenant.getId(), publicCode).orElseThrow().schemaVersion()).isEqualTo(2);
        assertThat(pluginImportService.rollback(upgradePreview.getImportId()).isSuccess()).isTrue();
        assertThat(jdbc.queryForObject("SELECT version FROM ab_plugin WHERE tenant_id = ? AND pid = ?", String.class, testTenant.getId(), result.getPluginPid())).as("publication rollback restores plugin metadata version").isEqualTo("1.0.0");
        assertThat(jdbc.queryForObject("SELECT display_name FROM ab_plugin WHERE tenant_id = ? AND pid = ?", String.class, testTenant.getId(), result.getPluginPid())).isEqualTo("Public import order fixture");
        assertThat(jdbc.queryForObject("SELECT description FROM ab_plugin WHERE tenant_id = ? AND pid = ?", String.class, testTenant.getId(), result.getPluginPid())).isNull();
        assertThat(json.readTree(jdbc.queryForObject("SELECT manifest::text FROM ab_plugin WHERE tenant_id = ? AND pid = ?", String.class, testTenant.getId(), result.getPluginPid()))).isEqualTo(originalPluginManifest);
        assertThat(publications.active(testTenant.getId(), publicCode).orElseThrow().schemaVersion()).isEqualTo(1);
        assertThat(publications.snapshots(testTenant.getId(), publicCode)).hasSize(2);
        for (var original : originalSources) {
            var retained = resources.findByTypeAndCode(testTenant.getId(), original.getResourceType(), original.getResourceCode());
            assertThat(retained).as("prior source ownership must survive a publication-only rollback: %s", original.getResourceCode()).isNotNull();
            assertThat(retained.getPid()).isEqualTo(original.getPid());
            assertThat(retained.getImportId()).isEqualTo(preview.getImportId());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ab_meta_model WHERE code = ? AND (deleted_flag = FALSE OR deleted_flag IS NULL)", Long.class, modelCode)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ab_meta_field WHERE code = ? AND (deleted_flag = FALSE OR deleted_flag IS NULL)", Long.class, fieldCode)).isEqualTo(1L);
        assertThat(resources.findByTypeAndCode(testTenant.getId(),
                com.auraboot.framework.plugin.dto.imports.ResourceType.PUBLIC_RESOURCE.code(), publicCode).getLastSyncVersion())
                .isEqualTo("1.0.0");

        manifest.put("version", "3.0.0");
        ((com.fasterxml.jackson.databind.node.ObjectNode) manifest.get("publicResources").get(0))
                .put("schemaVersion", 1).put("maxPageSize", 25);
        writePublicFixture(dir, manifest);
        var unchangedPreview = parsePublicFixture(dir, entry);
        assertThat(unchangedPreview.isValid()).isTrue();
        var unchanged = pluginImportService.execute(unchangedPreview.getImportId(), new ImportRequest());
        assertThat(unchanged.isSuccess()).isTrue();
        assertThat(unchanged.getResourceCounts().get("PUBLIC_RESOURCE")).containsEntry("SKIP", 1);
        assertThat(publications.snapshots(testTenant.getId(), publicCode)).hasSize(2);

        var installed = plugins.selectById(plugins.findByPid(result.getPluginPid()).getId());
        installed.setDisplayName("User edited metadata");
        installed.setSettings(java.util.Map.of("retainedSetting", "user-setting"));
        assertThat(plugins.updateById(installed)).isEqualTo(1);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> pluginImportService.rollback(unchangedPreview.getImportId())))
                .as("rollback must not overwrite metadata edited after import")
                .isInstanceOf(com.auraboot.framework.plugin.exception.PluginException.class)
                .hasMessageContaining("metadata changed after import");
        assertThat(plugins.selectById(installed.getId()).getDisplayName()).isEqualTo("User edited metadata");
        assertThat(publications.active(testTenant.getId(), publicCode).orElseThrow().schemaVersion()).isEqualTo(1);
        assertThat(publications.snapshots(testTenant.getId(), publicCode)).hasSize(2);
        assertThat(resources.findByTypeAndCode(testTenant.getId(),
                com.auraboot.framework.plugin.dto.imports.ResourceType.PUBLIC_RESOURCE.code(), publicCode).getImportId())
                .isEqualTo(unchangedPreview.getImportId());
        installed.setDisplayName("Changed public import metadata");
        assertThat(plugins.updateById(installed)).isEqualTo(1);

        var history = histories.findByImportId(unchangedPreview.getImportId());
        var persistedSnapshot = history.getManifestSnapshot();
        assertThat(persistedSnapshot).containsKeys("schemaVersion", "before", "after");
        assertThat(histories.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<com.auraboot.framework.plugin.entity.PluginImportHistory>()
                .eq(com.auraboot.framework.plugin.entity.PluginImportHistory::getId, history.getId())
                .set(com.auraboot.framework.plugin.entity.PluginImportHistory::getManifestSnapshot, null))).isEqualTo(1);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> pluginImportService.rollback(unchangedPreview.getImportId())))
                .as("legacy public upgrade without a metadata snapshot must fail closed")
                .isInstanceOf(com.auraboot.framework.plugin.exception.PluginException.class)
                .hasMessageContaining("rollback snapshot is missing");
        assertThat(plugins.selectById(installed.getId()).getVersion()).isEqualTo("3.0.0");
        assertThat(resources.findByTypeAndCode(testTenant.getId(),
                com.auraboot.framework.plugin.dto.imports.ResourceType.PUBLIC_RESOURCE.code(), publicCode).getImportId())
                .isEqualTo(unchangedPreview.getImportId());
        assertThat(publications.snapshots(testTenant.getId(), publicCode)).hasSize(2);
        history.setManifestSnapshot(persistedSnapshot);
        assertThat(histories.updateById(history)).isEqualTo(1);
        assertThat(pluginImportService.rollback(unchangedPreview.getImportId()).isSuccess()).isTrue();
        assertThat(plugins.selectById(installed.getId()).getSettings()).containsEntry("retainedSetting", "user-setting");
        assertThat(jdbc.queryForObject("SELECT version FROM ab_plugin WHERE tenant_id = ? AND pid = ?", String.class, testTenant.getId(), result.getPluginPid())).as("unchanged publication rollback restores plugin metadata version").isEqualTo("1.0.0");
        assertThat(json.readTree(jdbc.queryForObject("SELECT manifest::text FROM ab_plugin WHERE tenant_id = ? AND pid = ?", String.class, testTenant.getId(), result.getPluginPid()))).isEqualTo(originalPluginManifest);
        var restoredOwner = resources.findByTypeAndCode(testTenant.getId(),
                com.auraboot.framework.plugin.dto.imports.ResourceType.PUBLIC_RESOURCE.code(), publicCode);
        assertThat(restoredOwner.getImportId()).as("unchanged import rollback restores earlier ownership").isEqualTo(preview.getImportId());
        assertThat(restoredOwner.getLastSyncVersion()).isEqualTo("1.0.0");
        assertThat(publications.active(testTenant.getId(), publicCode).orElseThrow().schemaVersion()).isEqualTo(1);
        assertThat(publications.snapshots(testTenant.getId(), publicCode)).hasSize(2);
    }

    private void writePublicFixture(java.nio.file.Path dir, com.fasterxml.jackson.databind.node.ObjectNode manifest) throws Exception {
        var fileManifest = manifest.deepCopy();
        var contracts = fileManifest.remove("publicResources");
        fileManifest.putObject("resourceDirs").put("publicResources", "config/public-resources.json");
        java.nio.file.Files.createDirectories(dir.resolve("config"));
        java.nio.file.Files.writeString(dir.resolve("config/public-resources.json"), json.writeValueAsString(contracts));
        java.nio.file.Files.writeString(dir.resolve("plugin.json"), json.writeValueAsString(fileManifest));
    }

    private ImportPreviewResult parsePublicFixture(java.nio.file.Path dir, String entry) throws Exception {
        return switch (entry) {
            case "directory" -> pluginImportService.parseDirectory(dir.toString());
            case "source" -> pluginImportService.parseSource(new com.auraboot.framework.plugin.source.FileSystemPluginSource(dir));
            case "zip" -> {
                var bytes = new java.io.ByteArrayOutputStream();
                try (var zip = new java.util.zip.ZipOutputStream(bytes)) {
                    for (String file : java.util.List.of("plugin.json", "config/public-resources.json")) {
                        zip.putNextEntry(new java.util.zip.ZipEntry(file));
                        zip.write(java.nio.file.Files.readAllBytes(dir.resolve(file)));
                        zip.closeEntry();
                    }
                }
                yield pluginImportService.parse(new java.io.ByteArrayInputStream(bytes.toByteArray()), "public-import.zip");
            }
            default -> throw new IllegalArgumentException("Unknown fixture import entry: " + entry);
        };
    }

}
