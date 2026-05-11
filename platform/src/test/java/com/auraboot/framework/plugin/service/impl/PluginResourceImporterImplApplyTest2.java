package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.agent.entity.AgentDefinition;
import com.auraboot.framework.agent.mapper.AgentDefinitionMapper;
import com.auraboot.framework.dashboard.dto.DashboardCreateRequest;
import com.auraboot.framework.dashboard.dto.DashboardDTO;
import com.auraboot.framework.dashboard.dto.DashboardUpdateRequest;
import com.auraboot.framework.dashboard.service.DashboardService;
import com.auraboot.framework.environment.service.EnvironmentService;
import com.auraboot.framework.menu.mapper.MenuMapper;
import com.auraboot.framework.menu.service.MenuService;
import com.auraboot.framework.meta.converter.ExtensionConverter;
import com.auraboot.framework.meta.dto.CommandDefinitionCreateRequest;
import com.auraboot.framework.meta.dto.CommandDefinitionDTO;
import com.auraboot.framework.meta.dto.DictDTO;
import com.auraboot.framework.meta.dto.MetaFieldCreateRequest;
import com.auraboot.framework.meta.dto.MetaFieldDTO;
import com.auraboot.framework.meta.dto.MetaModelCreateRequest;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.dto.NamedQueryDTO;
import com.auraboot.framework.meta.dto.SchemaOperationResult;
import com.auraboot.framework.meta.entity.NamedQuery;
import com.auraboot.framework.meta.mapper.BindingRuleMapper;
import com.auraboot.framework.meta.mapper.CommandDefinitionMapper;
import com.auraboot.framework.meta.mapper.DictMapper;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.mapper.NamedQueryMapper;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.auraboot.framework.meta.service.CommandService;
import com.auraboot.framework.meta.service.DictService;
import com.auraboot.framework.meta.service.MetaFieldService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.ModelFieldBindingService;
import com.auraboot.framework.meta.service.NamedQueryService;
import com.auraboot.framework.meta.service.PageSchemaService;
import com.auraboot.framework.meta.service.SchemaManagementService;
import com.auraboot.framework.meta.service.impl.CommandMetadataCacheService;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.PermissionService;
import com.auraboot.framework.plugin.dto.imports.AgentDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.DashboardDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.FieldDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.dto.imports.ModelDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.NamedQueryDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ResourceAction;
import com.auraboot.framework.plugin.dto.imports.ResourceType;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.auraboot.framework.plugin.exception.PluginException;
import com.auraboot.framework.plugin.mapper.BpmProcessDefinitionMapper;
import com.auraboot.framework.plugin.mapper.PluginResourceMapper;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.auraboot.framework.rbac.service.RoleService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito tests covering CREATE/UPDATE/SKIP/ERROR branches for
 * {@link PluginResourceImporterImpl}'s remaining import methods that are not
 * exercised by the sibling {@code *ApplyTest} (which targets permission/role/
 * dict/bindingRule/menu).
 *
 * Targets: importModel, importField, importCommand, importPage,
 * importDashboard, importNamedQuery, importAgentDefinition.
 *
 * Strategy: ConflictStrategy.OVERWRITE so we run past the OVERWRITE_SAFE guard.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PluginResourceImporterImplApplyTest2 {

    @Mock private MetaModelService metaModelService;
    @Mock private MetaFieldService metaFieldService;
    @Mock private ModelFieldBindingService modelFieldBindingService;
    @Mock private SchemaManagementService schemaManagementService;
    @Mock private DictService dictService;
    @Mock private CommandService commandService;
    @Mock private PermissionService permissionService;
    @Mock private RoleService roleService;
    @Mock private MenuService menuService;
    @Mock private PageSchemaService pageSchemaService;
    @Mock private NamedQueryService namedQueryService;
    @Mock private DashboardService dashboardService;
    @Mock private EnvironmentService environmentService;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private MetaModelFieldBindingMapper fieldBindingMapper;
    @Mock private BpmProcessDefinitionMapper processDefinitionMapper;
    @Mock private RoleMapper roleMapper;
    @Mock private MenuMapper menuMapper;
    @Mock private ObjectMapper objectMapper;
    @Mock private MetaModelMapper metaModelMapper;
    @Mock private CommandDefinitionMapper commandDefinitionMapper;
    @Mock private BindingRuleMapper bindingRuleMapper;
    @Mock private PermissionMapper permissionMapper;
    @Mock private RolePermissionMapper rolePermissionMapper;
    @Mock private PageSchemaMapper pageSchemaMapper;
    @Mock private DictMapper dictMapper;
    @Mock private NamedQueryMapper namedQueryMapper;
    @Mock private AgentDefinitionMapper agentDefinitionMapper;
    @Mock private MetaFieldMapper metaFieldMapper;
    @Mock private ExtensionConverter extensionConverter;
    @Mock private PluginResourceMapper pluginResourceMapper;
    @Mock private CommandMetadataCacheService commandMetadataCache;
    @Mock private com.auraboot.framework.bpm.converter.JsonToBpmnConverter jsonToBpmnConverter;
    @Mock private com.auraboot.smart.framework.engine.SmartEngine smartEngine;

    @InjectMocks private PluginResourceImporterImpl importer;

    // ==================== importModel ====================

    @Test
    @DisplayName("importModel ERROR strategy: throws when model exists")
    void importModel_error_throws() {
        when(metaModelService.isModelExists("m1")).thenReturn(true);
        ModelDefinitionDTO dto = ModelDefinitionDTO.builder().code("m1").displayName("M1").build();

        assertThatThrownBy(() -> importer.importModel(dto, "plg", "imp", 1L,
                ImportRequest.ConflictStrategy.ERROR, false))
                .isInstanceOf(PluginException.class)
                .hasMessageContaining("Model already exists");
    }

    @Test
    @DisplayName("importModel SKIP strategy: returns SKIP record when model exists")
    void importModel_skip_returnsSkipRecord() {
        when(metaModelService.isModelExists("m1")).thenReturn(true);
        ModelDefinitionDTO dto = ModelDefinitionDTO.builder().code("m1").displayName("M1").build();

        PluginResource result = importer.importModel(dto, "plg", "imp", 1L,
                ImportRequest.ConflictStrategy.SKIP, false);

        assertThat(result.getAction()).isEqualTo(ResourceAction.SKIP.code());
        assertThat(result.getResourceType()).isEqualTo(ResourceType.MODEL.code());
        verify(metaModelService, never()).create(any());
    }

    @Test
    @DisplayName("importModel CREATE branch: no existing -> metaModelService.create")
    void importModel_create_happyPath() throws Exception {
        when(metaModelService.isModelExists("m1")).thenReturn(false);
        // resurrectSoftDeleted: queryForList returns empty list
        when(jdbcTemplate.queryForList(anyString(), eq("m1"), eq(1L))).thenReturn(List.of());
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        MetaModelDTO created = MetaModelDTO.builder().pid("model-pid-new").code("m1").build();
        when(metaModelService.create(any(MetaModelCreateRequest.class))).thenReturn(created);

        ModelDefinitionDTO dto = ModelDefinitionDTO.builder()
                .code("m1")
                .displayName("M1")
                .description("desc")
                .modelType("entity")
                .tableName("tbl_m1")
                .build();

        PluginResource result = importer.importModel(dto, "plg", "imp", 1L,
                ImportRequest.ConflictStrategy.OVERWRITE, false);

        assertThat(result.getAction()).isEqualTo(ResourceAction.CREATE.code());
        assertThat(result.getResourcePid()).isEqualTo("model-pid-new");

        ArgumentCaptor<MetaModelCreateRequest> captor = ArgumentCaptor.forClass(MetaModelCreateRequest.class);
        verify(metaModelService).create(captor.capture());
        assertThat(captor.getValue().getCode()).isEqualTo("m1");
        assertThat(captor.getValue().getPluginPid()).isEqualTo("plg");
        assertThat(captor.getValue().getTableName()).isEqualTo("tbl_m1");
    }

    @Test
    @DisplayName("importModel UPDATE branch: existing -> metaModelMapper.updateForPluginImport + clearAllCache")
    void importModel_update_happyPath() throws Exception {
        when(metaModelService.isModelExists("m1")).thenReturn(true);
        MetaModelDTO existing = MetaModelDTO.builder().pid("model-pid-exist").code("m1").build();
        when(metaModelService.findByCode("m1")).thenReturn(existing);
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"extension\":{}}");

        ModelDefinitionDTO dto = ModelDefinitionDTO.builder()
                .code("m1")
                .displayName("M1 Updated")
                .modelType("entity")
                .build();

        PluginResource result = importer.importModel(dto, "plg", "imp", 1L,
                ImportRequest.ConflictStrategy.OVERWRITE, false);

        assertThat(result.getAction()).isEqualTo(ResourceAction.UPDATE.code());
        assertThat(result.getResourcePid()).isEqualTo("model-pid-exist");
        verify(metaModelMapper).updateForPluginImport(anyString(), eq("plg"), any(), any(),
                any(), any(), any(), any(), eq(1L), eq("m1"));
        verify(metaModelService).clearAllCache();
        verify(metaModelService, never()).create(any());
    }

    @Test
    @DisplayName("importModel CREATE branch: soft-deleted resurrect -> action=CREATE, pid from jdbc row")
    void importModel_resurrect_path() throws Exception {
        when(metaModelService.isModelExists("m1")).thenReturn(false);
        when(jdbcTemplate.queryForList(anyString(), eq("m1"), eq(1L)))
                .thenReturn(List.of(java.util.Map.of("pid", "resurrect-pid")));
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        ModelDefinitionDTO dto = ModelDefinitionDTO.builder()
                .code("m1").displayName("M1").tableName("tbl1").build();

        PluginResource result = importer.importModel(dto, "plg", "imp", 1L,
                ImportRequest.ConflictStrategy.OVERWRITE, false);

        assertThat(result.getAction()).isEqualTo(ResourceAction.CREATE.code());
        assertThat(result.getResourcePid()).isEqualTo("resurrect-pid");
        verify(metaModelMapper).updateTableNameByPid("tbl1", "resurrect-pid");
        verify(metaModelService, never()).create(any());
    }

    // ==================== importField ====================

    @Test
    @DisplayName("importField ERROR strategy: throws when field exists")
    void importField_error_throws() {
        when(metaFieldService.isFieldExists("f1")).thenReturn(true);
        FieldDefinitionDTO dto = FieldDefinitionDTO.builder().code("f1").displayName("F1").dataType("string").build();

        assertThatThrownBy(() -> importer.importField(dto, "plg", "imp", 1L,
                ImportRequest.ConflictStrategy.ERROR, false))
                .isInstanceOf(PluginException.class)
                .hasMessageContaining("Field already exists");
    }

    @Test
    @DisplayName("importField SKIP strategy: returns SKIP record")
    void importField_skip_returnsSkipRecord() {
        when(metaFieldService.isFieldExists("f1")).thenReturn(true);
        FieldDefinitionDTO dto = FieldDefinitionDTO.builder().code("f1").displayName("F1").dataType("string").build();

        PluginResource result = importer.importField(dto, "plg", "imp", 1L,
                ImportRequest.ConflictStrategy.SKIP, false);

        assertThat(result.getAction()).isEqualTo(ResourceAction.SKIP.code());
        assertThat(result.getResourceType()).isEqualTo(ResourceType.FIELD.code());
    }

    @Test
    @DisplayName("importField CREATE branch: no existing -> metaFieldService.create")
    void importField_create_happyPath() {
        when(metaFieldService.isFieldExists("f1")).thenReturn(false);
        when(jdbcTemplate.queryForList(anyString(), eq("f1"), eq(1L))).thenReturn(List.of());

        MetaFieldDTO created = new MetaFieldDTO();
        created.setPid("field-pid-new");
        created.setCode("f1");
        when(metaFieldService.create(any(MetaFieldCreateRequest.class))).thenReturn(created);

        FieldDefinitionDTO dto = FieldDefinitionDTO.builder()
                .code("f1")
                .displayName("F1")
                .dataType("string")
                .build();

        PluginResource result = importer.importField(dto, "plg", "imp", 1L,
                ImportRequest.ConflictStrategy.OVERWRITE, false);

        assertThat(result.getAction()).isEqualTo(ResourceAction.CREATE.code());
        assertThat(result.getResourcePid()).isEqualTo("field-pid-new");
        ArgumentCaptor<MetaFieldCreateRequest> captor = ArgumentCaptor.forClass(MetaFieldCreateRequest.class);
        verify(metaFieldService).create(captor.capture());
        assertThat(captor.getValue().getCode()).isEqualTo("f1");
        assertThat(captor.getValue().getPluginPid()).isEqualTo("plg");
    }

    @Test
    @DisplayName("importField UPDATE branch: existing -> metaFieldMapper.updateFieldInPlace + cache evict")
    void importField_update_happyPath() {
        when(metaFieldService.isFieldExists("f1")).thenReturn(true);

        com.auraboot.framework.meta.entity.Field existing = new com.auraboot.framework.meta.entity.Field();
        existing.setPid("field-pid-exist");
        existing.setCode("f1");
        when(metaFieldMapper.findCurrentByCode("f1")).thenReturn(existing);
        when(extensionConverter.toBean(any())).thenReturn(null);
        when(metaFieldMapper.updateFieldInPlace(eq("field-pid-exist"), any(), any(), any(), any(), eq("plg")))
                .thenReturn(1);

        FieldDefinitionDTO dto = FieldDefinitionDTO.builder()
                .code("f1").displayName("F1 upd").dataType("string").build();

        PluginResource result = importer.importField(dto, "plg", "imp", 1L,
                ImportRequest.ConflictStrategy.OVERWRITE, false);

        assertThat(result.getAction()).isEqualTo(ResourceAction.UPDATE.code());
        assertThat(result.getResourcePid()).isEqualTo("field-pid-exist");
        verify(metaFieldService, never()).create(any());
    }

    @Test
    @DisplayName("importField UPDATE syncs published bound models after schema-affecting reimport")
    void importField_update_syncsPublishedBoundModels() {
        when(metaFieldService.isFieldExists("approver_id")).thenReturn(true);

        com.auraboot.framework.meta.entity.Field existing = new com.auraboot.framework.meta.entity.Field();
        existing.setId(42L);
        existing.setPid("field-pid-approver");
        existing.setCode("approver_id");
        existing.setDataType("integer");
        when(metaFieldMapper.findCurrentByCode("approver_id")).thenReturn(existing);
        when(extensionConverter.toBean(any())).thenReturn(null);
        when(metaFieldMapper.updateFieldInPlace(eq("field-pid-approver"), eq("long"), any(), any(), any(), eq("plg")))
                .thenReturn(1);
        when(fieldBindingMapper.findPublishedModelCodesByFieldId(42L))
                .thenReturn(List.of("agent_approval"));
        when(schemaManagementService.updateTableByModel("agent_approval"))
                .thenReturn(SchemaOperationResult.builder().success(true).build());

        FieldDefinitionDTO dto = FieldDefinitionDTO.builder()
                .code("approver_id")
                .displayName("Approver")
                .dataType("long")
                .build();

        PluginResource result = importer.importField(dto, "plg", "imp", 1L,
                ImportRequest.ConflictStrategy.OVERWRITE, false);

        assertThat(result.getAction()).isEqualTo(ResourceAction.UPDATE.code());
        verify(schemaManagementService).updateTableByModel("agent_approval");
    }

    @Test
    @DisplayName("importField UPDATE fails closed when bound published model schema sync fails")
    void importField_update_schemaSyncFailureThrows() {
        when(metaFieldService.isFieldExists("approver_id")).thenReturn(true);

        com.auraboot.framework.meta.entity.Field existing = new com.auraboot.framework.meta.entity.Field();
        existing.setId(42L);
        existing.setPid("field-pid-approver");
        existing.setCode("approver_id");
        when(metaFieldMapper.findCurrentByCode("approver_id")).thenReturn(existing);
        when(extensionConverter.toBean(any())).thenReturn(null);
        when(metaFieldMapper.updateFieldInPlace(any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(fieldBindingMapper.findPublishedModelCodesByFieldId(42L))
                .thenReturn(List.of("agent_approval"));
        when(schemaManagementService.updateTableByModel("agent_approval"))
                .thenReturn(SchemaOperationResult.builder()
                        .success(false)
                        .errorMessage("cannot cast")
                        .build());

        FieldDefinitionDTO dto = FieldDefinitionDTO.builder()
                .code("approver_id")
                .displayName("Approver")
                .dataType("long")
                .build();

        assertThatThrownBy(() -> importer.importField(dto, "plg", "imp", 1L,
                ImportRequest.ConflictStrategy.OVERWRITE, false))
                .isInstanceOf(PluginException.class)
                .hasMessageContaining("Failed to sync schema after updating field approver_id")
                .hasMessageContaining("cannot cast");
    }

    @Test
    @DisplayName("importField UPDATE: zero rows updated -> throws PluginException")
    void importField_update_zeroRows_throws() {
        when(metaFieldService.isFieldExists("f1")).thenReturn(true);
        com.auraboot.framework.meta.entity.Field existing = new com.auraboot.framework.meta.entity.Field();
        existing.setPid("field-pid-exist");
        when(metaFieldMapper.findCurrentByCode("f1")).thenReturn(existing);
        when(extensionConverter.toBean(any())).thenReturn(null);
        when(metaFieldMapper.updateFieldInPlace(any(), any(), any(), any(), any(), any())).thenReturn(0);

        FieldDefinitionDTO dto = FieldDefinitionDTO.builder().code("f1").displayName("F").dataType("string").build();
        assertThatThrownBy(() -> importer.importField(dto, "plg", "imp", 1L,
                ImportRequest.ConflictStrategy.OVERWRITE, false))
                .isInstanceOf(PluginException.class)
                .hasMessageContaining("Failed to update field");
    }

    // ==================== importCommand ====================

    @Test
    @DisplayName("importCommand ERROR strategy: throws when command exists")
    void importCommand_error_throws() {
        com.auraboot.framework.meta.dto.CommandDefinitionDTO existing = new com.auraboot.framework.meta.dto.CommandDefinitionDTO();
        existing.setPid("cmd-pid");
        when(commandService.findByCode("c1")).thenReturn(existing);

        com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO dto =
                com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO.builder()
                        .code("c1").displayName("Cmd").build();

        assertThatThrownBy(() -> importer.importCommand(dto, "plg", "imp", 1L,
                ImportRequest.ConflictStrategy.ERROR, false))
                .isInstanceOf(PluginException.class)
                .hasMessageContaining("Command already exists");
    }

    @Test
    @DisplayName("importCommand SKIP strategy: returns SKIP record")
    void importCommand_skip_returnsSkipRecord() {
        com.auraboot.framework.meta.dto.CommandDefinitionDTO existing = new com.auraboot.framework.meta.dto.CommandDefinitionDTO();
        when(commandService.findByCode("c1")).thenReturn(existing);
        com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO dto =
                com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO.builder()
                        .code("c1").displayName("Cmd").build();

        PluginResource result = importer.importCommand(dto, "plg", "imp", 1L,
                ImportRequest.ConflictStrategy.SKIP, false);

        assertThat(result.getAction()).isEqualTo(ResourceAction.SKIP.code());
        assertThat(result.getResourceType()).isEqualTo(ResourceType.COMMAND.code());
    }

    @Test
    @DisplayName("importCommand CREATE branch: no existing -> commandService.create")
    void importCommand_create_happyPath() throws Exception {
        when(commandService.findByCode("c1")).thenReturn(null);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        com.auraboot.framework.meta.dto.CommandDefinitionDTO created = new com.auraboot.framework.meta.dto.CommandDefinitionDTO();
        created.setPid("cmd-pid-new");
        created.setCode("c1");
        when(commandService.create(any(CommandDefinitionCreateRequest.class))).thenReturn(created);

        com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO dto =
                com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO.builder()
                        .code("c1").displayName("Cmd").modelCode("m1").build();

        PluginResource result = importer.importCommand(dto, "plg", "imp", 1L,
                ImportRequest.ConflictStrategy.OVERWRITE, false);

        assertThat(result.getAction()).isEqualTo(ResourceAction.CREATE.code());
        assertThat(result.getResourcePid()).isEqualTo("cmd-pid-new");
        verify(commandService).create(any(CommandDefinitionCreateRequest.class));
        verify(commandService, never()).publish(any());
    }

    @Test
    @DisplayName("importCommand CREATE with autoPublish=true -> publish() called")
    void importCommand_create_autoPublish() throws Exception {
        when(commandService.findByCode("c2")).thenReturn(null);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        com.auraboot.framework.meta.dto.CommandDefinitionDTO created = new com.auraboot.framework.meta.dto.CommandDefinitionDTO();
        created.setPid("cmd-pid-2");
        when(commandService.create(any())).thenReturn(created);

        com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO dto =
                com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO.builder()
                        .code("c2").displayName("Cmd2").build();

        importer.importCommand(dto, "plg", "imp", 1L, ImportRequest.ConflictStrategy.OVERWRITE, true);
        verify(commandService).publish("cmd-pid-2");
    }

    @Test
    @DisplayName("importCommand UPDATE branch: existing -> commandDefinitionMapper.updateForPluginImport + cache evict")
    void importCommand_update_happyPath() throws Exception {
        com.auraboot.framework.meta.dto.CommandDefinitionDTO existing = new com.auraboot.framework.meta.dto.CommandDefinitionDTO();
        existing.setPid("cmd-pid-exist");
        existing.setStatus("draft");
        when(commandService.findByCode("c1")).thenReturn(existing);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO dto =
                com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO.builder()
                        .code("c1").displayName("Cmd Upd").build();

        PluginResource result = importer.importCommand(dto, "plg", "imp", 1L,
                ImportRequest.ConflictStrategy.OVERWRITE, false);

        assertThat(result.getAction()).isEqualTo(ResourceAction.UPDATE.code());
        assertThat(result.getResourcePid()).isEqualTo("cmd-pid-exist");
        verify(commandDefinitionMapper).updateForPluginImport(any(), any(), any(), any(), any(),
                any(), any(), any(), eq("plg"), eq("cmd-pid-exist"), eq(1L));
        verify(commandMetadataCache).evictAll();
        verify(commandService, never()).create(any());
    }

    // ==================== importPage ====================

    @Test
    @DisplayName("importPage ERROR strategy: throws when page exists")
    void importPage_error_throws() {
        com.auraboot.framework.meta.dto.PageSchemaDTO existing = new com.auraboot.framework.meta.dto.PageSchemaDTO();
        existing.setPid("page-pid");
        when(pageSchemaService.findAnyByPageKey("p1")).thenReturn(existing);
        com.auraboot.framework.plugin.dto.imports.PageSchemaDTO dto =
                com.auraboot.framework.plugin.dto.imports.PageSchemaDTO.builder()
                        .pageKey("p1").name("Page1").build();

        assertThatThrownBy(() -> importer.importPage(dto, "plg", "imp", 1L,
                ImportRequest.ConflictStrategy.ERROR, false))
                .isInstanceOf(PluginException.class)
                .hasMessageContaining("Page already exists");
    }

    @Test
    @DisplayName("importPage SKIP strategy: returns SKIP record when page exists")
    void importPage_skip_returnsSkipRecord() {
        com.auraboot.framework.meta.dto.PageSchemaDTO existing = new com.auraboot.framework.meta.dto.PageSchemaDTO();
        when(pageSchemaService.findAnyByPageKey("p1")).thenReturn(existing);
        com.auraboot.framework.plugin.dto.imports.PageSchemaDTO dto =
                com.auraboot.framework.plugin.dto.imports.PageSchemaDTO.builder()
                        .pageKey("p1").name("Page1").build();

        PluginResource result = importer.importPage(dto, "plg", "imp", 1L,
                ImportRequest.ConflictStrategy.SKIP, false);
        assertThat(result.getAction()).isEqualTo(ResourceAction.SKIP.code());
    }

    @Test
    @DisplayName("importPage CREATE branch: no existing -> pageSchemaMapper.insertForPluginImport")
    void importPage_create_happyPath() throws Exception {
        when(pageSchemaService.findAnyByPageKey("p1")).thenReturn(null);
        when(environmentService.findOrCreateDefaultId(1L)).thenReturn(99L);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        com.auraboot.framework.plugin.dto.imports.PageSchemaDTO dto =
                com.auraboot.framework.plugin.dto.imports.PageSchemaDTO.builder()
                        .pageKey("p1").name("Page1").modelCode("m1").build();

        PluginResource result = importer.importPage(dto, "plg", "imp", 1L,
                ImportRequest.ConflictStrategy.OVERWRITE, false);

        assertThat(result.getAction()).isEqualTo(ResourceAction.CREATE.code());
        assertThat(result.getResourcePid()).isNotBlank();
        verify(pageSchemaMapper).insertForPluginImport(
                anyString(), eq(1L), eq(99L), eq("draft"),
                eq("p1"), eq("m1"), any(), any(), any(), eq("list"), eq("admin"),
                any(), any(), eq(2),
                eq(false), any(),
                any(), eq(0), any(), eq("plg"));
    }

    @Test
    @DisplayName("importPage CREATE with autoPublish=true -> insertForPluginImport with status=published")
    void importPage_create_autoPublish() throws Exception {
        when(pageSchemaService.findAnyByPageKey("p2")).thenReturn(null);
        when(environmentService.findOrCreateDefaultId(1L)).thenReturn(99L);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        com.auraboot.framework.plugin.dto.imports.PageSchemaDTO dto =
                com.auraboot.framework.plugin.dto.imports.PageSchemaDTO.builder()
                        .pageKey("p2").name("P2").build();

        importer.importPage(dto, "plg", "imp", 1L, ImportRequest.ConflictStrategy.OVERWRITE, true);

        verify(pageSchemaMapper).insertForPluginImport(
                anyString(), eq(1L), eq(99L), eq("published"),
                eq("p2"), any(), any(), any(), any(), any(), any(),
                any(), any(), eq(2),
                anyBoolean(), any(),
                any(), eq(0), any(), eq("plg"));
    }

    @Test
    @DisplayName("importPage UPDATE branch: existing -> updateForPluginImport, no autoPublish")
    void importPage_update_happyPath() throws Exception {
        com.auraboot.framework.meta.dto.PageSchemaDTO existing = new com.auraboot.framework.meta.dto.PageSchemaDTO();
        existing.setPid("page-pid-exist");
        existing.setStatus("draft");
        when(pageSchemaService.findAnyByPageKey("p1")).thenReturn(existing);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        com.auraboot.framework.plugin.dto.imports.PageSchemaDTO dto =
                com.auraboot.framework.plugin.dto.imports.PageSchemaDTO.builder()
                        .pageKey("p1").name("Page1 Upd").build();

        PluginResource result = importer.importPage(dto, "plg", "imp", 1L,
                ImportRequest.ConflictStrategy.OVERWRITE, false);

        assertThat(result.getAction()).isEqualTo(ResourceAction.UPDATE.code());
        assertThat(result.getResourcePid()).isEqualTo("page-pid-exist");
        verify(pageSchemaMapper).updateForPluginImport(any(), any(), any(), any(), any(), any(),
                any(), any(), eq(2), anyBoolean(), any(), anyInt(), any(), eq("plg"),
                eq("page-pid-exist"), eq(1L));
        verify(pageSchemaMapper, never()).publishByPid(anyString());
    }

    @Test
    @DisplayName("importPage UPDATE with autoPublish=true and draft -> publishByPid called")
    void importPage_update_autoPublish() throws Exception {
        com.auraboot.framework.meta.dto.PageSchemaDTO existing = new com.auraboot.framework.meta.dto.PageSchemaDTO();
        existing.setPid("page-pid-exist");
        existing.setStatus("draft");
        when(pageSchemaService.findAnyByPageKey("p1")).thenReturn(existing);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        com.auraboot.framework.plugin.dto.imports.PageSchemaDTO dto =
                com.auraboot.framework.plugin.dto.imports.PageSchemaDTO.builder()
                        .pageKey("p1").name("P").build();

        importer.importPage(dto, "plg", "imp", 1L, ImportRequest.ConflictStrategy.OVERWRITE, true);
        verify(pageSchemaMapper).publishByPid("page-pid-exist");
    }

    // ==================== importDashboard ====================

    @Test
    @DisplayName("importDashboard invalid -> throws PluginException")
    void importDashboard_invalid_throws() {
        DashboardDefinitionDTO dto = DashboardDefinitionDTO.builder().code("d1").build();
        // missing title and widgets -> isValid() == false
        assertThatThrownBy(() -> importer.importDashboard(dto, "plg", "imp", 1L,
                ImportRequest.ConflictStrategy.OVERWRITE))
                .isInstanceOf(PluginException.class)
                .hasMessageContaining("Invalid dashboard");
    }

    @Test
    @DisplayName("importDashboard ERROR strategy: throws when dashboard exists")
    void importDashboard_error_throws() {
        DashboardDefinitionDTO dto = DashboardDefinitionDTO.builder()
                .code("d1").title("Dash").widgets(List.of("w1")).build();
        when(objectMapper.valueToTree(any())).thenReturn(null);
        when(objectMapper.createObjectNode()).thenReturn(new ObjectMapper().createObjectNode());

        DashboardDTO existing = new DashboardDTO();
        existing.setPid("dash-pid");
        when(dashboardService.findByCode("d1")).thenReturn(existing);

        assertThatThrownBy(() -> importer.importDashboard(dto, "plg", "imp", 1L,
                ImportRequest.ConflictStrategy.ERROR))
                .isInstanceOf(PluginException.class)
                .hasMessageContaining("Dashboard already exists");
    }

    @Test
    @DisplayName("importDashboard SKIP strategy: returns SKIP record")
    void importDashboard_skip_returnsSkipRecord() {
        DashboardDefinitionDTO dto = DashboardDefinitionDTO.builder()
                .code("d1").title("Dash").widgets(List.of("w1")).build();
        ObjectMapper realMapper = new ObjectMapper();
        when(objectMapper.valueToTree(any())).thenReturn(realMapper.valueToTree(List.of("w1")));
        when(objectMapper.createObjectNode()).thenReturn(realMapper.createObjectNode());

        DashboardDTO existing = new DashboardDTO();
        existing.setPid("dash-pid-exist");
        when(dashboardService.findByCode("d1")).thenReturn(existing);

        PluginResource result = importer.importDashboard(dto, "plg", "imp", 1L,
                ImportRequest.ConflictStrategy.SKIP);
        assertThat(result.getAction()).isEqualTo(ResourceAction.SKIP.code());
        assertThat(result.getResourcePid()).isEqualTo("dash-pid-exist");
    }

    @Test
    @DisplayName("importDashboard CREATE branch: no existing -> dashboardService.create + publish")
    void importDashboard_create_happyPath() {
        DashboardDefinitionDTO dto = DashboardDefinitionDTO.builder()
                .code("d1").title("Dash").widgets(List.of("w1")).build();
        ObjectMapper realMapper = new ObjectMapper();
        when(objectMapper.valueToTree(any())).thenReturn(realMapper.valueToTree(List.of("w1")));
        when(objectMapper.createObjectNode()).thenReturn(realMapper.createObjectNode());
        when(dashboardService.findByCode("d1")).thenReturn(null);

        DashboardDTO created = new DashboardDTO();
        created.setPid("dash-pid-new");
        when(dashboardService.create(any(DashboardCreateRequest.class))).thenReturn(created);

        PluginResource result = importer.importDashboard(dto, "plg", "imp", 1L,
                ImportRequest.ConflictStrategy.OVERWRITE);

        assertThat(result.getAction()).isEqualTo(ResourceAction.CREATE.code());
        assertThat(result.getResourcePid()).isEqualTo("dash-pid-new");
        verify(dashboardService).create(any(DashboardCreateRequest.class));
        // status default = "published" -> publish should be called
        verify(dashboardService).publish("dash-pid-new");
    }

    @Test
    @DisplayName("importDashboard CREATE with status=draft -> publish NOT called")
    void importDashboard_create_draftStatus_noPublish() {
        DashboardDefinitionDTO dto = DashboardDefinitionDTO.builder()
                .code("d2").title("Dash").widgets(List.of("w")).status("draft").build();
        ObjectMapper realMapper = new ObjectMapper();
        when(objectMapper.valueToTree(any())).thenReturn(realMapper.valueToTree(List.of("w")));
        when(objectMapper.createObjectNode()).thenReturn(realMapper.createObjectNode());
        when(dashboardService.findByCode("d2")).thenReturn(null);
        DashboardDTO created = new DashboardDTO();
        created.setPid("dash-pid-d2");
        when(dashboardService.create(any())).thenReturn(created);

        importer.importDashboard(dto, "plg", "imp", 1L, ImportRequest.ConflictStrategy.OVERWRITE);
        verify(dashboardService, never()).publish(anyString());
    }

    @Test
    @DisplayName("importDashboard UPDATE branch: existing -> dashboardService.update")
    void importDashboard_update_happyPath() {
        DashboardDefinitionDTO dto = DashboardDefinitionDTO.builder()
                .code("d1").title("Dash Upd").widgets(List.of("w1")).build();
        ObjectMapper realMapper = new ObjectMapper();
        when(objectMapper.valueToTree(any())).thenReturn(realMapper.valueToTree(List.of("w1")));
        when(objectMapper.createObjectNode()).thenReturn(realMapper.createObjectNode());

        DashboardDTO existing = new DashboardDTO();
        existing.setPid("dash-pid-exist");
        when(dashboardService.findByCode("d1")).thenReturn(existing);

        PluginResource result = importer.importDashboard(dto, "plg", "imp", 1L,
                ImportRequest.ConflictStrategy.OVERWRITE);

        assertThat(result.getAction()).isEqualTo(ResourceAction.UPDATE.code());
        assertThat(result.getResourcePid()).isEqualTo("dash-pid-exist");
        verify(dashboardService).update(eq("dash-pid-exist"), any(DashboardUpdateRequest.class));
        verify(dashboardService, never()).create(any());
    }

    // ==================== importNamedQuery ====================

    @Test
    @DisplayName("importNamedQuery ERROR strategy: throws when query exists")
    void importNamedQuery_error_throws() {
        NamedQueryDTO existing = new NamedQueryDTO();
        when(namedQueryService.findByCode("nq1")).thenReturn(existing);
        NamedQueryDefinitionDTO dto = NamedQueryDefinitionDTO.builder()
                .code("nq1").title("NQ").fromSql("SELECT 1").build();

        assertThatThrownBy(() -> importer.importNamedQuery(dto, "plg", "imp", 1L,
                ImportRequest.ConflictStrategy.ERROR))
                .isInstanceOf(PluginException.class)
                .hasMessageContaining("Named query already exists");
    }

    @Test
    @DisplayName("importNamedQuery SKIP strategy: returns SKIP record")
    void importNamedQuery_skip_returnsSkipRecord() {
        NamedQueryDTO existing = new NamedQueryDTO();
        when(namedQueryService.findByCode("nq1")).thenReturn(existing);
        NamedQueryDefinitionDTO dto = NamedQueryDefinitionDTO.builder()
                .code("nq1").title("NQ").fromSql("SELECT 1").build();

        PluginResource result = importer.importNamedQuery(dto, "plg", "imp", 1L,
                ImportRequest.ConflictStrategy.SKIP);
        assertThat(result.getAction()).isEqualTo(ResourceAction.SKIP.code());
        assertThat(result.getResourceType()).isEqualTo(ResourceType.NAMED_QUERY.code());
    }

    @Test
    @DisplayName("importNamedQuery CREATE branch: no existing -> namedQueryService.create + markFieldsAsPluginSource")
    void importNamedQuery_create_happyPath() {
        when(namedQueryService.findByCode("nq1")).thenReturn(null);
        NamedQueryDTO created = new NamedQueryDTO();
        created.setPid("nq-pid-new");
        created.setId(7L);
        when(namedQueryService.create(any())).thenReturn(created);

        NamedQueryDefinitionDTO dto = NamedQueryDefinitionDTO.builder()
                .code("nq1").title("NQ").fromSql("SELECT 1").status("draft").build();

        PluginResource result = importer.importNamedQuery(dto, "plg", "imp", 1L,
                ImportRequest.ConflictStrategy.OVERWRITE);

        assertThat(result.getAction()).isEqualTo(ResourceAction.CREATE.code());
        assertThat(result.getResourcePid()).isEqualTo("nq-pid-new");
        assertThat(result.getResourceId()).isEqualTo(7L);
        verify(namedQueryService).markFieldsAsPluginSource("nq1");
    }

    @Test
    @DisplayName("importNamedQuery CREATE: stale-cache fallback when namedQueryMapper.findByCode returns null")
    void importNamedQuery_create_staleCacheFallback() {
        // checkNamedQueryExists -> service returns non-null (cache says exists)
        when(namedQueryService.findByCode("nq1")).thenReturn(new NamedQueryDTO());
        // But mapper says null (stale cache) -> fall through to CREATE
        when(namedQueryMapper.findByCode("nq1")).thenReturn(null);
        NamedQueryDTO created = new NamedQueryDTO();
        created.setPid("nq-pid-stale");
        when(namedQueryService.create(any())).thenReturn(created);

        NamedQueryDefinitionDTO dto = NamedQueryDefinitionDTO.builder()
                .code("nq1").title("NQ").fromSql("SELECT 1").build();

        PluginResource result = importer.importNamedQuery(dto, "plg", "imp", 1L,
                ImportRequest.ConflictStrategy.OVERWRITE);
        assertThat(result.getAction()).isEqualTo(ResourceAction.CREATE.code());
        assertThat(result.getResourcePid()).isEqualTo("nq-pid-stale");
    }

    // ==================== importAgentDefinition ====================

    @Test
    @DisplayName("importAgentDefinition ERROR strategy: throws when agent exists")
    void importAgentDefinition_error_throws() {
        AgentDefinition existing = new AgentDefinition();
        existing.setPid("agent-pid");
        when(agentDefinitionMapper.selectOne(any())).thenReturn(existing);

        AgentDefinitionDTO dto = AgentDefinitionDTO.builder()
                .agentCode("a1").name("A").build();

        assertThatThrownBy(() -> importer.importAgentDefinition(dto, "plg", "imp", 1L,
                ImportRequest.ConflictStrategy.ERROR))
                .isInstanceOf(PluginException.class)
                .hasMessageContaining("Agent definition already exists");
    }

    @Test
    @DisplayName("importAgentDefinition SKIP strategy: returns SKIP record")
    void importAgentDefinition_skip_returnsSkipRecord() {
        AgentDefinition existing = new AgentDefinition();
        when(agentDefinitionMapper.selectOne(any())).thenReturn(existing);
        AgentDefinitionDTO dto = AgentDefinitionDTO.builder()
                .agentCode("a1").name("A").build();

        PluginResource result = importer.importAgentDefinition(dto, "plg", "imp", 1L,
                ImportRequest.ConflictStrategy.SKIP);
        assertThat(result.getAction()).isEqualTo(ResourceAction.SKIP.code());
        assertThat(result.getResourceType()).isEqualTo(ResourceType.AGENT_DEFINITION.code());
    }

    @Test
    @DisplayName("importAgentDefinition CREATE branch: no existing -> agentDefinitionMapper.insert")
    void importAgentDefinition_create_happyPath() throws Exception {
        when(agentDefinitionMapper.selectOne(any())).thenReturn(null);
        when(objectMapper.writeValueAsString(any())).thenReturn("[]");

        AgentDefinitionDTO dto = AgentDefinitionDTO.builder()
                .agentCode("a1").name("Agent1").description("d").build();

        PluginResource result = importer.importAgentDefinition(dto, "plg", "imp", 1L,
                ImportRequest.ConflictStrategy.OVERWRITE);

        assertThat(result.getAction()).isEqualTo(ResourceAction.CREATE.code());
        assertThat(result.getResourcePid()).isNotBlank();

        ArgumentCaptor<AgentDefinition> captor = ArgumentCaptor.forClass(AgentDefinition.class);
        verify(agentDefinitionMapper).insert(captor.capture());
        assertThat(captor.getValue().getAgentCode()).isEqualTo("a1");
        assertThat(captor.getValue().getTenantId()).isEqualTo(1L);
        assertThat(captor.getValue().getDeletedFlag()).isFalse();
        // defaults applied
        assertThat(captor.getValue().getAgentType()).isEqualTo("reactive");
        assertThat(captor.getValue().getStatus()).isEqualTo("active");
    }

    @Test
    @DisplayName("importAgentDefinition UPDATE branch: existing -> updateById + applies fields")
    void importAgentDefinition_update_happyPath() throws Exception {
        AgentDefinition existing = new AgentDefinition();
        existing.setId(42L);
        existing.setPid("agent-pid-exist");
        existing.setAgentCode("a1");
        // 1st invocation: pre-update (exists check); 2nd: post-update lookup
        when(agentDefinitionMapper.selectOne(any())).thenReturn(existing, existing);
        when(objectMapper.writeValueAsString(any())).thenReturn("[]");

        AgentDefinitionDTO dto = AgentDefinitionDTO.builder()
                .agentCode("a1").name("Agent Updated").description("desc upd").build();

        PluginResource result = importer.importAgentDefinition(dto, "plg", "imp", 1L,
                ImportRequest.ConflictStrategy.OVERWRITE);

        assertThat(result.getAction()).isEqualTo(ResourceAction.UPDATE.code());
        assertThat(result.getResourcePid()).isEqualTo("agent-pid-exist");
        assertThat(result.getResourceId()).isEqualTo(42L);
        verify(agentDefinitionMapper).updateById(any(AgentDefinition.class));
        verify(agentDefinitionMapper, never()).insert(any(AgentDefinition.class));
    }
}
