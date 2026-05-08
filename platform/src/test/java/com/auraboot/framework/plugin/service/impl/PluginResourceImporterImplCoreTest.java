package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.agent.mapper.AgentDefinitionMapper;
import com.auraboot.framework.dashboard.service.DashboardService;
import com.auraboot.framework.environment.service.EnvironmentService;
import com.auraboot.framework.menu.mapper.MenuMapper;
import com.auraboot.framework.menu.service.MenuService;
import com.auraboot.framework.meta.converter.ExtensionConverter;
import com.auraboot.framework.meta.dto.CommandDefinitionDTO;
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
import com.auraboot.framework.plugin.dto.imports.BindingRuleDTO;
import com.auraboot.framework.plugin.dto.imports.DictDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.dto.imports.NamedQueryDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.PermissionDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ResourceAction;
import com.auraboot.framework.plugin.dto.imports.ResourceType;
import com.auraboot.framework.plugin.dto.imports.RoleDefinitionDTO;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.auraboot.framework.plugin.exception.PluginException;
import com.auraboot.framework.plugin.mapper.BpmProcessDefinitionMapper;
import com.auraboot.framework.plugin.mapper.PluginResourceMapper;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.auraboot.framework.rbac.service.RoleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito tests for {@link PluginResourceImporterImpl} core import-method conflict
 * branches: ERROR, SKIP, and the platform-only role guard. These exercise the early-return
 * branches that don't touch JDBC (no resurrectSoftDeleted call) and validate the
 * {@link PluginResource} DTOs returned for SKIP actions.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PluginResourceImporterImplCoreTest {

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

    // ==================== importPermission ====================

    @Test
    @DisplayName("importPermission throws PluginException on ERROR strategy when exists")
    void importPermission_errorOnConflict() {
        com.auraboot.framework.permission.dto.PermissionDTO existing =
                new com.auraboot.framework.permission.dto.PermissionDTO();
        existing.setPid("perm-pid");
        when(permissionService.findByCode("perm.read")).thenReturn(existing);

        PermissionDefinitionDTO dto = PermissionDefinitionDTO.builder()
                .code("perm.read")
                .name("Read")
                .build();

        assertThatThrownBy(() -> importer.importPermission(dto, "plugin-1", "imp-1", 1L,
                ImportRequest.ConflictStrategy.ERROR))
                .isInstanceOf(PluginException.class)
                .hasMessageContaining("Permission already exists");
    }

    @Test
    @DisplayName("importPermission returns SKIP record on SKIP strategy when exists")
    void importPermission_skipOnConflict() {
        com.auraboot.framework.permission.dto.PermissionDTO existing =
                new com.auraboot.framework.permission.dto.PermissionDTO();
        existing.setPid("perm-pid");
        when(permissionService.findByCode("perm.read")).thenReturn(existing);

        PermissionDefinitionDTO dto = PermissionDefinitionDTO.builder()
                .code("perm.read")
                .name("Read")
                .build();

        PluginResource result = importer.importPermission(dto, "plugin-1", "imp-1", 1L,
                ImportRequest.ConflictStrategy.SKIP);

        assertThat(result).isNotNull();
        assertThat(result.getAction()).isEqualTo(ResourceAction.SKIP.code());
        assertThat(result.getResourceType()).isEqualTo(ResourceType.PERMISSION.code());
        assertThat(result.getResourceCode()).isEqualTo("perm.read");
        assertThat(result.getPluginPid()).isEqualTo("plugin-1");
        assertThat(result.getImportId()).isEqualTo("imp-1");
        assertThat(result.getResourcePid()).isNull();
    }

    @Test
    @DisplayName("importPermission OVERWRITE_SAFE skips user-modified resource")
    void importPermission_overwriteSafe_skipsUserModified() {
        com.auraboot.framework.permission.dto.PermissionDTO existing =
                new com.auraboot.framework.permission.dto.PermissionDTO();
        existing.setPid("perm-pid");
        when(permissionService.findByCode("perm.read")).thenReturn(existing);

        PluginResource userModified = PluginResource.builder()
                .userModified(true)
                .build();
        when(pluginResourceMapper.findByTypeAndCode(eq(1L), eq(ResourceType.PERMISSION.code()),
                eq("perm.read"))).thenReturn(userModified);

        PermissionDefinitionDTO dto = PermissionDefinitionDTO.builder()
                .code("perm.read")
                .name("Read")
                .build();

        PluginResource result = importer.importPermission(dto, "plugin-1", "imp-1", 1L,
                ImportRequest.ConflictStrategy.OVERWRITE_SAFE);

        assertThat(result.getAction()).isEqualTo(ResourceAction.SKIP.code());
    }

    // ==================== importRole ====================

    @Test
    @DisplayName("importRole rejects platform-only role code in non-global scope")
    void importRole_platformOnly_rejected() {
        RoleDefinitionDTO dto = RoleDefinitionDTO.builder()
                .code(com.auraboot.framework.rbac.constant.RoleConstants.PLATFORM_ADMIN)
                .name("Platform Admin")
                .scopeType("tenant")
                .build();

        assertThatThrownBy(() -> importer.importRole(dto, "plugin-1", "imp-1", 1L,
                ImportRequest.ConflictStrategy.ERROR))
                .isInstanceOf(PluginException.class)
                .hasMessageContaining("reserved for platform level");
    }

    @Test
    @DisplayName("importRole throws on ERROR strategy when role exists")
    void importRole_errorOnConflict() {
        when(roleMapper.existsByCode(1L, "tester")).thenReturn(true);
        RoleDefinitionDTO dto = RoleDefinitionDTO.builder()
                .code("tester")
                .name("Tester")
                .build();

        assertThatThrownBy(() -> importer.importRole(dto, "plugin-1", "imp-1", 1L,
                ImportRequest.ConflictStrategy.ERROR))
                .isInstanceOf(PluginException.class)
                .hasMessageContaining("Role already exists");
    }

    @Test
    @DisplayName("importRole returns SKIP record on SKIP strategy")
    void importRole_skipOnConflict() {
        when(roleMapper.existsByCode(1L, "tester")).thenReturn(true);
        RoleDefinitionDTO dto = RoleDefinitionDTO.builder()
                .code("tester")
                .name("Tester")
                .build();

        PluginResource result = importer.importRole(dto, "plugin-1", "imp-1", 1L,
                ImportRequest.ConflictStrategy.SKIP);

        assertThat(result.getAction()).isEqualTo(ResourceAction.SKIP.code());
        assertThat(result.getResourceType()).isEqualTo(ResourceType.ROLE.code());
        assertThat(result.getResourceCode()).isEqualTo("tester");
    }

    // ==================== importDict ====================

    @Test
    @DisplayName("importDict throws on ERROR strategy when dict exists")
    void importDict_errorOnConflict() {
        com.auraboot.framework.meta.dto.DictDTO existing = new com.auraboot.framework.meta.dto.DictDTO();
        existing.setPid("dict-pid");
        when(dictService.findByCode("status")).thenReturn(existing);

        DictDefinitionDTO dto = DictDefinitionDTO.builder()
                .code("status")
                .name("Status")
                .build();

        assertThatThrownBy(() -> importer.importDict(dto, "plugin-1", "imp-1", 1L,
                ImportRequest.ConflictStrategy.ERROR))
                .isInstanceOf(PluginException.class)
                .hasMessageContaining("Dictionary already exists");
    }

    @Test
    @DisplayName("importDict returns SKIP record on SKIP strategy when dict exists")
    void importDict_skipOnConflict() {
        com.auraboot.framework.meta.dto.DictDTO existing = new com.auraboot.framework.meta.dto.DictDTO();
        existing.setPid("dict-pid");
        when(dictService.findByCode("status")).thenReturn(existing);

        DictDefinitionDTO dto = DictDefinitionDTO.builder()
                .code("status")
                .name("Status")
                .build();

        PluginResource result = importer.importDict(dto, "plugin-1", "imp-1", 1L,
                ImportRequest.ConflictStrategy.SKIP);

        assertThat(result.getAction()).isEqualTo(ResourceAction.SKIP.code());
        assertThat(result.getResourceType()).isEqualTo(ResourceType.DICT.code());
        assertThat(result.getResourceCode()).isEqualTo("status");
    }

    // ==================== importNamedQuery ====================

    @Test
    @DisplayName("importNamedQuery throws on ERROR strategy when query exists")
    void importNamedQuery_errorOnConflict() {
        com.auraboot.framework.meta.dto.NamedQueryDTO existing =
                new com.auraboot.framework.meta.dto.NamedQueryDTO();
        existing.setPid("nq-pid");
        when(namedQueryService.findByCode("user.list")).thenReturn(existing);

        NamedQueryDefinitionDTO dto = NamedQueryDefinitionDTO.builder()
                .code("user.list")
                .title("User List")
                .build();

        assertThatThrownBy(() -> importer.importNamedQuery(dto, "plugin-1", "imp-1", 1L,
                ImportRequest.ConflictStrategy.ERROR))
                .isInstanceOf(PluginException.class)
                .hasMessageContaining("Named query already exists");
    }

    @Test
    @DisplayName("importNamedQuery returns SKIP record on SKIP strategy")
    void importNamedQuery_skipOnConflict() {
        com.auraboot.framework.meta.dto.NamedQueryDTO existing =
                new com.auraboot.framework.meta.dto.NamedQueryDTO();
        existing.setPid("nq-pid");
        when(namedQueryService.findByCode("user.list")).thenReturn(existing);

        NamedQueryDefinitionDTO dto = NamedQueryDefinitionDTO.builder()
                .code("user.list")
                .title("User List")
                .build();

        PluginResource result = importer.importNamedQuery(dto, "plugin-1", "imp-1", 1L,
                ImportRequest.ConflictStrategy.SKIP);

        assertThat(result.getAction()).isEqualTo(ResourceAction.SKIP.code());
        assertThat(result.getResourceType()).isEqualTo(ResourceType.NAMED_QUERY.code());
        assertThat(result.getResourceCode()).isEqualTo("user.list");
    }

    // ==================== importBindingRule ====================

    @Test
    @DisplayName("importBindingRule fails when referenced command not found")
    void importBindingRule_commandNotFound() {
        when(commandService.findByCode("missing.cmd"))
                .thenThrow(new RuntimeException("not found"));

        BindingRuleDTO dto = BindingRuleDTO.builder()
                .commandCode("missing.cmd")
                .ruleType("PRE_VALIDATE")
                .build();

        assertThatThrownBy(() -> importer.importBindingRule(dto, "plugin-1", "imp-1", 1L,
                ImportRequest.ConflictStrategy.ERROR))
                .isInstanceOf(PluginException.class)
                .hasMessageContaining("Command not found");
    }

    @Test
    @DisplayName("importBindingRule throws on ERROR when ruleType already exists")
    void importBindingRule_errorOnConflict() {
        CommandDefinitionDTO command = new CommandDefinitionDTO();
        command.setPid("cmd-pid");
        when(commandService.findByCode("foo.cmd")).thenReturn(command);

        com.auraboot.framework.meta.dto.BindingRuleDTO existing =
                new com.auraboot.framework.meta.dto.BindingRuleDTO();
        existing.setRuleType("PRE_VALIDATE");
        when(commandService.getBindingRules("cmd-pid")).thenReturn(java.util.List.of(existing));

        BindingRuleDTO dto = BindingRuleDTO.builder()
                .commandCode("foo.cmd")
                .ruleType("PRE_VALIDATE")
                .build();

        assertThatThrownBy(() -> importer.importBindingRule(dto, "plugin-1", "imp-1", 1L,
                ImportRequest.ConflictStrategy.ERROR))
                .isInstanceOf(PluginException.class)
                .hasMessageContaining("Binding rule already exists");
    }

    @Test
    @DisplayName("importBindingRule returns SKIP record on SKIP strategy")
    void importBindingRule_skipOnConflict() {
        CommandDefinitionDTO command = new CommandDefinitionDTO();
        command.setPid("cmd-pid");
        when(commandService.findByCode("foo.cmd")).thenReturn(command);

        com.auraboot.framework.meta.dto.BindingRuleDTO existing =
                new com.auraboot.framework.meta.dto.BindingRuleDTO();
        existing.setRuleType("PRE_VALIDATE");
        when(commandService.getBindingRules("cmd-pid")).thenReturn(java.util.List.of(existing));

        BindingRuleDTO dto = BindingRuleDTO.builder()
                .commandCode("foo.cmd")
                .ruleType("PRE_VALIDATE")
                .build();

        PluginResource result = importer.importBindingRule(dto, "plugin-1", "imp-1", 1L,
                ImportRequest.ConflictStrategy.SKIP);

        assertThat(result.getAction()).isEqualTo(ResourceAction.SKIP.code());
        assertThat(result.getResourceType()).isEqualTo(ResourceType.BINDING_RULE.code());
        assertThat(result.getResourceCode()).isEqualTo("foo.cmd:PRE_VALIDATE");
    }

    // ==================== clearMenuCodeMap ====================

    @Test
    @DisplayName("clearMenuCodeMap completes without exception")
    void clearMenuCodeMap_noop() {
        // Idempotent — just verify no throw on empty state.
        importer.clearMenuCodeMap();
        importer.clearMenuCodeMap();
    }
}
