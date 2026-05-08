package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.rule.DroolsRuleService;
import com.auraboot.framework.bpm.service.SlaConfigService;
import com.auraboot.framework.i18n.compiler.I18nCompiler;
import com.auraboot.framework.i18n.service.I18nResourceService;
import com.auraboot.framework.lock.DistributedLock;
import com.auraboot.framework.menu.mapper.MenuMapper;
import com.auraboot.framework.meta.service.CommandService;
import com.auraboot.framework.meta.service.MetaFieldService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.SchemaManagementService;
import com.auraboot.framework.meta.template.generator.DocumentCommandGenerator;
import com.auraboot.framework.permission.service.AutoPermissionAssignmentService;
import com.auraboot.framework.permission.service.PermissionService;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.plugin.config.PlatformProperties;
import com.auraboot.framework.plugin.service.PluginImportService;
import com.auraboot.framework.plugin.service.PluginImportService.ImportHistoryDTO;
import com.auraboot.framework.plugin.dto.imports.ImportPreviewResult;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import com.auraboot.framework.plugin.entity.PluginImportHistory;
import com.auraboot.framework.plugin.mapper.PluginImportHistoryMapper;
import com.auraboot.framework.plugin.mapper.PluginRecordMapper;
import com.auraboot.framework.plugin.mapper.PluginResourceMapper;
import com.auraboot.framework.plugin.service.PlatformVersionChecker;
import com.auraboot.framework.plugin.validation.PluginQualityScorer;
import com.auraboot.framework.plugin.validation.PluginValidationPipeline;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.view.mapper.SavedViewMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito branch tests for {@link PluginImportServiceImpl} fail-fast and validation paths
 * that do not require database, transaction template, or actual filesystem extraction.
 *
 * <p>Targets the small but heavily branched validation routine in {@code validateManifest}
 * (lines 2197-2260) plus the early-return branches in parseDirectory / parseSource /
 * parseJson / canRollback / cancelImport / getImportStatus.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PluginImportServiceImplBranchTest {

    @Mock private PluginImportHistoryMapper importHistoryMapper;
    @Mock private PluginRecordMapper pluginRecordMapper;
    @Mock private PluginResourceMapper pluginResourceMapper;
    @Mock private PluginResourceImporter resourceImporter;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private PluginDirectoryLoader directoryLoader;
    @Mock private MenuMapper menuMapper;
    @Mock private MetaModelService metaModelService;
    @Mock private MetaFieldService metaFieldService;
    @Mock private CommandService commandService;
    @Mock private SchemaManagementService schemaManagementService;
    @Mock private PermissionService permissionService;
    @Mock private UserPermissionService userPermissionService;
    @Mock private RoleService roleService;
    @Mock private RolePermissionMapper rolePermissionMapper;
    @Mock private DistributedLock distributedLock;
    @Mock private I18nResourceService i18nResourceService;
    @Mock private I18nCompiler i18nCompiler;
    @Mock private PlatformProperties platformProperties;
    @Mock private PlatformVersionChecker platformVersionChecker;
    @Mock private PluginValidationPipeline validationPipeline;
    @Mock private PluginQualityScorer qualityScorer;
    @Mock private SavedViewMapper savedViewMapper;
    @Mock private AutoPermissionAssignmentService autoPermissionAssignmentService;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private DocumentCommandGenerator documentCommandGenerator;
    @Mock private DroolsRuleService droolsRuleService;
    @Mock private SlaConfigService slaConfigService;
    @Mock private JdbcTemplate jdbcTemplate;

    @InjectMocks private PluginImportServiceImpl service;

    @BeforeEach
    void setUpContext() {
        if (MetaContext.exists()) {
            MetaContext.clear();
        }
        MetaContext.setContext(100L, 1L, "U-1", "tester");
    }

    @AfterEach
    void clearContext() {
        if (MetaContext.exists()) {
            MetaContext.clear();
        }
    }

    @Test
    @DisplayName("parseJson returns invalid result on malformed JSON")
    void parseJson_invalid() {
        ImportPreviewResult result = service.parseJson("{ this is not json", "evil.json");

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).isNotNull();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("Invalid JSON format"));
    }

    @Test
    @DisplayName("parseDirectory returns invalid result when path is not a directory")
    void parseDirectory_notADirectory() {
        ImportPreviewResult result = service.parseDirectory("/no/such/path/exists/here/__nope__");

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("not a directory"));
    }

    @Test
    @DisplayName("validateManifest returns single error when manifest is null")
    void validateManifest_null() {
        List<String> errors = service.validateManifest(null);

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).isEqualTo("Manifest is null");
    }

    @Test
    @DisplayName("validateManifest reports missing pluginId/namespace/version when blank")
    void validateManifest_missingRequired() {
        PluginManifestExtended manifest = new PluginManifestExtended();
        // pluginId, namespace, version all blank/null

        List<String> errors = service.validateManifest(manifest);

        assertThat(errors).anyMatch(e -> e.contains("pluginId is required"));
        assertThat(errors).anyMatch(e -> e.contains("namespace is required"));
        assertThat(errors).anyMatch(e -> e.contains("version is required"));
    }

    @Test
    @DisplayName("validateManifest rejects invalid semver")
    void validateManifest_invalidSemver() {
        PluginManifestExtended manifest = new PluginManifestExtended();
        manifest.setPluginId("com.demo");
        manifest.setNamespace("demo");
        manifest.setVersion("not-a-version");

        List<String> errors = service.validateManifest(manifest);

        assertThat(errors).anyMatch(e -> e.contains("not valid semver format"));
    }

    @Test
    @DisplayName("validateManifest accepts a minimal valid manifest with semver version")
    void validateManifest_minimalValid() {
        PluginManifestExtended manifest = new PluginManifestExtended();
        manifest.setPluginId("com.demo");
        manifest.setNamespace("demo");
        manifest.setVersion("1.0.0");

        List<String> errors = service.validateManifest(manifest);

        // No required-field errors; no semver errors
        assertThat(errors).noneMatch(e -> e.contains("pluginId is required"));
        assertThat(errors).noneMatch(e -> e.contains("namespace is required"));
        assertThat(errors).noneMatch(e -> e.contains("version is required"));
        assertThat(errors).noneMatch(e -> e.contains("not valid semver format"));
    }

    @Test
    @DisplayName("validateManifest emits INCOMPATIBLE error from platformVersionChecker")
    void validateManifest_incompatiblePlatform() {
        PluginManifestExtended manifest = new PluginManifestExtended();
        manifest.setPluginId("com.demo");
        manifest.setNamespace("demo");
        manifest.setVersion("1.0.0");
        manifest.setMinPlatformVersion("99.0.0");

        when(platformVersionChecker.check(eq("99.0.0"), eq(null))).thenReturn(
                new PlatformVersionChecker.CompatibilityResult(
                        PlatformVersionChecker.CompatibilityStatus.INCOMPATIBLE,
                        "1.0.0", "99.0.0", null, "Platform too old"));

        List<String> errors = service.validateManifest(manifest);

        assertThat(errors).contains("Platform too old");
    }

    @Test
    @DisplayName("validateManifest prefixes WARN_NEWER messages with [WARN]")
    void validateManifest_warnNewer() {
        PluginManifestExtended manifest = new PluginManifestExtended();
        manifest.setPluginId("com.demo");
        manifest.setNamespace("demo");
        manifest.setVersion("1.0.0");
        manifest.setMaxPlatformVersion("0.5.0");

        when(platformVersionChecker.check(eq(null), eq("0.5.0"))).thenReturn(
                new PlatformVersionChecker.CompatibilityResult(
                        PlatformVersionChecker.CompatibilityStatus.WARN_NEWER,
                        "1.0.0", null, "0.5.0", "Plugin built for older platform"));

        List<String> errors = service.validateManifest(manifest);

        assertThat(errors).anyMatch(e -> e.startsWith("[WARN]"));
    }

    @Test
    @DisplayName("canRollback false when no history record")
    void canRollback_noHistory() {
        when(importHistoryMapper.findByImportId("missing")).thenReturn(null);
        assertThat(service.canRollback("missing")).isFalse();
    }

    @Test
    @DisplayName("canRollback false when history status is not SUCCESS")
    void canRollback_notSuccess() {
        PluginImportHistory history = new PluginImportHistory();
        history.setStatus("FAILED");
        when(importHistoryMapper.findByImportId("h")).thenReturn(history);

        assertThat(service.canRollback("h")).isFalse();
    }

    @Test
    @DisplayName("getImportStatus returns null when history not found")
    void getImportStatus_null() {
        when(importHistoryMapper.findByImportId("missing")).thenReturn(null);
        assertThat(service.getImportStatus("missing")).isNull();
    }

    @Test
    @DisplayName("getImportStatus returns mapped DTO when history present")
    void getImportStatus_dto() {
        PluginImportHistory history = new PluginImportHistory();
        history.setImportId("imp-1");
        history.setPluginPid("pp");
        history.setPluginId("com.demo");
        history.setStatus("SUCCESS");

        when(importHistoryMapper.findByImportId("imp-1")).thenReturn(history);

        ImportHistoryDTO dto = service.getImportStatus("imp-1");

        assertThat(dto).isNotNull();
        assertThat(dto.importId()).isEqualTo("imp-1");
        assertThat(dto.pluginId()).isEqualTo("com.demo");
        assertThat(dto.status()).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("cancelImport returns false when no in-flight context cached")
    void cancelImport_noContext() {
        assertThat(service.cancelImport("nope")).isFalse();
        verify(importHistoryMapper, never()).updateStatus(anyString(), anyString());
    }
}
