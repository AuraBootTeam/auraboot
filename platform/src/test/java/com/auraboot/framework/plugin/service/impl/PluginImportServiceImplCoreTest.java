package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.rule.DroolsRuleService;
import com.auraboot.framework.bpm.service.SlaConfigService;
import com.auraboot.framework.exception.RootUnCheckedException;
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
import com.auraboot.framework.plugin.dto.PluginManifest;
import com.auraboot.framework.plugin.dto.imports.BindingRuleDTO;
import com.auraboot.framework.plugin.dto.imports.ImportPreviewResult;
import com.auraboot.framework.plugin.dto.imports.MenuDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ModelDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.PermissionDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import com.auraboot.framework.plugin.dto.imports.ResourceType;
import com.auraboot.framework.plugin.dto.imports.RoleDefinitionDTO;
import com.auraboot.framework.plugin.entity.PluginImportHistory;
import com.auraboot.framework.plugin.entity.PluginRecord;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.auraboot.framework.plugin.exception.PluginException;
import com.auraboot.framework.plugin.mapper.PluginImportHistoryMapper;
import com.auraboot.framework.plugin.mapper.PluginRecordMapper;
import com.auraboot.framework.plugin.mapper.PluginResourceMapper;
import com.auraboot.framework.plugin.service.PlatformVersionChecker;
import com.auraboot.framework.plugin.service.PluginImportService.ImportHistoryDTO;
import com.auraboot.framework.plugin.source.PluginSource;
import com.auraboot.framework.plugin.validation.PluginQualityScorer;
import com.auraboot.framework.plugin.validation.PluginValidationPipeline;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.view.mapper.SavedViewMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito tests covering validation, conflict detection, dependency analysis,
 * rollback orchestration, history listing and miscellaneous DTO mapping branches in
 * {@link PluginImportServiceImpl}. Focuses on logic that does not require a real
 * filesystem, distributed-lock acquisition or transactional database state.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PluginImportServiceImplCoreTest {

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

    private PluginManifestExtended baseManifest() {
        PluginManifestExtended m = new PluginManifestExtended();
        m.setPluginId("com.demo");
        m.setNamespace("demo");
        m.setVersion("1.0.0");
        return m;
    }

    @SuppressWarnings("unchecked")
    private void invokeLoadResourcesFromZip(PluginManifestExtended manifest, Map<String, byte[]> files) {
        try {
            Method method = PluginImportServiceImpl.class.getDeclaredMethod(
                    "loadResourcesFromZipFiles", PluginManifestExtended.class, Map.class);
            method.setAccessible(true);
            method.invoke(service, manifest, files);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException(cause);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    // ---------- validateManifest deeper branches ----------

    @Test
    @DisplayName("validateManifest reports invalid pluginType when not in allowed set")
    void validateManifest_invalidPluginType() {
        PluginManifestExtended m = baseManifest();
        m.setPluginType("rogue");

        List<String> errors = service.validateManifest(m);

        assertThat(errors).anyMatch(e -> e.contains("Invalid pluginType 'rogue'"));
    }

    @Test
    @DisplayName("validateManifest accepts canonical pluginTypes (config/hybrid/solution)")
    void validateManifest_validPluginTypes() {
        for (String type : List.of("config", "hybrid", "solution")) {
            PluginManifestExtended m = baseManifest();
            m.setPluginType(type);
            List<String> errors = service.validateManifest(m);
            assertThat(errors).as("type=%s", type).noneMatch(e -> e.contains("Invalid pluginType"));
        }
    }

    @Test
    @DisplayName("validateManifest emits WARN_OLDER as hard error (treated as platform-too-old)")
    void validateManifest_warnOlderTreatedAsError() {
        PluginManifestExtended m = baseManifest();
        m.setMinPlatformVersion("0.5.0");

        when(platformVersionChecker.check(eq("0.5.0"), eq(null))).thenReturn(
                new PlatformVersionChecker.CompatibilityResult(
                        PlatformVersionChecker.CompatibilityStatus.WARN_OLDER,
                        "1.0.0", "0.5.0", null, "Plugin built for older runtime"));

        List<String> errors = service.validateManifest(m);

        assertThat(errors).contains("Plugin built for older runtime");
        // WARN_OLDER must NOT be prefixed with [WARN]
        assertThat(errors).noneMatch(e -> e.equals("[WARN] Plugin built for older runtime"));
    }

    @Test
    @DisplayName("validateManifest does not invoke version checker when min/max both blank")
    void validateManifest_skipsCheckerWhenNoBounds() {
        PluginManifestExtended m = baseManifest();
        // no min/max
        service.validateManifest(m);
        verify(platformVersionChecker, never()).check(anyString(), anyString());
    }

    @Test
    @DisplayName("ZIP resourceDirs loads bindingRules from nested JSON files")
    void zipResourceDirs_loadsBindingRules() {
        PluginManifestExtended m = baseManifest();
        m.setResourceDirs(Map.of("bindingRules", "config/binding-rules"));
        Map<String, byte[]> files = Map.of(
                "config/binding-rules/rules.json",
                """
                [
                  {
                    "commandCode": "demo:approve",
                    "ruleType": "field_map",
                    "sequence": 10,
                    "targetModel": "demo_order",
                    "targetField": "status",
                    "sourceField": "approvalStatus"
                  }
                ]
                """.getBytes(StandardCharsets.UTF_8)
        );

        invokeLoadResourcesFromZip(m, files);

        assertThat(m.getBindingRules())
                .extracting(BindingRuleDTO::getCommandCode)
                .containsExactly("demo:approve");
    }

    @Test
    @DisplayName("ZIP resourceDirs bindingRules fail fast when declared resource JSON is invalid")
    void zipResourceDirs_bindingRulesInvalidJsonFailsFast() {
        PluginManifestExtended m = baseManifest();
        m.setResourceDirs(Map.of("bindingRules", "config/binding-rules"));
        Map<String, byte[]> files = Map.of(
                "config/binding-rules/rules.json",
                "{not-json".getBytes(StandardCharsets.UTF_8)
        );

        assertThatThrownBy(() -> invokeLoadResourcesFromZip(m, files))
                .isInstanceOf(PluginException.class)
                .hasMessageContaining("Failed to parse ZIP resource file")
                .hasMessageContaining("config/binding-rules/rules.json");
    }

    @Test
    @DisplayName("validateManifest flags binding with missing modelCode and missing fieldCode")
    void validateManifest_bindingMissingCodes() {
        PluginManifestExtended m = baseManifest();
        com.auraboot.framework.plugin.dto.imports.ModelFieldBindingDTO b =
                new com.auraboot.framework.plugin.dto.imports.ModelFieldBindingDTO();
        // both blank
        m.setModelFieldBindings(List.of(b));

        List<String> errors = service.validateManifest(m);

        assertThat(errors).anyMatch(e -> e.contains("missing modelCode"));
        assertThat(errors).anyMatch(e -> e.contains("missing fieldCode"));
    }

    @Test
    @DisplayName("validateManifest flags role permission referencing missing permission code")
    void validateManifest_roleRefsMissingPermission() {
        PluginManifestExtended m = baseManifest();
        RoleDefinitionDTO role = new RoleDefinitionDTO();
        role.setCode("role.x");
        role.setPermissions(List.of("perm.missing"));
        m.setRoles(List.of(role));

        when(resourceImporter.checkPermissionExists(eq(1L), eq("perm.missing"))).thenReturn(false);

        List<String> errors = service.validateManifest(m);

        assertThat(errors).anyMatch(e ->
                e.contains("Role 'role.x'") && e.contains("perm.missing"));
    }

    @Test
    @DisplayName("validateManifest does NOT flag role permission satisfied by manifest's own permissions")
    void validateManifest_roleRefsSelfDefinedPermission() {
        PluginManifestExtended m = baseManifest();
        PermissionDefinitionDTO perm = new PermissionDefinitionDTO();
        perm.setCode("perm.local");
        m.setPermissions(List.of(perm));

        RoleDefinitionDTO role = new RoleDefinitionDTO();
        role.setCode("role.x");
        role.setPermissions(List.of("perm.local"));
        m.setRoles(List.of(role));

        List<String> errors = service.validateManifest(m);

        assertThat(errors).noneMatch(e -> e.contains("perm.local"));
    }

    @Test
    @DisplayName("validateManifest flags menu referencing missing parent and missing permission")
    void validateManifest_menuRefsMissingParentAndPermission() {
        PluginManifestExtended m = baseManifest();
        MenuDefinitionDTO menu = new MenuDefinitionDTO();
        menu.setCode("menu.a");
        menu.setParentCode("menu.parent.missing");
        menu.setPermissionCode("perm.missing");
        m.setMenus(List.of(menu));

        when(resourceImporter.checkMenuExists(eq(1L), eq("menu.parent.missing"))).thenReturn(false);
        when(resourceImporter.checkPermissionExists(eq(1L), eq("perm.missing"))).thenReturn(false);

        List<String> errors = service.validateManifest(m);

        assertThat(errors).anyMatch(e -> e.contains("missing parent menu: menu.parent.missing"));
        assertThat(errors).anyMatch(e -> e.contains("missing permission: perm.missing"));
    }

    // ---------- checkConflicts branches ----------

    @Test
    @DisplayName("checkConflicts returns empty when manifest is null")
    void checkConflicts_nullManifest() {
        List<ImportPreviewResult.ResourceConflict> conflicts = service.checkConflicts(null);
        assertThat(conflicts).isEmpty();
    }

    @Test
    @DisplayName("checkConflicts returns empty when no tenant in MetaContext")
    void checkConflicts_noTenant() {
        MetaContext.clear();
        List<ImportPreviewResult.ResourceConflict> conflicts = service.checkConflicts(baseManifest());
        assertThat(conflicts).isEmpty();
    }

    @Test
    @DisplayName("checkConflicts skips own-plugin ownership and emits different_plugin for foreign owner")
    void checkConflicts_differentPluginOwner() {
        PluginManifestExtended m = baseManifest();
        ModelDefinitionDTO model = new ModelDefinitionDTO();
        model.setCode("crm_lead");
        m.setModels(List.of(model));

        PluginResource existing = new PluginResource();
        existing.setPluginPid("pp-foreign");
        when(pluginResourceMapper.findByTypeAndCode(eq(1L), eq("MODEL"), eq("crm_lead")))
                .thenReturn(existing);

        PluginRecord owner = new PluginRecord();
        owner.setPluginId("com.other");
        when(pluginRecordMapper.findByPid("pp-foreign")).thenReturn(owner);

        List<ImportPreviewResult.ResourceConflict> conflicts = service.checkConflicts(m);

        assertThat(conflicts).hasSize(1);
        ImportPreviewResult.ResourceConflict c = conflicts.get(0);
        assertThat(c.getResourceType()).isEqualTo(ResourceType.MODEL);
        assertThat(c.getResourceCode()).isEqualTo("crm_lead");
        assertThat(c.getConflictType()).isEqualTo("different_plugin");
        assertThat(c.getOwnerPluginId()).isEqualTo("com.other");
    }

    @Test
    @DisplayName("checkConflicts skips when existing resource owner matches importing plugin")
    void checkConflicts_sameOwnerSkipped() {
        PluginManifestExtended m = baseManifest();
        ModelDefinitionDTO model = new ModelDefinitionDTO();
        model.setCode("crm_lead");
        m.setModels(List.of(model));

        PluginResource existing = new PluginResource();
        existing.setPluginPid("pp-same");
        when(pluginResourceMapper.findByTypeAndCode(eq(1L), eq("MODEL"), eq("crm_lead")))
                .thenReturn(existing);

        PluginRecord owner = new PluginRecord();
        owner.setPluginId("com.demo"); // matches manifest pluginId
        when(pluginRecordMapper.findByPid("pp-same")).thenReturn(owner);

        List<ImportPreviewResult.ResourceConflict> conflicts = service.checkConflicts(m);

        assertThat(conflicts).isEmpty();
    }

    @Test
    @DisplayName("checkConflicts swallows mapper lookup errors and continues")
    void checkConflicts_lookupErrorIsBestEffort() {
        PluginManifestExtended m = baseManifest();
        ModelDefinitionDTO model = new ModelDefinitionDTO();
        model.setCode("crm_lead");
        m.setModels(List.of(model));

        when(pluginResourceMapper.findByTypeAndCode(eq(1L), eq("MODEL"), eq("crm_lead")))
                .thenThrow(new RuntimeException("duplicate row"));

        List<ImportPreviewResult.ResourceConflict> conflicts = service.checkConflicts(m);

        assertThat(conflicts).isEmpty();
    }

    @Test
    @DisplayName("checkConflicts ignores entries with blank code")
    void checkConflicts_blankCodeIgnored() {
        PluginManifestExtended m = baseManifest();
        ModelDefinitionDTO model = new ModelDefinitionDTO();
        model.setCode("   ");
        m.setModels(List.of(model));

        List<ImportPreviewResult.ResourceConflict> conflicts = service.checkConflicts(m);

        assertThat(conflicts).isEmpty();
        verify(pluginResourceMapper, never()).findByTypeAndCode(anyLong(), anyString(), anyString());
    }

    // ---------- analyzeDependencies branches ----------

    @Test
    @DisplayName("analyzeDependencies reports missing when dep plugin record absent")
    void analyzeDependencies_missingPlugin() {
        PluginManifestExtended m = baseManifest();
        m.setDependencySpecs(List.of(
                new PluginManifest.PluginDependencySpec("com.foo", ">=1.0.0")));

        when(pluginRecordMapper.findByTenantAndPluginId("com.foo")).thenReturn(null);

        ImportPreviewResult.DependencyAnalysis analysis = service.analyzeDependencies(m);

        assertThat(analysis.isSatisfied()).isFalse();
        assertThat(analysis.getMissingDependencies()).anyMatch(s -> s.contains("com.foo"));
        assertThat(analysis.getPluginDependencies()).hasSize(1);
        assertThat(analysis.getPluginDependencies().get(0).isSatisfied()).isFalse();
    }

    @Test
    @DisplayName("analyzeDependencies reports satisfied when version range matches installed")
    void analyzeDependencies_satisfied() {
        PluginManifestExtended m = baseManifest();
        m.setDependencySpecs(List.of(
                new PluginManifest.PluginDependencySpec("com.foo", "*")));

        PluginRecord installed = new PluginRecord();
        installed.setPluginId("com.foo");
        installed.setVersion("2.3.4");
        when(pluginRecordMapper.findByTenantAndPluginId("com.foo")).thenReturn(installed);

        ImportPreviewResult.DependencyAnalysis analysis = service.analyzeDependencies(m);

        assertThat(analysis.isSatisfied()).isTrue();
        assertThat(analysis.getMissingDependencies()).isEmpty();
        assertThat(analysis.getPluginDependencies()).hasSize(1);
        ImportPreviewResult.PluginDependency dep = analysis.getPluginDependencies().get(0);
        assertThat(dep.isSatisfied()).isTrue();
        assertThat(dep.getInstalledVersion()).isEqualTo("2.3.4");
    }

    @Test
    @DisplayName("analyzeDependencies reports version mismatch when installed version fails range")
    void analyzeDependencies_versionMismatch() {
        PluginManifestExtended m = baseManifest();
        m.setDependencySpecs(List.of(
                new PluginManifest.PluginDependencySpec("com.foo", ">=2.0.0")));

        PluginRecord installed = new PluginRecord();
        installed.setPluginId("com.foo");
        installed.setVersion("1.0.0");
        when(pluginRecordMapper.findByTenantAndPluginId("com.foo")).thenReturn(installed);

        ImportPreviewResult.DependencyAnalysis analysis = service.analyzeDependencies(m);

        assertThat(analysis.isSatisfied()).isFalse();
        assertThat(analysis.getMissingDependencies()).anyMatch(s -> s.contains("requires >=2.0.0"));
        assertThat(analysis.getPluginDependencies().get(0).isSatisfied()).isFalse();
    }

    @Test
    @DisplayName("analyzeDependencies returns empty satisfied=true when no dependencies declared")
    void analyzeDependencies_empty() {
        ImportPreviewResult.DependencyAnalysis analysis = service.analyzeDependencies(baseManifest());

        assertThat(analysis.isSatisfied()).isTrue();
        assertThat(analysis.getPluginDependencies()).isEmpty();
        assertThat(analysis.getMissingDependencies()).isEmpty();
    }

    // ---------- rollback orchestration ----------

    @Test
    @DisplayName("rollback throws PluginException when history not found")
    void rollback_historyNotFound() {
        when(importHistoryMapper.findByImportId("missing")).thenReturn(null);

        assertThatThrownBy(() -> service.rollback("missing"))
                .isInstanceOf(PluginException.class)
                .hasMessageContaining("Import not found");
    }

    @Test
    @DisplayName("rollback throws PluginException when history status is not SUCCESS")
    void rollback_statusNotSuccess() {
        PluginImportHistory h = new PluginImportHistory();
        h.setImportId("imp-1");
        h.setStatus("failed");
        when(importHistoryMapper.findByImportId("imp-1")).thenReturn(h);

        assertThatThrownBy(() -> service.rollback("imp-1"))
                .isInstanceOf(PluginException.class)
                .hasMessageContaining("Can only rollback successful imports");
    }

    @Test
    @DisplayName("rollback success path deletes created, restores updated, soft-deletes plugin for install")
    void rollback_successInstallPath() {
        PluginImportHistory h = new PluginImportHistory();
        h.setImportId("imp-1");
        h.setStatus("success");
        h.setPluginPid("pp-1");
        h.setPluginId("com.demo");
        h.setNamespace("demo");
        h.setVersion("1.0.0");
        h.setImportType("install");
        when(importHistoryMapper.findByImportId("imp-1")).thenReturn(h);

        PluginResource created = new PluginResource();
        PluginResource updated = new PluginResource();
        when(pluginResourceMapper.findCreatedResourcesForRollback("pp-1"))
                .thenReturn(List.of(created));
        when(pluginResourceMapper.findUpdatedResourcesForRollback("pp-1"))
                .thenReturn(List.of(updated));

        var result = service.rollback("imp-1");

        assertThat(result.isSuccess()).isTrue();
        verify(resourceImporter).rollbackResource(created);
        verify(resourceImporter).restoreResource(updated);
        verify(pluginResourceMapper).deleteByPluginPid("pp-1");
        verify(pluginRecordMapper).softDelete("pp-1");
        verify(importHistoryMapper).updateStatus(eq("imp-1"), eq("rolled_back"));
    }

    @Test
    @DisplayName("rollback non-install type does not soft-delete the plugin record")
    void rollback_nonInstallSkipsSoftDelete() {
        PluginImportHistory h = new PluginImportHistory();
        h.setImportId("imp-1");
        h.setStatus("success");
        h.setPluginPid("pp-1");
        h.setImportType("upgrade");
        when(importHistoryMapper.findByImportId("imp-1")).thenReturn(h);
        when(pluginResourceMapper.findCreatedResourcesForRollback("pp-1")).thenReturn(List.of());
        when(pluginResourceMapper.findUpdatedResourcesForRollback("pp-1")).thenReturn(List.of());

        service.rollback("imp-1");

        verify(pluginRecordMapper, never()).softDelete(anyString());
        verify(importHistoryMapper).updateStatus(eq("imp-1"), eq("rolled_back"));
    }

    @Test
    @DisplayName("rollback wraps mapper failure in PluginException")
    void rollback_mapperFailureWrapped() {
        PluginImportHistory h = new PluginImportHistory();
        h.setImportId("imp-1");
        h.setStatus("success");
        h.setPluginPid("pp-1");
        h.setImportType("install");
        when(importHistoryMapper.findByImportId("imp-1")).thenReturn(h);
        when(pluginResourceMapper.findCreatedResourcesForRollback("pp-1"))
                .thenThrow(new RuntimeException("DB down"));

        assertThatThrownBy(() -> service.rollback("imp-1"))
                .isInstanceOf(PluginException.class)
                .hasMessageContaining("Rollback failed");
    }

    // ---------- history listing ----------

    @Test
    @DisplayName("getImportHistory maps PluginImportHistory rows to DTOs")
    void getImportHistory_mapsDtos() {
        PluginImportHistory h = new PluginImportHistory();
        h.setImportId("imp-1");
        h.setPluginPid("pp-1");
        h.setPluginId("com.demo");
        h.setNamespace("demo");
        h.setVersion("1.0.0");
        h.setStatus("success");
        h.setImportType("install");
        h.setSourceType("json");
        h.setSourceName("plugin.json");
        h.setStartedAt(Instant.parse("2026-05-01T00:00:00Z"));
        h.setCompletedAt(Instant.parse("2026-05-01T00:01:00Z"));
        Map<String, Object> summary = new HashMap<>();
        summary.put("models", 3);
        summary.put("nonNumeric", "skip-me");
        h.setResourceSummary(summary);

        when(importHistoryMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(h));

        List<ImportHistoryDTO> dtos = service.getImportHistory(20);

        assertThat(dtos).hasSize(1);
        ImportHistoryDTO dto = dtos.get(0);
        assertThat(dto.importId()).isEqualTo("imp-1");
        assertThat(dto.resourceCounts()).containsEntry("models", 3);
        assertThat(dto.resourceCounts()).doesNotContainKey("nonNumeric");
    }

    @Test
    @DisplayName("getPluginImportHistory delegates to mapper with tenant + pluginId")
    void getPluginImportHistory_delegates() {
        PluginImportHistory h = new PluginImportHistory();
        h.setImportId("imp-2");
        h.setPluginId("com.demo");
        h.setStatus("failed");
        when(importHistoryMapper.findByTenantAndPluginId(eq(1L), eq("com.demo")))
                .thenReturn(List.of(h));

        List<ImportHistoryDTO> dtos = service.getPluginImportHistory("com.demo");

        assertThat(dtos).hasSize(1);
        assertThat(dtos.get(0).status()).isEqualTo("failed");
        verify(importHistoryMapper, times(1)).findByTenantAndPluginId(eq(1L), eq("com.demo"));
    }

    @Test
    @DisplayName("getImportHistory with null resourceSummary yields empty counts map")
    void getImportHistory_nullSummary() {
        PluginImportHistory h = new PluginImportHistory();
        h.setImportId("imp-3");
        h.setStatus("success");
        // resourceSummary intentionally null
        when(importHistoryMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(h));

        List<ImportHistoryDTO> dtos = service.getImportHistory(5);

        assertThat(dtos).hasSize(1);
        assertThat(dtos.get(0).resourceCounts()).isEmpty();
    }

    // ---------- preview / executeFromManifest validation paths ----------

    @Test
    @DisplayName("executeFromManifest aborts when manifest validation fails (missing required)")
    void executeFromManifest_validationFails() {
        PluginManifestExtended bad = new PluginManifestExtended();
        // pluginId/namespace/version blank — validation must fail before any lock acquisition

        assertThatThrownBy(() -> service.executeFromManifest(bad,
                new com.auraboot.framework.plugin.dto.imports.ImportRequest()))
                .isInstanceOfAny(PluginException.class, RootUnCheckedException.class)
                .hasMessageContaining("validation failed");
        verify(distributedLock, never()).tryLock(anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("preview throws when importId not in cache")
    void preview_notFound() {
        assertThatThrownBy(() ->
                service.preview("absent",
                        new com.auraboot.framework.plugin.dto.imports.ImportRequest()))
                .isInstanceOf(PluginException.class)
                .hasMessageContaining("Import not found");
    }

    @Test
    @DisplayName("getPreview returns null when context cache empty")
    void getPreview_returnsNullWhenAbsent() {
        assertThat(service.getPreview("absent")).isNull();
    }

    @Test
    @DisplayName("execute throws PluginException when context absent")
    void execute_notFound() {
        assertThatThrownBy(() -> service.execute("absent",
                new com.auraboot.framework.plugin.dto.imports.ImportRequest()))
                .isInstanceOf(PluginException.class)
                .hasMessageContaining("Import not found");
    }

    // ---------- parseSource branches ----------

    @Test
    @DisplayName("parseSource returns invalid result when source has no plugin.json")
    void parseSource_invalidSource() {
        PluginSource src = new PluginSource() {
            @Override public String getSourceId() { return "test-src"; }
            @Override public boolean exists(String relativePath) { return false; }
            @Override public java.io.InputStream readResource(String r) { return null; }
            @Override public String readString(String r) { return ""; }
            @Override public List<String> listFiles(String d, String e) { return List.of(); }
        };

        ImportPreviewResult result = service.parseSource(src);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("does not contain plugin.json"));
    }

    @Test
    @DisplayName("parseSource wraps directoryLoader PluginException as invalid result")
    void parseSource_loaderThrows() {
        PluginSource src = new PluginSource() {
            @Override public String getSourceId() { return "broken-src"; }
            @Override public boolean exists(String relativePath) { return true; }
            @Override public java.io.InputStream readResource(String r) { return null; }
            @Override public String readString(String r) { return ""; }
            @Override public List<String> listFiles(String d, String e) { return List.of(); }
        };
        when(directoryLoader.loadFromSource(src))
                .thenThrow(new PluginException("manifest unreadable"));

        ImportPreviewResult result = service.parseSource(src);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("Failed to load plugin from source"));
    }

    // ---------- canRollback positive ----------

    @Test
    @DisplayName("canRollback true when history exists with status SUCCESS")
    void canRollback_successTrue() {
        PluginImportHistory h = new PluginImportHistory();
        h.setStatus("success");
        when(importHistoryMapper.findByImportId("ok")).thenReturn(h);
        assertThat(service.canRollback("ok")).isTrue();
    }
}
