package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.menu.entity.Menu;
import com.auraboot.framework.menu.mapper.MenuMapper;
import com.auraboot.framework.menu.service.MenuService;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.dto.imports.MenuDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.RoleDefinitionDTO;
import com.auraboot.framework.plugin.entity.PluginRecord;
import com.auraboot.framework.plugin.exception.PluginException;
import com.auraboot.framework.plugin.mapper.PluginRecordMapper;
import com.auraboot.framework.permission.dto.PermissionDTO;
import com.auraboot.framework.permission.service.PermissionService;
import com.auraboot.framework.permission.service.RolePermissionService;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Fail-closed contract for declared role permissions during plugin import:
 * after model publication every declared permission code must resolve and bind,
 * otherwise the import fails with a locatable error (role + code + provider
 * analysis) instead of the historic warn+skip that swallowed bad compositions.
 * Platform-registered system/data codes are covered by the same positive path —
 * there is deliberately no exemption branch for them (registered rows resolve
 * and bind like any other code).
 */
class PluginRolePermissionFailClosedTest {
    private final PluginResourceImporterImpl service = mock(PluginResourceImporterImpl.class, CALLS_REAL_METHODS);
    private final PermissionService permissions = mock(PermissionService.class);
    private final RolePermissionService rolePermissions = mock(RolePermissionService.class);
    private final RolePermissionMapper bindings = mock(RolePermissionMapper.class);
    private final RoleMapper roleMapper = mock(RoleMapper.class);
    private final MetaModelMapper metaModels = mock(MetaModelMapper.class);
    private final PluginRecordMapper pluginRecords = mock(PluginRecordMapper.class);
    private final MenuMapper menus = mock(MenuMapper.class);
    private final MenuService menuService = mock(MenuService.class);

    PluginRolePermissionFailClosedTest() {
        ReflectionTestUtils.setField(service, "permissionService", permissions);
        ReflectionTestUtils.setField(service, "rolePermissionService", rolePermissions);
        ReflectionTestUtils.setField(service, "rolePermissionMapper", bindings);
        ReflectionTestUtils.setField(service, "roleMapper", roleMapper);
        ReflectionTestUtils.setField(service, "metaModelMapper", metaModels);
        ReflectionTestUtils.setField(service, "pluginRecordMapper", pluginRecords);
        ReflectionTestUtils.setField(service, "menuMapper", menus);
        ReflectionTestUtils.setField(service, "menuService", menuService);
        ReflectionTestUtils.setField(service, "objectMapper", new com.fasterxml.jackson.databind.ObjectMapper());
        // Mockito's CALLS_REAL_METHODS instantiation skips instance initializers.
        ReflectionTestUtils.setField(service, "menuCodeToIdMap", new java.util.concurrent.ConcurrentHashMap<>());
    }

    private RoleDefinitionDTO role(String code, String... permissions) {
        return RoleDefinitionDTO.builder().code(code).permissions(List.of(permissions)).build();
    }

    private PermissionDTO registered(long id, String code) {
        PermissionDTO value = new PermissionDTO();
        value.setId(id);
        value.setCode(code);
        return value;
    }

    private void stubRoleResolvable(long roleId) {
        when(roleMapper.findIdByCode(7L, "operator")).thenReturn(roleId);
        when(bindings.findPermissionIdsByRole(roleId)).thenReturn(java.util.Set.of());
        when(bindings.countByRoleAndPermission(eq(roleId), anyLong(), eq(7L))).thenReturn(0);
    }

    // ==================== strict (post-publication reconcile) ====================

    @Test void strictReconcileFailsClosedOnUnresolvedCodeNamingRoleAndCodeAndProviderGap() {
        stubRoleResolvable(3L);
        when(permissions.findByCode("model.sl_sales_return_common.read"))
                .thenThrow(new com.auraboot.framework.application.exception.ResourceNotFoundException("Permission not found: model.sl_sales_return_common.read"));
        when(metaModels.selectList(any())).thenReturn(java.util.Collections.<Model>emptyList());
        assertThatThrownBy(() -> service.reconcileRolePermissions(role("operator", "model.sl_sales_return_common.read"), 7L))
                .isInstanceOf(PluginException.class)
                .hasMessageContaining("Role 'operator'")
                .hasMessageContaining("model.sl_sales_return_common.read")
                .hasMessageContaining("sl_sales_return_common")
                .hasMessageContaining("provider plugin");
        verifyNoInteractions(rolePermissions);
    }

    @Test void strictReconcileNamesOwnerPluginWhenModelExistsButPermissionIsMissing() {
        stubRoleResolvable(3L);
        when(permissions.findByCode("model.sl_sales_return_common.read"))
                .thenThrow(new com.auraboot.framework.application.exception.ResourceNotFoundException("Permission not found: model.sl_sales_return_common.read"));
        Model model = new Model();
        model.setCode("sl_sales_return_common");
        model.setPluginPid("pp-1");
        when(metaModels.selectList(any())).thenReturn(List.of(model));
        PluginRecord owner = new PluginRecord();
        owner.setPid("pp-1");
        owner.setPluginId("com.auraboot.sales");
        when(pluginRecords.findByPid("pp-1")).thenReturn(owner);
        assertThatThrownBy(() -> service.reconcileRolePermissions(role("operator", "model.sl_sales_return_common.read"), 7L))
                .isInstanceOf(PluginException.class)
                .hasMessageContaining("owned by plugin 'com.auraboot.sales'");
    }

    @Test void strictReconcileBindsResolvedCodes() {
        stubRoleResolvable(3L);
        when(permissions.findByCode("model.work_order.read")).thenReturn(registered(9L, "model.work_order.read"));
        when(rolePermissions.assignPermissionsToRole(3L, List.of(9L))).thenReturn(true);
        assertThatCode(() -> service.reconcileRolePermissions(role("operator", "model.work_order.read"), 7L))
                .doesNotThrowAnyException();
        verify(rolePermissions).assignPermissionsToRole(3L, List.of(9L));
    }

    @Test void strictReconcileFailsClosedWhenBindingFails() {
        stubRoleResolvable(3L);
        when(permissions.findByCode("model.work_order.read")).thenReturn(registered(9L, "model.work_order.read"));
        when(rolePermissions.assignPermissionsToRole(3L, List.of(9L))).thenThrow(new IllegalStateException("db down"));
        assertThatThrownBy(() -> service.reconcileRolePermissions(role("operator", "model.work_order.read"), 7L))
                .isInstanceOf(PluginException.class)
                .hasMessageContaining("model.work_order.read")
                .hasMessageContaining("operator")
                .hasMessageContaining("db down");
    }

    @Test void strictReconcileRejectsRoleItCannotResolve() {
        when(roleMapper.findIdByCode(7L, "operator")).thenReturn(null);
        assertThatThrownBy(() -> service.reconcileRolePermissions(role("operator", "model.work_order.read"), 7L))
                .isInstanceOf(PluginException.class)
                .hasMessageContaining("declared role 'operator'");
        verifyNoInteractions(permissions);
    }

    @Test void registeredSystemAndDataCodesBindWithNoExemptionBranch() {
        // Platform built-ins (source=system) and data-view permissions (resource_type=data)
        // are registered ab_permission rows: they resolve and bind like any declared code.
        stubRoleResolvable(3L);
        when(permissions.findByCode("meta.command.execute")).thenReturn(registered(11L, "meta.command.execute"));
        when(permissions.findByCode("dashboard.read")).thenReturn(registered(12L, "dashboard.read"));
        when(permissions.findByCode("model.inv_daily_stock.read")).thenReturn(registered(13L, "model.inv_daily_stock.read"));
        when(rolePermissions.assignPermissionsToRole(anyLong(), any())).thenReturn(true);
        assertThatCode(() -> service.reconcileRolePermissions(
                role("operator", "meta.command.execute", "dashboard.read", "model.inv_daily_stock.read"), 7L))
                .doesNotThrowAnyException();
        verify(rolePermissions).assignPermissionsToRole(3L, List.of(11L));
        verify(rolePermissions).assignPermissionsToRole(3L, List.of(12L));
        verify(rolePermissions).assignPermissionsToRole(3L, List.of(13L));
    }

    // ==================== deferred (role-import stage) ====================

    @Test void roleStageDefersUnresolvedGeneratedActionsInsteadOfFailing() {
        // Own-plugin model actions are generated after the ROLE stage; deferral is expected.
        // The permission service signals an unregistered code by throwing, not by null.
        when(permissions.findByCode("model.qc_iqc_order.read"))
                .thenThrow(new com.auraboot.framework.application.exception.ResourceNotFoundException("Permission not found: model.qc_iqc_order.read"));
        assertThatCode(() -> service.updateRolePermissions(3L, role("operator", "model.qc_iqc_order.read"), 7L, "pid", false))
                .doesNotThrowAnyException();
        verifyNoInteractions(rolePermissions, bindings);
    }

    @Test void roleStageDefersNullReturningResolutionToo() {
        when(permissions.findByCode("model.qc_iqc_order.read")).thenReturn(null);
        assertThatCode(() -> service.updateRolePermissions(3L, role("operator", "model.qc_iqc_order.read"), 7L, "pid", false))
                .doesNotThrowAnyException();
        verifyNoInteractions(rolePermissions, bindings);
    }

    @Test void strictReconcileFailsClosedWhenResolutionThrows() {
        stubRoleResolvable(3L);
        when(permissions.findByCode("model.work_order.read"))
                .thenThrow(new com.auraboot.framework.application.exception.ResourceNotFoundException("Permission not found: model.work_order.read"));
        when(metaModels.selectList(any())).thenReturn(java.util.Collections.<Model>emptyList());
        assertThatThrownBy(() -> service.reconcileRolePermissions(role("operator", "model.work_order.read"), 7L))
                .isInstanceOf(PluginException.class)
                .hasMessageContaining("Role 'operator'")
                .hasMessageContaining("model.work_order.read")
                .hasMessageContaining("provider plugin");
    }

    @Test void roleStageStillBindsCodesThatResolveAlready() {
        when(permissions.findByCode("model.sl_sales_return_common.read")).thenReturn(registered(9L, "model.sl_sales_return_common.read"));
        when(bindings.countByRoleAndPermission(3L, 9L, 7L)).thenReturn(0);
        when(rolePermissions.assignPermissionsToRole(3L, List.of(9L))).thenReturn(true);
        assertThatCode(() -> service.updateRolePermissions(3L, role("operator", "model.sl_sales_return_common.read"), 7L, "pid", false))
                .doesNotThrowAnyException();
        verify(rolePermissions).assignPermissionsToRole(3L, List.of(9L));
    }

    // ==================== generated-action code parsing ====================

    @Test void modelCodeExtractionCoversCodeShapes() {
        assertThat(PluginResourceImporterImpl.modelCodeOfGeneratedActionPermission("model.sl_sales_return_common.read"))
                .isEqualTo("sl_sales_return_common");
        assertThat(PluginResourceImporterImpl.modelCodeOfGeneratedActionPermission("model.qc_iqc_order.read"))
                .isEqualTo("qc_iqc_order");
        assertThat(PluginResourceImporterImpl.modelCodeOfGeneratedActionPermission("model.work_order.update"))
                .isEqualTo("work_order");
        assertThat(PluginResourceImporterImpl.modelCodeOfGeneratedActionPermission("model.work_order")).isNull();
        assertThat(PluginResourceImporterImpl.modelCodeOfGeneratedActionPermission("model..read")).isNull();
        assertThat(PluginResourceImporterImpl.modelCodeOfGeneratedActionPermission("meta.command.execute")).isNull();
        assertThat(PluginResourceImporterImpl.modelCodeOfGeneratedActionPermission(null)).isNull();
    }

    // ==================== menu parent reference fail-closed ====================

    private MenuDefinitionDTO menu(String code, String parentCode) {
        return MenuDefinitionDTO.builder().code(code).parentCode(parentCode).name("Menu").build();
    }

    @Test void menuWithUnresolvableParentFailsClosedInsteadOfSilentReparentToRoot() {
        when(menus.existsByCode(7L, "child")).thenReturn(false);
        when(menus.findIdByCode(7L, "missing_parent")).thenReturn(null);
        assertThatThrownBy(() -> service.importMenu(menu("child", "missing_parent"), "pid", "import", 7L, ImportRequest.ConflictStrategy.OVERWRITE))
                .isInstanceOf(PluginException.class)
                .hasMessageContaining("child")
                .hasMessageContaining("missing_parent");
        verify(menuService, never()).createMenu(any());
    }

    @Test void menuParentFromEarlierImportedPluginResolvesViaTenantLookup() {
        when(menus.existsByCode(7L, "child")).thenReturn(false);
        when(menus.findIdByCode(7L, "pr_root")).thenReturn(5L);
        Menu created = new Menu();
        created.setId(6L);
        when(menuService.createMenu(any())).thenReturn(created);
        when(menus.updatePluginFields(any(), any(), any(), anyLong())).thenReturn(1);
        assertThatCode(() -> service.importMenu(menu("child", "pr_root"), "pid", "import", 7L, ImportRequest.ConflictStrategy.OVERWRITE))
                .doesNotThrowAnyException();
        ArgumentCaptor<Menu> captor = ArgumentCaptor.forClass(Menu.class);
        verify(menuService).createMenu(captor.capture());
        assertThat(captor.getValue().getParentId()).isEqualTo(5L);
    }
}
