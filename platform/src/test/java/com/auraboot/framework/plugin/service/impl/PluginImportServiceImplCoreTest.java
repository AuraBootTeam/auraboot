package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.automation.dto.AutomationCreateRequest;
import com.auraboot.framework.automation.dto.AutomationDTO;
import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.entity.TriggerConfig;
import com.auraboot.framework.automation.service.AutomationService;
import com.auraboot.framework.bpm.rule.DroolsRuleService;
import com.auraboot.framework.bpm.service.SlaConfigService;
import com.auraboot.framework.decision.dto.DrtDefinitionCreateRequest;
import com.auraboot.framework.decision.dto.DrtDefinitionDTO;
import com.auraboot.framework.decision.dto.DrtVersionCreateRequest;
import com.auraboot.framework.decision.dto.DrtVersionDTO;
import com.auraboot.framework.decision.model.DecisionValidateResult;
import com.auraboot.framework.decision.service.DecisionVersionService;
import com.auraboot.framework.decision.service.DrtDefinitionService;
import com.auraboot.framework.decision.rule.DecisionBinding;
import com.auraboot.framework.decision.rule.RuleBindingKind;
import com.auraboot.framework.decision.rule.RuleConsumerBinding;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.i18n.compiler.I18nCompiler;
import com.auraboot.framework.i18n.entity.I18nResource;
import com.auraboot.framework.i18n.service.I18nResourceService;
import com.auraboot.framework.i18n.service.I18nService;
import com.auraboot.framework.lock.DistributedLock;
import com.auraboot.framework.menu.mapper.MenuMapper;
import com.auraboot.framework.meta.service.CommandService;
import com.auraboot.framework.meta.service.MetaFieldService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.SchemaManagementService;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.auraboot.framework.meta.template.generator.DocumentCommandGenerator;
import com.auraboot.framework.permission.service.AutoPermissionAssignmentService;
import com.auraboot.framework.permission.service.PermissionService;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.plugin.config.PlatformProperties;
import com.auraboot.framework.plugin.dto.PluginManifest;
import com.auraboot.framework.plugin.dto.imports.AutomationDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.BindingRuleDTO;
import com.auraboot.framework.plugin.dto.imports.DecisionDefinitionSeedDTO;
import com.auraboot.framework.plugin.dto.imports.ImportPreviewResult;
import com.auraboot.framework.plugin.dto.imports.ImportExecuteResult;
import com.auraboot.framework.plugin.dto.imports.MenuDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ModelDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.PermissionDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import com.auraboot.framework.plugin.dto.imports.SavedViewDefinitionDTO;
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
import com.auraboot.framework.view.entity.SavedView;
import com.auraboot.framework.view.entity.ViewConfig;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.mockito.ArgumentCaptor;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
    @Mock private I18nService i18nService;
    @Mock private I18nCompiler i18nCompiler;
    @Mock private PlatformProperties platformProperties;
    @Mock private PlatformVersionChecker platformVersionChecker;
    @Mock private PluginValidationPipeline validationPipeline;
    @Mock private PluginQualityScorer qualityScorer;
    @Mock private com.auraboot.framework.plugin.validation.PageSchemaImportGate pageSchemaImportGate;
    @Mock private SavedViewMapper savedViewMapper;
    @Mock private PageSchemaMapper pageSchemaMapper;
    @Mock private AutoPermissionAssignmentService autoPermissionAssignmentService;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private DocumentCommandGenerator documentCommandGenerator;
    @Mock private DroolsRuleService droolsRuleService;
    @Mock private SlaConfigService slaConfigService;
    @Mock private AutomationService automationService;
    @Mock private DrtDefinitionService drtDefinitionService;
    @Mock private DecisionVersionService decisionVersionService;
    @Mock private JdbcTemplate jdbcTemplate;

    @InjectMocks private PluginImportServiceImpl service;

    @BeforeEach
    void setUpContext() {
        if (MetaContext.exists()) {
            MetaContext.clear();
        }
        MetaContext.setContext(1L, 100L, "U-1", "tester");
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

    private String invokeCreateOrUpdatePlugin(PluginManifestExtended manifest, Long tenantId) {
        try {
            Method method = PluginImportServiceImpl.class.getDeclaredMethod(
                    "createOrUpdatePlugin", PluginManifestExtended.class, Long.class);
            method.setAccessible(true);
            return (String) method.invoke(service, manifest, tenantId);
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
    @DisplayName("ZIP resourceDirs loads decisionDefinitions from declared JSON files")
    void zipResourceDirs_loadsDecisionDefinitions() {
        PluginManifestExtended m = baseManifest();
        m.setResourceDirs(Map.of("decisionDefinitions", "config/decisions"));
        Map<String, byte[]> files = Map.of(
                "config/decisions/rule-center.json",
                """
                [
                  {
                    "decisionCode": "approval_routing",
                    "decisionName": "审批路由",
                    "scopeType": "BPM",
                    "ownerModule": "bpm",
                    "kind": "SIMPLE_CONDITION",
                    "runtimeAdapter": "AST_EVALUATOR",
                    "contentJson": {
                      "condition": { "type": "group", "op": "AND", "children": [] },
                      "outputs": { "primaryAssignee": "manager" }
                    }
                  }
                ]
                """.getBytes(StandardCharsets.UTF_8)
        );

        invokeLoadResourcesFromZip(m, files);

        assertThat(m.getDecisionDefinitions())
                .extracting(DecisionDefinitionSeedDTO::getDecisionCode)
                .containsExactly("approval_routing");
        assertThat(m.getDecisionDefinitions().get(0).getContentJson()
                .at("/outputs/primaryAssignee").asText()).isEqualTo("manager");
    }

    @Test
    @DisplayName("importDecisionDefinitions creates validates and publishes DRT seed versions")
    void importDecisionDefinitionsCreatesValidatesAndPublishes() throws Exception {
        JsonNode content = new ObjectMapper().readTree("""
                {
                  "condition": { "type": "group", "op": "AND", "children": [] },
                  "outputs": { "deadlineMinutes": 30 }
                }
                """);
        DecisionDefinitionSeedDTO seed = DecisionDefinitionSeedDTO.builder()
                .decisionCode("complaint_sla_deadline")
                .decisionName("投诉 SLA 截止时间")
                .scopeType("SLA")
                .ownerModule("bpm")
                .kind("SIMPLE_CONDITION")
                .runtimeAdapter("AST_EVALUATOR")
                .versionTag("seed-v1")
                .contentJson(content)
                .publish(true)
                .build();
        PluginManifestExtended manifest = baseManifest();
        manifest.setDecisionDefinitions(List.of(seed));

        DrtDefinitionDTO createdDefinition = new DrtDefinitionDTO();
        createdDefinition.setPid("def-pid");
        createdDefinition.setDecisionCode("complaint_sla_deadline");
        when(drtDefinitionService.findByCode("complaint_sla_deadline")).thenReturn(null);
        when(drtDefinitionService.create(any(DrtDefinitionCreateRequest.class))).thenReturn(createdDefinition);

        DrtVersionDTO draft = new DrtVersionDTO();
        draft.setPid("ver-pid");
        when(decisionVersionService.createDraft(eq("complaint_sla_deadline"), any(DrtVersionCreateRequest.class)))
                .thenReturn(draft);
        when(decisionVersionService.validate("ver-pid"))
                .thenReturn(DecisionValidateResult.ok(List.of("process.taskKey"), List.of()));

        invokeImportDecisionDefinitions(manifest);

        ArgumentCaptor<DrtDefinitionCreateRequest> definitionCaptor =
                ArgumentCaptor.forClass(DrtDefinitionCreateRequest.class);
        verify(drtDefinitionService).create(definitionCaptor.capture());
        assertThat(definitionCaptor.getValue().getDecisionCode()).isEqualTo("complaint_sla_deadline");
        assertThat(definitionCaptor.getValue().getScopeType()).isEqualTo("SLA");

        ArgumentCaptor<DrtVersionCreateRequest> versionCaptor =
                ArgumentCaptor.forClass(DrtVersionCreateRequest.class);
        verify(decisionVersionService).createDraft(eq("complaint_sla_deadline"), versionCaptor.capture());
        assertThat(versionCaptor.getValue().getKind()).isEqualTo("SIMPLE_CONDITION");
        assertThat(versionCaptor.getValue().getRuntimeAdapter()).isEqualTo("AST_EVALUATOR");
        assertThat(versionCaptor.getValue().getContentJson().at("/outputs/deadlineMinutes").asInt()).isEqualTo(30);
        verify(decisionVersionService).publish("ver-pid", true);
    }

    @Test
    @DisplayName("importDecisionDefinitions skips draft creation when published seed content is unchanged")
    void importDecisionDefinitionsSkipsUnchangedPublishedVersion() throws Exception {
        JsonNode content = new ObjectMapper().readTree("""
                {
                  "hitPolicy": "FIRST",
                  "inputs": [
                    {
                      "id": "targetKey",
                      "expr": { "type": "path", "scope": "record", "path": "data.targetKey", "dataType": "string" }
                    }
                  ],
                  "outputs": [
                    { "id": "deadlineMinutes", "dataType": "integer" }
                  ],
                  "rules": [
                    {
                      "ruleId": "node-sla",
                      "when": { "targetKey": { "operator": "EQ", "value": "task_manager_approve" } },
                      "then": { "deadlineMinutes": 30 }
                    }
                  ]
                }
                """);
        DecisionDefinitionSeedDTO seed = DecisionDefinitionSeedDTO.builder()
                .decisionCode("complaint_sla_deadline")
                .decisionName("投诉 SLA 截止时间")
                .scopeType("SLA")
                .ownerModule("bpm")
                .kind("DECISION_TABLE")
                .runtimeAdapter("PLATFORM_DECISION_TABLE")
                .versionTag("seed-v1")
                .contentJson(content)
                .publish(true)
                .build();
        PluginManifestExtended manifest = baseManifest();
        manifest.setDecisionDefinitions(List.of(seed));

        DrtDefinitionDTO existingDefinition = new DrtDefinitionDTO();
        existingDefinition.setPid("def-pid");
        existingDefinition.setDecisionCode("complaint_sla_deadline");
        when(drtDefinitionService.findByCode("complaint_sla_deadline")).thenReturn(existingDefinition);

        DrtVersionDTO published = new DrtVersionDTO();
        published.setPid("published-pid");
        published.setStatus("PUBLISHED");
        published.setKind("DECISION_TABLE");
        published.setRuntimeAdapter("PLATFORM_DECISION_TABLE");
        published.setContentJson(content);
        when(decisionVersionService.listByCode("complaint_sla_deadline")).thenReturn(List.of(published));

        invokeImportDecisionDefinitions(manifest);

        verify(drtDefinitionService).update(eq("def-pid"), any(DrtDefinitionCreateRequest.class));
        verify(decisionVersionService, never()).createDraft(anyString(), any(DrtVersionCreateRequest.class));
        verify(decisionVersionService, never()).publish(anyString(), anyBoolean());
    }

    @Test
    @DisplayName("importAutomations creates Automation seed with Rule Center binding")
    void importAutomationsCreatesRuleCenterBoundAutomation() {
        TriggerConfig triggerConfig = new TriggerConfig();
        triggerConfig.setModelCode("wd_leave_request");
        triggerConfig.setRuleBinding(new RuleConsumerBinding(
                "AUTOMATION",
                "wd_leave_high_value_notify",
                "trigger",
                RuleBindingKind.DECISION_REF,
                null,
                new DecisionBinding(
                        "leave_request_automation",
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(),
                        DecisionBinding.FallbackPolicy.failClosed(),
                        300,
                        DecisionBinding.TraceMode.ALWAYS,
                        true,
                        null,
                        null),
                true));
        AutomationDefinitionDTO seed = AutomationDefinitionDTO.builder()
                .automationKey("wd_leave_high_value_notify")
                .name("长假申请提醒")
                .description("Rule Center bound automation seed")
                .modelCode("wd_leave_request")
                .triggerType("on_record_create")
                .triggerConfig(triggerConfig)
                .triggerCondition("#decision['outputs']['actionType'] == 'send_notification'")
                .actions(List.of(AutomationAction.builder()
                        .type("send_notification")
                        .label("Notify manager")
                        .sequence(0)
                        .continueOnError(true)
                        .config(Map.of("type", "in_app", "title", "长假申请提醒", "recipients", List.of()))
                        .build()))
                .enabled(true)
                .build();
        PluginManifestExtended manifest = baseManifest();
        manifest.setAutomations(List.of(seed));
        when(automationService.create(any(AutomationCreateRequest.class)))
                .thenReturn(AutomationDTO.builder()
                        .pid("auto-pid")
                        .name("长假申请提醒")
                        .enabled(true)
                        .build());

        invokeImportAutomations(manifest);

        ArgumentCaptor<AutomationCreateRequest> requestCaptor =
                ArgumentCaptor.forClass(AutomationCreateRequest.class);
        verify(automationService).create(requestCaptor.capture());
        AutomationCreateRequest request = requestCaptor.getValue();
        assertThat(request.getName()).isEqualTo("长假申请提醒");
        assertThat(request.getModelCode()).isEqualTo("wd_leave_request");
        assertThat(request.getTriggerType()).isEqualTo("on_record_create");
        assertThat(request.getTriggerCondition())
                .isEqualTo("#decision['outputs']['actionType'] == 'send_notification'");
        assertThat(request.getActions()).hasSize(1);
        assertThat(request.getEnabled()).isTrue();
        assertThat(request.getTriggerConfig().getRuleBinding().consumerType()).isEqualTo("AUTOMATION");
        assertThat(request.getTriggerConfig().getRuleBinding().decisionBinding().decisionCode())
                .isEqualTo("leave_request_automation");
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

    // ---------- two-phase cross-plugin reference validation ----------

    /**
     * Reproduces the cold-reset cyclic-dependency bug: crm declares a command on
     * {@code sl_sales_quotation} (owned by sales) while sales depends on crm. In a
     * per-plugin import loop neither order satisfies the other side, so the default
     * "command references missing model" hard error rolls back the import.
     *
     * <p>Default mode (no deferral) MUST keep the hard error — this is the regression
     * guard proving the fix did not silently weaken validation.
     */
    @Test
    @DisplayName("validateManifest: cross-plugin command->model is a HARD error by default")
    void validateManifest_crossPluginCommandModel_hardErrorByDefault() {
        PluginManifestExtended m = baseManifest();
        m.setPluginId("com.auraboot.crm");
        m.setNamespace("crm");
        com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO cmd =
                new com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO();
        cmd.setCode("crm:convert_quotation_to_order");
        cmd.setModelCode("sl_sales_quotation"); // owned by sales, not yet imported
        cmd.setType("custom");
        m.setCommands(List.of(cmd));

        when(resourceImporter.checkModelExists(eq(1L), eq("sl_sales_quotation"))).thenReturn(false);

        List<String> errors = service.validateManifest(m);

        assertThat(errors).anyMatch(e ->
                e.contains("crm:convert_quotation_to_order") && e.contains("sl_sales_quotation"));
    }

    /**
     * Assertion 1 (cycle imports): with {@code deferReferenceValidation=true}, the same
     * cross-plugin command->model reference is downgraded to a deferred warning, so the
     * plugin is valid and the per-plugin import proceeds. Both sides of a crm↔sales cycle
     * can therefore import.
     */
    @Test
    @DisplayName("validateManifest: cross-plugin command->model is DEFERRED (no error) when deferral on")
    void validateManifest_crossPluginCommandModel_deferred() {
        PluginManifestExtended m = baseManifest();
        m.setPluginId("com.auraboot.crm");
        m.setNamespace("crm");
        com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO cmd =
                new com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO();
        cmd.setCode("crm:convert_quotation_to_order");
        cmd.setModelCode("sl_sales_quotation");
        cmd.setType("custom");
        m.setCommands(List.of(cmd));

        when(resourceImporter.checkModelExists(eq(1L), eq("sl_sales_quotation"))).thenReturn(false);

        List<String> messages = service.validateManifest(m, true);

        // No hard error → preview stays valid → import proceeds
        assertThat(messages).noneMatch(e ->
                e.contains("references missing model") && !e.startsWith("[WARN] "));
        // Deferred reference is surfaced as a [WARN] for observability
        assertThat(messages).anyMatch(e ->
                e.startsWith("[WARN] ") && e.contains("sl_sales_quotation"));
    }

    /**
     * Deferral must NOT mask an intra-manifest typo: a command referencing a model the
     * plugin itself declares (present in manifest) never errors regardless of mode; and a
     * command referencing a model already installed in the tenant resolves cleanly.
     */
    @Test
    @DisplayName("validateManifest: command->model satisfied by manifest or DB never errors (deferral on)")
    void validateManifest_commandModelSatisfied_noErrorWhenDeferred() {
        PluginManifestExtended m = baseManifest();
        ModelDefinitionDTO local = new ModelDefinitionDTO();
        local.setCode("crm_lead");
        local.setModelType("entity");
        m.setModels(List.of(local));

        com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO localCmd =
                new com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO();
        localCmd.setCode("crm:create_lead");
        localCmd.setModelCode("crm_lead");
        localCmd.setType("custom");
        com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO installedCmd =
                new com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO();
        installedCmd.setCode("crm:touch_account");
        installedCmd.setModelCode("crm_account_installed");
        installedCmd.setType("custom");
        m.setCommands(List.of(localCmd, installedCmd));

        when(resourceImporter.checkModelExists(eq(1L), eq("crm_account_installed"))).thenReturn(true);

        List<String> messages = service.validateManifest(m, true);

        // Scope to the command->model reference check (the deferral feature's concern). A bare
        // contains("crm_lead") would also catch the orthogonal "entity model requires a field
        // binding" structural rule, which legitimately fires for this minimal fixture and is not
        // what deferral governs.
        assertThat(messages).noneMatch(e -> e.contains("references missing model") && e.contains("crm_lead"));
        assertThat(messages).noneMatch(e ->
                e.contains("references missing model") && e.contains("crm_account_installed"));
    }

    /**
     * Assertion 2 (dangling still fails): the closing reference-integrity sweep flags a
     * command whose modelCode is provided by no plugin at all, even though the per-plugin
     * import deferred it. This is the pure decision core of the sweep.
     */
    @Test
    @DisplayName("findDanglingCommandModelRefs flags command->model that no model provides")
    void closingSweep_flagsDanglingCommandModelRef() {
        com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO ok =
                new com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO();
        ok.setCode("crm:convert_quotation_to_order");
        ok.setModelCode("sl_sales_quotation"); // provided after full batch
        com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO dangling =
                new com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO();
        dangling.setCode("crm:typo_command");
        dangling.setModelCode("sl_sales_quotaton"); // typo — provided by nobody

        List<String> danglingMsgs = service.findDanglingCommandModelRefs(
                List.of(ok, dangling),
                java.util.Set.of("sl_sales_quotation", "sl_sales_order", "crm_lead"));

        assertThat(danglingMsgs).anyMatch(s ->
                s.contains("crm:typo_command") && s.contains("sl_sales_quotaton"));
        assertThat(danglingMsgs).noneMatch(s -> s.contains("crm:convert_quotation_to_order"));
    }

    @Test
    @DisplayName("findDanglingCommandModelRefs returns empty when all references resolve")
    void closingSweep_emptyWhenAllResolve() {
        com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO a =
                new com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO();
        a.setCode("crm:c1");
        a.setModelCode("crm_lead");
        com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO b =
                new com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO();
        b.setCode("sl:c2");
        b.setModelCode("sl_sales_order");

        List<String> danglingMsgs = service.findDanglingCommandModelRefs(
                List.of(a, b),
                java.util.Set.of("crm_lead", "sl_sales_order"));

        assertThat(danglingMsgs).isEmpty();
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

    // ---------- plugin record namespace ownership ----------

    @Test
    @DisplayName("createOrUpdatePlugin rejects namespace reuse by a different plugin")
    void createOrUpdatePlugin_rejectsNamespaceCollision() {
        PluginManifestExtended manifest = baseManifest();
        manifest.setPluginId("com.auraboot.pcba-base");
        manifest.setNamespace("pcba");

        PluginRecord namespaceOwner = new PluginRecord();
        namespaceOwner.setPid("owner-pid");
        namespaceOwner.setPluginId("com.auraboot.pcba-industry");
        namespaceOwner.setNamespace("pcba");

        when(pluginRecordMapper.findByTenantAndPluginId("com.auraboot.pcba-base")).thenReturn(null);
        when(pluginRecordMapper.findByTenantAndNamespace("pcba")).thenReturn(namespaceOwner);

        assertThatThrownBy(() -> invokeCreateOrUpdatePlugin(manifest, 100L))
                .isInstanceOf(PluginException.class)
                .hasMessageContaining("namespace 'pcba'")
                .hasMessageContaining("com.auraboot.pcba-industry");

        verify(pluginRecordMapper, never()).updateById(any(PluginRecord.class));
        verify(pluginRecordMapper, never()).insert(any(PluginRecord.class));
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
    @DisplayName("previewFromManifest requires tenant context before creating import history")
    void previewFromManifest_requiresTenantContext() {
        MetaContext.clear();

        assertThatThrownBy(() -> service.previewFromManifest(baseManifest()))
                .isInstanceOf(PluginException.class)
                .hasMessageContaining("Tenant context is required for plugin import");
        verify(importHistoryMapper, never()).insert(any(PluginImportHistory.class));
    }

    @Test
    @DisplayName("executeFromManifest requires tenant context before creating import history")
    void executeFromManifest_requiresTenantContext() {
        MetaContext.clear();

        assertThatThrownBy(() -> service.executeFromManifest(baseManifest(),
                new com.auraboot.framework.plugin.dto.imports.ImportRequest()))
                .isInstanceOf(PluginException.class)
                .hasMessageContaining("Tenant context is required for plugin import");
        verify(importHistoryMapper, never()).insert(any(PluginImportHistory.class));
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

    // ---------- permission i18n record generation ----------

    @Test
    @DisplayName("generatePermissionI18nRecords emits permission.{code} and .description records per locale")
    void generatePermissionI18nRecords_emitsRecords() {
        PermissionDefinitionDTO perm = PermissionDefinitionDTO.builder()
                .code("meta_management")
                .nameZhCN("元数据管理")
                .nameEn("Metadata Management")
                .localizedDescriptions(new java.util.LinkedHashMap<>(Map.of(
                        "zh-CN", "访问元数据管理模块",
                        "en-US", "Access the metadata management module")))
                .build();

        invokeGeneratePermissionI18nRecords(List.of(perm), 1L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<I18nResource>> captor = ArgumentCaptor.forClass(List.class);
        verify(i18nResourceService).batchUpsert(captor.capture());
        List<I18nResource> emitted = captor.getValue();

        // 2 name records (zh-CN/en-US) + 2 description records (zh-CN/en-US)
        assertThat(emitted).hasSize(4);
        assertThat(emitted).allSatisfy(r -> {
            assertThat(r.getRefType()).isEqualTo("permission");
            assertThat(r.getSource()).isEqualTo(I18nResource.SOURCE_IMPORT);
            assertThat(r.getStatus()).isEqualTo(I18nResource.STATUS_APPROVED);
        });
        assertThat(emitted).anySatisfy(r -> {
            assertThat(r.getI18nKey()).isEqualTo("permission.meta_management");
            assertThat(r.getLang()).isEqualTo("en-US");
            assertThat(r.getValue()).isEqualTo("Metadata Management");
        });
        assertThat(emitted).anySatisfy(r -> {
            assertThat(r.getI18nKey()).isEqualTo("permission.meta_management.description");
            assertThat(r.getLang()).isEqualTo("zh-CN");
            assertThat(r.getValue()).isEqualTo("访问元数据管理模块");
        });
    }

    @Test
    @DisplayName("plugin saved views are locked presets and update by stable viewKey")
    void importSavedViewsTagsPluginPresetAndUpdatesByViewKey() {
        PluginManifestExtended manifest = baseManifest();
        SavedViewDefinitionDTO dto = SavedViewDefinitionDTO.builder()
                .name("Pipeline Board")
                .description("Plugin baseline board")
                .modelCode("crm.opportunity")
                .pageKey("crm_opportunity_list")
                .viewType("table")
                .viewKey("crm.opportunity.pipeline")
                .viewConfig(Map.of("rowHeight", "medium"))
                .build();
        manifest.setSavedViews(List.of(dto));

        when(pageSchemaMapper.selectAnyByPageKey("crm_opportunity_list")).thenReturn(new PageSchema());
        ViewConfig existingConfig = new ViewConfig();
        existingConfig.setMeta(ViewConfig.Meta.builder()
                .viewKey("crm.opportunity.pipeline")
                .managedBy("plugin")
                .locked(true)
                .allowUserCopy(true)
                .build());
        SavedView existing = new SavedView();
        existing.setPid("existing-view");
        existing.setName("Old Plugin Board");
        existing.setViewType("table");
        existing.setViewConfig(existingConfig);
        when(savedViewMapper.findGlobalViews("crm.opportunity", "crm_opportunity_list"))
                .thenReturn(List.of(existing));

        invokeImportSavedViews(manifest, new ImportExecuteResult(), 1L);

        ArgumentCaptor<SavedView> captor = ArgumentCaptor.forClass(SavedView.class);
        verify(savedViewMapper).updateSavedView(captor.capture());
        SavedView updated = captor.getValue();
        assertThat(updated.getName()).isEqualTo("Pipeline Board");
        assertThat(updated.getViewConfig().getRowHeight()).isEqualTo("medium");
        assertThat(updated.getViewConfig().getMeta().getViewKey())
                .isEqualTo("crm.opportunity.pipeline");
        assertThat(updated.getViewConfig().getMeta().getManagedBy()).isEqualTo("plugin");
        assertThat(updated.getViewConfig().getMeta().getLocked()).isTrue();
        assertThat(updated.getViewConfig().getMeta().getAllowUserCopy()).isTrue();
    }

    private void invokeGeneratePermissionI18nRecords(List<PermissionDefinitionDTO> permissions, Long tenantId) {
        try {
            Method method = PluginImportServiceImpl.class.getDeclaredMethod(
                    "generatePermissionI18nRecords", List.class, Long.class);
            method.setAccessible(true);
            method.invoke(service, permissions, tenantId);
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

    private void invokeImportSavedViews(PluginManifestExtended manifest, ImportExecuteResult result, Long tenantId) {
        try {
            Method method = PluginImportServiceImpl.class.getDeclaredMethod(
                    "importSavedViews", PluginManifestExtended.class, ImportExecuteResult.class, Long.class);
            method.setAccessible(true);
            method.invoke(service, manifest, result, tenantId);
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

    private void invokeImportDecisionDefinitions(PluginManifestExtended manifest) {
        try {
            Method method = PluginImportServiceImpl.class.getDeclaredMethod(
                    "importDecisionDefinitions", PluginManifestExtended.class);
            method.setAccessible(true);
            method.invoke(service, manifest);
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

    private void invokeImportAutomations(PluginManifestExtended manifest) {
        try {
            Method method = PluginImportServiceImpl.class.getDeclaredMethod(
                    "importAutomations", PluginManifestExtended.class);
            method.setAccessible(true);
            method.invoke(service, manifest);
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
}
