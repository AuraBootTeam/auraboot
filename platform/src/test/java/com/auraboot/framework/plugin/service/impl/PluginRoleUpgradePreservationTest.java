package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.plugin.dto.imports.*;
import com.auraboot.framework.permission.dto.PermissionDTO;
import com.auraboot.framework.permission.service.PermissionService;
import com.auraboot.framework.permission.service.RolePermissionService;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Map;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.auraboot.framework.plugin.mapper.PluginResourceMapper;
import com.auraboot.framework.permission.service.DataScopeService;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.auraboot.framework.plugin.exception.PluginException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PluginRoleUpgradePreservationTest {
    private final PluginResourceImporterImpl importer = mock(PluginResourceImporterImpl.class, CALLS_REAL_METHODS);
    private final RoleMapper roles = mock(RoleMapper.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final RolePermissionService grants = mock(RolePermissionService.class);
    private final RolePermissionMapper bindings = mock(RolePermissionMapper.class);
    private final PluginResourceMapper resources = mock(PluginResourceMapper.class);
    private final DataScopeService scopes = mock(DataScopeService.class);
    private final UserRoleService members = mock(UserRoleService.class);

    PluginRoleUpgradePreservationTest() {
        ReflectionTestUtils.setField(importer, "pluginResourceMapper", resources);
        ReflectionTestUtils.setField(importer, "dataScopeService", scopes);
        ReflectionTestUtils.setField(importer, "userRoleService", members);
        ReflectionTestUtils.setField(importer, "roleMapper", roles);
        ReflectionTestUtils.setField(importer, "permissionService", permissions);
        ReflectionTestUtils.setField(importer, "rolePermissionService", grants);
        ReflectionTestUtils.setField(importer, "rolePermissionMapper", bindings);
        when(importer.checkRoleExists(7L, "quote_reader")).thenReturn(true);
        when(roles.findIdByCode(7L, "quote_reader")).thenReturn(3L);
        when(roles.findPidByCode(7L, "quote_reader")).thenReturn("existing-role");
        PermissionDTO permission = new PermissionDTO(); permission.setId(9L); permission.setCode("quote.read");
        when(permissions.findByCode("quote.read")).thenReturn(permission);
    }

    @Test void overwriteCannotRestoreARevokedGrantOrReplaceExistingRoleScope() {
        var template = RoleDefinitionDTO.builder().code("quote_reader").name("Updated template")
                .defaultDataScopeType("all").permissions(List.of("quote.read")).build();
        var result = importer.importRole(template, "plugin", "upgrade", 7L, ImportRequest.ConflictStrategy.OVERWRITE);
        verifyNoInteractions(grants);
        assertThat(result.getActionEnum()).isEqualTo(ResourceAction.SKIP);
        assertThat(result.getResourcePid()).isEqualTo("existing-role");
    }
    private RoleDefinitionDTO template() {
        return RoleDefinitionDTO.builder().code("quote_reader").permissions(List.of("quote.read"))
                .defaultDataScopeType("all")
                .permissionPolicies(List.of(RolePermissionPolicyDefinitionDTO.builder()
                        .permissionCode("quote.read").conditions(Map.of("limit", 999)).build()))
                .dataScopes(List.of(RoleDataScopeDefinitionDTO.builder()
                        .permissionCode("quote.read").scopeType("all").build()))
                .seedMembers(List.of("CURRENT_MEMBER")).build();
    }

    @Test void existingRoleDoesNotReceiveTemplateConditionsScopesOrSeedMembers() {
        var result = importer.importRole(template(), "plugin", "upgrade", 7L, ImportRequest.ConflictStrategy.OVERWRITE);
        assertThat(result.getActionEnum()).isEqualTo(ResourceAction.SKIP);
        verifyNoInteractions(grants, bindings, scopes, members);
    }

    @Test void publicReconciliationDoesNotRepairAnIntentionallyEmptyRole() {
        assertThat(importer.reconcileRolePermissions(template(), 7L)).isFalse();
        verifyNoInteractions(grants, bindings, scopes, members);
    }

    private PluginResource receipt(ResourceAction action) {
        return com.auraboot.framework.plugin.service.PluginResourceRecords.createResourceRecord(
                "plugin", "upgrade", 7L, ResourceType.ROLE, "existing-role", 3L,
                "quote_reader", "Reader", action, null, null);
    }

    @Test void currentSkipReceiptPreservesExistingRuntimeConfiguration() {
        when(resources.findByTenantPluginAndResource(7L, "plugin", "role", "quote_reader"))
                .thenReturn(receipt(ResourceAction.SKIP));
        assertThat(importer.reconcileImportedRolePermissions(template(), 7L, "plugin", "upgrade")).isFalse();
        verifyNoInteractions(grants, bindings, scopes, members);
    }

    @Test void oldCreateReceiptCannotRestoreRevokedGrantsDuringAnotherImport() {
        var old = receipt(ResourceAction.CREATE); old.setImportId("old-import");
        when(resources.findByTenantPluginAndResource(7L, "plugin", "role", "quote_reader")).thenReturn(old);
        assertThatThrownBy(() -> importer.reconcileImportedRolePermissions(template(), 7L, "plugin", "upgrade"))
                .isInstanceOf(PluginException.class).hasMessageContaining("Current owned role import receipt");
        verifyNoInteractions(grants, bindings, scopes, members);
    }

    @Test void foreignReceiptCannotSeedAResolvedLocalRole() {
        var foreign = receipt(ResourceAction.CREATE); foreign.setTenantId(8L);
        when(resources.findByTenantPluginAndResource(7L, "plugin", "role", "quote_reader")).thenReturn(foreign);
        assertThatThrownBy(() -> importer.reconcileImportedRolePermissions(template(), 7L, "plugin", "upgrade"))
                .isInstanceOf(PluginException.class);
        verifyNoInteractions(grants, bindings, scopes, members);
    }

    @Test void replacedRoleIdentityCannotConsumeAnOldCreateReceipt() {
        var replaced = receipt(ResourceAction.CREATE); replaced.setResourcePid("replaced-role");
        when(resources.findByTenantPluginAndResource(7L, "plugin", "role", "quote_reader")).thenReturn(replaced);
        assertThatThrownBy(() -> importer.reconcileImportedRolePermissions(template(), 7L, "plugin", "upgrade"))
                .isInstanceOf(PluginException.class).hasMessageContaining("Created role identity");
        verifyNoInteractions(grants, bindings, scopes, members);
    }

}
