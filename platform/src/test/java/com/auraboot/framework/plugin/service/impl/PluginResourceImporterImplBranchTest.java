package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.agent.entity.AgentDefinition;
import com.auraboot.framework.agent.mapper.AgentDefinitionMapper;
import com.auraboot.framework.dashboard.service.DashboardService;
import com.auraboot.framework.environment.service.EnvironmentService;
import com.auraboot.framework.menu.mapper.MenuMapper;
import com.auraboot.framework.menu.service.MenuService;
import com.auraboot.framework.meta.converter.ExtensionConverter;
import com.auraboot.framework.meta.dto.CommandDefinitionDTO;
import com.auraboot.framework.meta.dto.DictDTO;
import com.auraboot.framework.meta.dto.NamedQueryDTO;
import com.auraboot.framework.meta.dto.PageSchemaDTO;
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
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.PermissionService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito branch tests for {@link PluginResourceImporterImpl} existence-check delegates
 * and the catch-Exception "treat as not exists" branch. These cover the small but heavily
 * branched section in lines 167-249 without any database or filesystem dependency.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PluginResourceImporterImplBranchTest {

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

    @Test
    @DisplayName("checkModelExists delegates to metaModelService")
    void checkModelExists_true() {
        when(metaModelService.isModelExists("m1")).thenReturn(true);
        assertThat(importer.checkModelExists(1L, "m1")).isTrue();
    }

    @Test
    @DisplayName("checkModelExists returns false when service says no")
    void checkModelExists_false() {
        when(metaModelService.isModelExists("missing")).thenReturn(false);
        assertThat(importer.checkModelExists(1L, "missing")).isFalse();
    }

    @Test
    @DisplayName("checkFieldExists delegates to metaFieldService")
    void checkFieldExists_true() {
        when(metaFieldService.isFieldExists("f1")).thenReturn(true);
        assertThat(importer.checkFieldExists(1L, "f1")).isTrue();
    }

    @Test
    @DisplayName("checkCommandExists returns true on present DTO")
    void checkCommandExists_true() {
        when(commandService.findByCode("cmd1")).thenReturn(new CommandDefinitionDTO());
        assertThat(importer.checkCommandExists(1L, "cmd1")).isTrue();
    }

    @Test
    @DisplayName("checkCommandExists returns false on null result")
    void checkCommandExists_falseOnNull() {
        when(commandService.findByCode("cmd1")).thenReturn(null);
        assertThat(importer.checkCommandExists(1L, "cmd1")).isFalse();
    }

    @Test
    @DisplayName("checkCommandExists swallows exception and returns false")
    void checkCommandExists_exceptionSwallowed() {
        when(commandService.findByCode("boom")).thenThrow(new RuntimeException("db down"));
        assertThat(importer.checkCommandExists(1L, "boom")).isFalse();
    }

    @Test
    @DisplayName("checkPermissionExists returns true on present DTO")
    void checkPermissionExists_true() {
        when(permissionService.findByCode("p1")).thenReturn(new PermissionDTO());
        assertThat(importer.checkPermissionExists(1L, "p1")).isTrue();
    }

    @Test
    @DisplayName("checkPermissionExists swallows exception")
    void checkPermissionExists_exceptionSwallowed() {
        when(permissionService.findByCode("boom")).thenThrow(new RuntimeException("x"));
        assertThat(importer.checkPermissionExists(1L, "boom")).isFalse();
    }

    @Test
    @DisplayName("checkRoleExists delegates to roleMapper")
    void checkRoleExists_true() {
        when(roleMapper.existsByCode(eq(1L), eq("r1"))).thenReturn(true);
        assertThat(importer.checkRoleExists(1L, "r1")).isTrue();
    }

    @Test
    @DisplayName("checkMenuExists delegates to menuMapper")
    void checkMenuExists_false() {
        when(menuMapper.existsByCode(eq(1L), eq("m1"))).thenReturn(false);
        assertThat(importer.checkMenuExists(1L, "m1")).isFalse();
    }

    @Test
    @DisplayName("clearMenuCodeMap is idempotent and does not throw")
    void clearMenuCodeMap_noThrow() {
        importer.clearMenuCodeMap();
        importer.clearMenuCodeMap();
    }

    @Test
    @DisplayName("checkProcessExists delegates to processDefinitionMapper")
    void checkProcessExists_true() {
        when(processDefinitionMapper.existsByProcessKey(eq(1L), eq("k"))).thenReturn(true);
        assertThat(importer.checkProcessExists(1L, "k")).isTrue();
    }

    @Test
    @DisplayName("checkPageExists is true when service returns DTO")
    void checkPageExists_true() {
        when(pageSchemaService.findAnyByPageKey("page-1")).thenReturn(new PageSchemaDTO());
        assertThat(importer.checkPageExists(1L, "page-1")).isTrue();
    }

    @Test
    @DisplayName("checkPageExists is false when service returns null")
    void checkPageExists_false() {
        when(pageSchemaService.findAnyByPageKey("page-1")).thenReturn(null);
        assertThat(importer.checkPageExists(1L, "page-1")).isFalse();
    }

    @Test
    @DisplayName("checkDictExists is true when service returns DTO")
    void checkDictExists_true() {
        when(dictService.findByCode("d1")).thenReturn(new DictDTO());
        assertThat(importer.checkDictExists(1L, "d1")).isTrue();
    }

    @Test
    @DisplayName("checkDictExists is false when service returns null")
    void checkDictExists_false() {
        when(dictService.findByCode("missing")).thenReturn(null);
        assertThat(importer.checkDictExists(1L, "missing")).isFalse();
    }

    @Test
    @DisplayName("checkNamedQueryExists is true when service returns DTO")
    void checkNamedQueryExists_true() {
        when(namedQueryService.findByCode("nq1")).thenReturn(new NamedQueryDTO());
        assertThat(importer.checkNamedQueryExists(1L, "nq1")).isTrue();
    }

    @Test
    @DisplayName("checkNamedQueryExists is false on null")
    void checkNamedQueryExists_falseOnNull() {
        when(namedQueryService.findByCode("missing")).thenReturn(null);
        assertThat(importer.checkNamedQueryExists(1L, "missing")).isFalse();
    }

    @Test
    @DisplayName("checkNamedQueryExists swallows exception and returns false")
    void checkNamedQueryExists_exceptionSwallowed() {
        when(namedQueryService.findByCode("boom")).thenThrow(new RuntimeException("x"));
        assertThat(importer.checkNamedQueryExists(1L, "boom")).isFalse();
    }
}
