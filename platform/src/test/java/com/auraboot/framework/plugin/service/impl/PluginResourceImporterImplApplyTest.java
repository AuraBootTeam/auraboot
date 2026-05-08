package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.agent.mapper.AgentDefinitionMapper;
import com.auraboot.framework.dashboard.service.DashboardService;
import com.auraboot.framework.environment.service.EnvironmentService;
import com.auraboot.framework.menu.entity.Menu;
import com.auraboot.framework.menu.mapper.MenuMapper;
import com.auraboot.framework.menu.service.MenuService;
import com.auraboot.framework.meta.converter.ExtensionConverter;
import com.auraboot.framework.meta.dto.CommandDefinitionDTO;
import com.auraboot.framework.meta.dto.DictDTO;
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
import com.auraboot.framework.permission.dto.PermissionDTO;
import com.auraboot.framework.permission.dto.PermissionCreateRequest;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.PermissionService;
import com.auraboot.framework.plugin.dto.imports.BindingRuleDTO;
import com.auraboot.framework.plugin.dto.imports.DictDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.dto.imports.MenuDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.PermissionDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ResourceAction;
import com.auraboot.framework.plugin.dto.imports.ResourceType;
import com.auraboot.framework.plugin.dto.imports.RoleDefinitionDTO;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.auraboot.framework.plugin.mapper.BpmProcessDefinitionMapper;
import com.auraboot.framework.plugin.mapper.PluginResourceMapper;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.auraboot.framework.rbac.service.RoleService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito tests covering the CREATE and UPDATE branches of
 * {@link PluginResourceImporterImpl} for permission, role, dict, binding rule, and menu.
 *
 * These exercise the path past the conflict-strategy guards (no exists / OVERWRITE)
 * so that the body of each apply method runs and downstream collaborators are invoked.
 *
 * Sibling files {@code *CoreTest} and {@code *BranchTest} cover the early-return
 * conflict branches; this file complements them without modification.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PluginResourceImporterImplApplyTest {

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

    // ==================== importPermission CREATE / UPDATE ====================

    @Test
    @DisplayName("importPermission CREATE branch: no existing perm, no soft-deleted -> calls permissionService.create")
    void importPermission_create_happyPath() {
        // checkPermissionExists -> false (findByCode returns null)
        when(permissionService.findByCode("perm.create")).thenReturn(null);
        // resurrectSoftDeleted: jdbcTemplate.queryForObject returns null (no row)
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), any(), any())).thenReturn(null);

        PermissionDTO created = new PermissionDTO();
        created.setPid("perm-pid-new");
        created.setId(101L);
        when(permissionService.create(any(PermissionCreateRequest.class))).thenReturn(created);

        PermissionDefinitionDTO dto = PermissionDefinitionDTO.builder()
                .code("perm.create")
                .name("Perm Create")
                .description("desc")
                .resourceType("menu")
                .resourceCode("perm.create")
                .action("view")
                .build();

        PluginResource result = importer.importPermission(dto, "plugin-1", "imp-1", 1L,
                ImportRequest.ConflictStrategy.OVERWRITE);

        assertThat(result.getAction()).isEqualTo(ResourceAction.CREATE.code());
        assertThat(result.getResourceType()).isEqualTo(ResourceType.PERMISSION.code());
        assertThat(result.getResourceCode()).isEqualTo("perm.create");
        assertThat(result.getResourcePid()).isEqualTo("perm-pid-new");

        ArgumentCaptor<PermissionCreateRequest> captor = ArgumentCaptor.forClass(PermissionCreateRequest.class);
        verify(permissionService).create(captor.capture());
        assertThat(captor.getValue().getCode()).isEqualTo("perm.create");
        assertThat(captor.getValue().getPluginPid()).isEqualTo("plugin-1");
    }

    @Test
    @DisplayName("importPermission UPDATE branch: existing perm + OVERWRITE strategy -> permissionMapper.updateForPluginImport")
    void importPermission_update_happyPath() {
        PermissionDTO existing = new PermissionDTO();
        existing.setPid("perm-pid-exist");
        existing.setId(7L);
        when(permissionService.findByCode("perm.upd")).thenReturn(existing);

        PermissionDefinitionDTO dto = PermissionDefinitionDTO.builder()
                .code("perm.upd")
                .name("Perm Upd")
                .description("d")
                .build();

        PluginResource result = importer.importPermission(dto, "plugin-2", "imp-2", 2L,
                ImportRequest.ConflictStrategy.OVERWRITE);

        assertThat(result.getAction()).isEqualTo(ResourceAction.UPDATE.code());
        assertThat(result.getResourcePid()).isEqualTo("perm-pid-exist");
        verify(permissionMapper).updateForPluginImport(
                eq("Perm Upd"), eq("d"), any(), any(), any(), any(),
                any(), any(), any(), any(),
                eq("plugin-2"), eq(2L), eq("perm.upd"));
        verify(permissionService, never()).create(any());
    }

    @Test
    @DisplayName("importPermission CREATE branch: soft-deleted resurrect path -> returns CREATE without calling create()")
    void importPermission_resurrect_happyPath() {
        when(permissionService.findByCode("perm.res")).thenReturn(null);
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), any(), any()))
                .thenReturn("resurrected-pid");

        PermissionDefinitionDTO dto = PermissionDefinitionDTO.builder()
                .code("perm.res")
                .name("Perm Res")
                .build();

        PluginResource result = importer.importPermission(dto, "plugin-3", "imp-3", 3L,
                ImportRequest.ConflictStrategy.OVERWRITE);

        assertThat(result.getAction()).isEqualTo(ResourceAction.CREATE.code());
        assertThat(result.getResourcePid()).isEqualTo("resurrected-pid");
        verify(permissionService, never()).create(any());
    }

    // ==================== importRole CREATE / UPDATE ====================

    @Test
    @DisplayName("importRole CREATE branch: no existing role -> roleService.createRole + plugin pid update")
    void importRole_create_happyPath() {
        when(roleMapper.existsByCode(1L, "tester")).thenReturn(false);

        Role created = new Role();
        created.setPid("role-pid-new");
        created.setId(99L);
        when(roleService.createRole(any(Role.class))).thenReturn(created);

        RoleDefinitionDTO dto = RoleDefinitionDTO.builder()
                .code("tester")
                .name("Tester")
                .build();

        PluginResource result = importer.importRole(dto, "plugin-r", "imp-r", 1L,
                ImportRequest.ConflictStrategy.OVERWRITE);

        assertThat(result.getAction()).isEqualTo(ResourceAction.CREATE.code());
        assertThat(result.getResourcePid()).isEqualTo("role-pid-new");
        assertThat(result.getResourceId()).isEqualTo(99L);
        verify(roleService).createRole(any(Role.class));
        verify(roleMapper).updatePluginPidById("plugin-r", 99L);
    }

    @Test
    @DisplayName("importRole UPDATE branch: existing role + OVERWRITE -> roleMapper.updateForPluginImport")
    void importRole_update_happyPath() {
        when(roleMapper.existsByCode(1L, "tester")).thenReturn(true);
        when(roleMapper.findIdByCode(1L, "tester")).thenReturn(55L);
        when(roleMapper.findPidByCode(1L, "tester")).thenReturn("role-pid-exist");

        RoleDefinitionDTO dto = RoleDefinitionDTO.builder()
                .code("tester")
                .name("Tester Updated")
                .description("desc")
                .build();

        PluginResource result = importer.importRole(dto, "plugin-r", "imp-r", 1L,
                ImportRequest.ConflictStrategy.OVERWRITE);

        assertThat(result.getAction()).isEqualTo(ResourceAction.UPDATE.code());
        assertThat(result.getResourcePid()).isEqualTo("role-pid-exist");
        assertThat(result.getResourceId()).isEqualTo(55L);
        verify(roleMapper).updateForPluginImport(
                eq("Tester Updated"), eq("desc"), any(),
                any(), any(), any(), any(), any(),
                eq("plugin-r"), eq(1L), eq("tester"));
        verify(roleService, never()).createRole(any());
    }

    @Test
    @DisplayName("importRole CREATE with permissions -> updateRolePermissions iterates permission codes")
    void importRole_create_withPermissions() {
        when(roleMapper.existsByCode(1L, "rwp")).thenReturn(false);
        Role created = new Role();
        created.setPid("rp-pid");
        created.setId(200L);
        when(roleService.createRole(any(Role.class))).thenReturn(created);

        // updateRolePermissions: per-perm lookup
        PermissionDTO p1 = new PermissionDTO();
        p1.setId(1001L);
        when(permissionService.findByCode("p.read")).thenReturn(p1);
        when(rolePermissionMapper.countByRoleAndPermission(200L, 1001L, 1L)).thenReturn(0);

        RoleDefinitionDTO dto = RoleDefinitionDTO.builder()
                .code("rwp")
                .name("RoleWithPerms")
                .permissions(List.of("p.read"))
                .build();

        PluginResource result = importer.importRole(dto, "plg", "imp", 1L,
                ImportRequest.ConflictStrategy.OVERWRITE);

        assertThat(result.getAction()).isEqualTo(ResourceAction.CREATE.code());
        verify(permissionService).bindToRole(200L, 1001L);
    }

    @Test
    @DisplayName("importRole UPDATE: missing permission in updateRolePermissions logs warn but does not throw")
    void importRole_update_missingPermissionDoesNotThrow() {
        when(roleMapper.existsByCode(1L, "rwp")).thenReturn(true);
        when(roleMapper.findIdByCode(1L, "rwp")).thenReturn(77L);
        when(roleMapper.findPidByCode(1L, "rwp")).thenReturn("rp");
        when(permissionService.findByCode("missing")).thenReturn(null);

        RoleDefinitionDTO dto = RoleDefinitionDTO.builder()
                .code("rwp")
                .name("R")
                .permissions(List.of("missing"))
                .build();

        PluginResource result = importer.importRole(dto, "plg", "imp", 1L,
                ImportRequest.ConflictStrategy.OVERWRITE);
        assertThat(result.getAction()).isEqualTo(ResourceAction.UPDATE.code());
        verify(permissionService, never()).bindToRole(anyLong(), anyLong());
    }

    // ==================== importBindingRule CREATE / UPDATE ====================

    @Test
    @DisplayName("importBindingRule CREATE branch: no existing rule -> commandService.addBindingRule")
    void importBindingRule_create_happyPath() {
        CommandDefinitionDTO command = new CommandDefinitionDTO();
        command.setPid("cmd-pid");
        when(commandService.findByCode("foo.cmd")).thenReturn(command);
        when(commandService.getBindingRules("cmd-pid")).thenReturn(List.of());

        com.auraboot.framework.meta.dto.BindingRuleDTO created = new com.auraboot.framework.meta.dto.BindingRuleDTO();
        created.setPid("br-pid-new");
        when(commandService.addBindingRule(eq("cmd-pid"), any())).thenReturn(created);

        BindingRuleDTO dto = BindingRuleDTO.builder()
                .commandCode("foo.cmd")
                .ruleType("PRE_VALIDATE")
                .expression("expr")
                .build();

        PluginResource result = importer.importBindingRule(dto, "plg", "imp", 1L,
                ImportRequest.ConflictStrategy.OVERWRITE);

        assertThat(result.getAction()).isEqualTo(ResourceAction.CREATE.code());
        assertThat(result.getResourcePid()).isEqualTo("br-pid-new");
        verify(bindingRuleMapper).updatePluginPid("plg", "br-pid-new");
    }

    @Test
    @DisplayName("importBindingRule UPDATE branch: existing rule + OVERWRITE -> remove + recreate, action=UPDATE")
    void importBindingRule_update_happyPath() {
        CommandDefinitionDTO command = new CommandDefinitionDTO();
        command.setPid("cmd-pid");
        when(commandService.findByCode("foo.cmd")).thenReturn(command);

        com.auraboot.framework.meta.dto.BindingRuleDTO existing = new com.auraboot.framework.meta.dto.BindingRuleDTO();
        existing.setPid("old-rule");
        existing.setRuleType("PRE_VALIDATE");
        when(commandService.getBindingRules("cmd-pid")).thenReturn(List.of(existing));

        com.auraboot.framework.meta.dto.BindingRuleDTO created = new com.auraboot.framework.meta.dto.BindingRuleDTO();
        created.setPid("new-rule");
        when(commandService.addBindingRule(eq("cmd-pid"), any())).thenReturn(created);

        BindingRuleDTO dto = BindingRuleDTO.builder()
                .commandCode("foo.cmd")
                .ruleType("PRE_VALIDATE")
                .build();

        PluginResource result = importer.importBindingRule(dto, "plg", "imp", 1L,
                ImportRequest.ConflictStrategy.OVERWRITE);

        assertThat(result.getAction()).isEqualTo(ResourceAction.UPDATE.code());
        verify(commandService).removeBindingRule("old-rule");
        verify(commandService).addBindingRule(eq("cmd-pid"), any());
    }

    // ==================== importDict CREATE / UPDATE ====================

    @Test
    @DisplayName("importDict CREATE branch: no existing dict -> dictService.create + markItemsAsPluginSource")
    void importDict_create_happyPath() {
        when(dictService.findByCode("d1")).thenReturn(null);
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), any(), any())).thenReturn(null);

        DictDTO created = new DictDTO();
        created.setPid("dict-pid-new");
        created.setId(11L);
        when(dictService.create(any())).thenReturn(created);

        DictDefinitionDTO dto = DictDefinitionDTO.builder()
                .code("d1")
                .name("Dict 1")
                .build();

        PluginResource result = importer.importDict(dto, "plg", "imp", 1L,
                ImportRequest.ConflictStrategy.OVERWRITE);

        assertThat(result.getAction()).isEqualTo(ResourceAction.CREATE.code());
        assertThat(result.getResourcePid()).isEqualTo("dict-pid-new");
        verify(dictService).create(any());
        verify(dictService).markItemsAsPluginSource("dict-pid-new");
    }

    @Test
    @DisplayName("importDict UPDATE branch: existing dict + OVERWRITE -> dictService.update + dictMapper plugin pid update")
    void importDict_update_happyPath() {
        DictDTO existing = new DictDTO();
        existing.setPid("dict-pid-exist");
        existing.setId(22L);
        when(dictService.findByCode("d2")).thenReturn(existing);

        DictDTO updated = new DictDTO();
        updated.setPid("dict-pid-exist");
        updated.setId(22L);
        when(dictService.update(eq("dict-pid-exist"), any())).thenReturn(updated);

        DictDefinitionDTO dto = DictDefinitionDTO.builder()
                .code("d2")
                .name("Dict 2 Updated")
                .description("desc")
                .build();

        PluginResource result = importer.importDict(dto, "plg-d", "imp-d", 1L,
                ImportRequest.ConflictStrategy.OVERWRITE);

        assertThat(result.getAction()).isEqualTo(ResourceAction.UPDATE.code());
        assertThat(result.getResourcePid()).isEqualTo("dict-pid-exist");
        verify(dictService).update(eq("dict-pid-exist"), any());
        verify(dictMapper).updatePluginPidByPid("plg-d", "dict-pid-exist");
        verify(dictService, never()).create(any());
    }

    // ==================== importMenu CREATE / UPDATE ====================

    @Test
    @DisplayName("importMenu CREATE branch: no existing menu -> menuService.createMenu + plugin fields update")
    void importMenu_create_happyPath() {
        when(menuMapper.existsByCode(1L, "menu.x")).thenReturn(false);

        Menu created = new Menu();
        created.setPid("menu-pid-new");
        created.setId(33L);
        when(menuService.createMenu(any(Menu.class))).thenReturn(created);

        MenuDefinitionDTO dto = MenuDefinitionDTO.builder()
                .code("menu.x")
                .name("Menu X")
                .path("/x")
                .build();

        PluginResource result = importer.importMenu(dto, "plg-m", "imp-m", 1L,
                ImportRequest.ConflictStrategy.OVERWRITE);

        assertThat(result.getAction()).isEqualTo(ResourceAction.CREATE.code());
        assertThat(result.getResourcePid()).isEqualTo("menu-pid-new");
        verify(menuService).createMenu(any(Menu.class));
        verify(menuMapper).updatePluginFields(eq("plg-m"), any(), any(), eq(33L));
    }

    @Test
    @DisplayName("importMenu UPDATE branch: existing menu + OVERWRITE -> menuMapper.updateForPluginImport")
    void importMenu_update_happyPath() {
        when(menuMapper.existsByCode(1L, "menu.y")).thenReturn(true);
        when(menuMapper.findPidByCode(1L, "menu.y")).thenReturn("menu-pid-exist");
        when(menuMapper.findIdByPid(1L, "menu-pid-exist")).thenReturn(44L);

        MenuDefinitionDTO dto = MenuDefinitionDTO.builder()
                .code("menu.y")
                .name("Menu Y")
                .path("/y")
                .build();

        PluginResource result = importer.importMenu(dto, "plg-m", "imp-m", 1L,
                ImportRequest.ConflictStrategy.OVERWRITE);

        assertThat(result.getAction()).isEqualTo(ResourceAction.UPDATE.code());
        assertThat(result.getResourcePid()).isEqualTo("menu-pid-exist");
        verify(menuMapper).updateForPluginImport(
                eq("menu.y"), eq("Menu Y"), eq("/y"), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                eq("plg-m"), eq(1L), eq("menu.y"));
        verify(menuService, never()).createMenu(any());
    }

    @Test
    @DisplayName("importMenu rejects /dynamic/ path missing pageKey")
    void importMenu_dynamicWithoutPageKey_throws() {
        MenuDefinitionDTO dto = MenuDefinitionDTO.builder()
                .code("menu.dyn")
                .name("Dyn")
                .path("/dynamic/foo")
                .build();

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                importer.importMenu(dto, "plg", "imp", 1L,
                        ImportRequest.ConflictStrategy.OVERWRITE))
                .isInstanceOf(com.auraboot.framework.plugin.exception.PluginException.class)
                .hasMessageContaining("missing pageKey");
    }

    @Test
    @DisplayName("importMenu CREATE with parentCode -> resolves parent via mapper")
    void importMenu_create_withParent() {
        when(menuMapper.existsByCode(1L, "child")).thenReturn(false);
        when(menuMapper.findIdByCode(1L, "parent")).thenReturn(500L);

        Menu created = new Menu();
        created.setPid("c-pid");
        created.setId(501L);
        when(menuService.createMenu(any(Menu.class))).thenReturn(created);

        MenuDefinitionDTO dto = MenuDefinitionDTO.builder()
                .code("child")
                .name("Child")
                .parentCode("parent")
                .build();

        PluginResource result = importer.importMenu(dto, "plg", "imp", 1L,
                ImportRequest.ConflictStrategy.OVERWRITE);

        assertThat(result.getAction()).isEqualTo(ResourceAction.CREATE.code());
        verify(menuMapper).findIdByCode(1L, "parent");
        ArgumentCaptor<Menu> menuCaptor = ArgumentCaptor.forClass(Menu.class);
        verify(menuService).createMenu(menuCaptor.capture());
        assertThat(menuCaptor.getValue().getParentId()).isEqualTo(500L);
    }
}
