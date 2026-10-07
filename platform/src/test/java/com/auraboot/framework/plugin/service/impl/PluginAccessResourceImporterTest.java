package com.auraboot.framework.plugin.service.impl;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.dto.imports.*;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.auraboot.framework.meta.service.FieldMaskService;
import com.auraboot.framework.permission.capability.CapabilityRegistryService;
import com.auraboot.framework.permission.service.*;
import com.auraboot.framework.permission.dto.PermissionDTO;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.*;

/** Permission imports keep role bindings tenant-scoped and invalidate authorization caches. */
class PluginAccessResourceImporterTest {
    final PluginResourceImporter resources=mock(PluginResourceImporter.class);
    final FieldMaskService masks=mock(FieldMaskService.class);
    final CapabilityRegistryService capabilities=mock(CapabilityRegistryService.class);
    final PluginAccessResourceImporter.BindImportedPermissionCodesOperation bindCodes=mock(PluginAccessResourceImporter.BindImportedPermissionCodesOperation.class);
    final PermissionService permissions=mock(PermissionService.class);
    final UserPermissionService users=mock(UserPermissionService.class);
    final RoleService roles=mock(RoleService.class);
    final RolePermissionMapper bindings=mock(RolePermissionMapper.class);
    final PluginAccessResourceImporter.GenerateMenuI18nRecordsOperation0 menuI18n=mock(PluginAccessResourceImporter.GenerateMenuI18nRecordsOperation0.class);
    final PluginAccessResourceImporter.GeneratePermissionI18nRecordsOperation1 permissionI18n=mock(PluginAccessResourceImporter.GeneratePermissionI18nRecordsOperation1.class);
    final PluginAccessResourceImporter.SaveOrUpdatePluginResourceOperation save=mock(PluginAccessResourceImporter.SaveOrUpdatePluginResourceOperation.class);
    final PluginAccessResourceImporter.CaptureImportSnapshotOperation snapshot=mock(PluginAccessResourceImporter.CaptureImportSnapshotOperation.class);
    final PluginAccessResourceImporter importer=new PluginAccessResourceImporter(resources,masks,capabilities,bindCodes,users,menuI18n,permissionI18n,save,snapshot);
    PermissionDefinitionDTO permission(String code) { return PermissionDefinitionDTO.builder().code(code).build(); }
    @Test void declarationsRegisterWithoutGrantingOrRestoringRevokedBindings() {
        var manifest = new PluginManifestExtended();
        manifest.setPermissions(List.of(permission("invoice.read")));
        importer.importPermissions(manifest, new ImportRequest(), new ImportExecuteResult(), "p", "i", 42L);
        verify(resources).importPermission(eq(manifest.getPermissions().get(0)), eq("p"), eq("i"), eq(42L), any());
        verify(users).evictPermissionDefinitions(42L);
        verify(bindCodes).execute(List.of("invoice.read"), null); // manifest.pluginId unset in this fixture
        verifyNoInteractions(bindings, permissions, roles);
    }
    @Test void absentDeclarationsDoNotTouchGrantsOrAuthorizationCaches() {
        importer.importPermissions(new PluginManifestExtended(), new ImportRequest(), new ImportExecuteResult(), "p", "i", 42L);
        verifyNoInteractions(resources, bindings, permissions, roles, users);
    }
    @Test void importsPreserveSnapshotsCountsAndMenuParentOrder() {
        var resource=new PluginResource(); resource.setAction("CREATE"); resource.setResourcePid("r-42");
        when(resources.importPermission(any(),eq("p"),eq("i"),eq(42L),any())).thenReturn(resource);
        when(resources.importRole(any(),eq("p"),eq("i"),eq(42L),any())).thenReturn(resource);
        when(resources.importMenu(any(),eq("p"),eq("i"),eq(42L),any())).thenReturn(resource);
        var parent=MenuDefinitionDTO.builder().code("finance").build(); var child=MenuDefinitionDTO.builder().code("invoice").parentCode("finance").build();
        var m=new PluginManifestExtended(); m.setPermissions(List.of(permission("read"),new PermissionDefinitionDTO()));
        m.setRoles(List.of(RoleDefinitionDTO.builder().code("operator").build(),new RoleDefinitionDTO())); m.setMenus(List.of(child,parent));
        var q=new ImportRequest(); var result=new ImportExecuteResult();
        importer.importPermissions(m,q,result,"p","i",42L); importer.importRoles(m,q,result,"p","i",42L); importer.importMenus(m,q,result,"p","i",42L);
        assertThat(result.getTotalResourceCount()).isEqualTo(4); verify(save,times(4)).execute(resource,42L); verify(snapshot,times(4)).execute(eq(resource),any());
        var order=inOrder(resources); order.verify(resources).importPermission(eq(m.getPermissions().get(0)),eq("p"),eq("i"),eq(42L),any());
        order.verify(resources).importRole(eq(m.getRoles().get(0)),eq("p"),eq("i"),eq(42L),any());
        order.verify(resources).importMenu(eq(parent),eq("p"),eq("i"),eq(42L),any()); order.verify(resources).importMenu(eq(child),eq("p"),eq("i"),eq(42L),any());
        verify(menuI18n).execute(List.of(parent,child),42L); verify(permissionI18n).execute(m.getPermissions(),42L);
    }
    @Test void cyclicMenusRemainVisibleAndOneBrokenRoleDoesNotSuppressTheNext() {
        var a=MenuDefinitionDTO.builder().code("a").parentCode("b").build(); var b=MenuDefinitionDTO.builder().code("b").parentCode("a").build();
        assertThat(importer.topologicalSortMenus(List.of(a,b))).containsExactly(a,b);
        var r1=RoleDefinitionDTO.builder().code("one").build(); var r2=RoleDefinitionDTO.builder().code("two").build();
        var m=new PluginManifestExtended(); m.setRoles(Arrays.asList(null,new RoleDefinitionDTO(),r1,r2));
        doThrow(new IllegalStateException("stale")).when(resources).reconcileRolePermissions(r1,42L);
        importer.reconcileRolePermissionBindings(m,42L); verify(resources).reconcileRolePermissions(r2,42L);
    }
    @Test void masksAndCapabilitiesKeepExemptionsAndSkipInvalidSeeds() {
        var mask=FieldMaskDefinitionDTO.builder().modelCode("invoice").fieldCode("amount").maskType("PARTIAL").maskPattern("last4")
                .replacementChar("#").applyToExport(false).exemptRoles("finance_admin").exemptPermissionCodes("invoice.unmask").build();
        var capability=CapabilityDefinitionDTO.builder().code("invoice.manage").includes(List.of("invoice.read","invoice.write")).build();
        var m=new PluginManifestExtended(); m.setFieldMasks(List.of(new FieldMaskDefinitionDTO(),mask)); m.setCapabilities(List.of(new CapabilityDefinitionDTO(),capability));
        importer.importFieldMasks(m);
        assertThatThrownBy(() -> importer.importCapabilities(m)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(capabilities);
        m.setCapabilities(List.of(capability));
        importer.importCapabilities(m);
        verify(masks).saveConfig(argThat(c -> c.getModelCode().equals("invoice") && c.getFieldCode().equals("amount") && c.getReplacementChar().equals("#") && !c.getApplyToExport() && c.getExemptRoles().equals("finance_admin") && c.getExemptPermissionCodes().equals("invoice.unmask")));
        verify(capabilities).saveDefinition(capability);
    }
}

/** The binder materializes imported codes onto wildcard roles (fresh-stack admin path). */
class ImportedPermissionWildcardBinderTest {
    final com.auraboot.framework.rbac.mapper.RolePermissionMapper bindings=mock(com.auraboot.framework.rbac.mapper.RolePermissionMapper.class);
    final PermissionService permissions=mock(PermissionService.class);
    final UserPermissionService users=mock(UserPermissionService.class);
    final ImportedPermissionWildcardBinder binder=new ImportedPermissionWildcardBinder(bindings, permissions, users);

    @org.junit.jupiter.api.Test
    void materializesImportedCodesOntoWildcardRoles() {
        MetaContext.setContext(100L, 1L, "u-pid", "tester");
        MetaContext.setMemberId(5L);
        try {
            var role = new com.auraboot.framework.rbac.entity.Role();
            role.setId(9L); role.setCode("tenant_admin");
            when(bindings.findWildcardRoles(100L)).thenReturn(List.of(role));
            var dto = new PermissionDTO(); dto.setId(51L); dto.setCode("model.page_schema.read");
            when(permissions.findByCode("model.page_schema.read")).thenReturn(dto);
            when(permissions.findRolePermissions(9L)).thenReturn(List.of());

            binder.bind(List.of("model.page_schema.read"), "fixture");

            verify(bindings).insert(any(com.auraboot.framework.rbac.entity.RolePermission.class));
            verify(users).evictPermissionDefinitions(100L);
            verify(users).evictRoleUsers(100L, 9L);
        } finally { MetaContext.clear(); }
    }
    @org.junit.jupiter.api.Test
    void skipsCodesNotYetRegisteredWithoutFailingTheImport() {
        MetaContext.setContext(100L, 1L, "u-pid", "tester");
        MetaContext.setMemberId(5L);
        try {
            var role = new com.auraboot.framework.rbac.entity.Role();
            role.setId(9L); role.setCode("tenant_admin");
            when(bindings.findWildcardRoles(100L)).thenReturn(List.of(role));
            when(permissions.findByCode("later.plugin.code")).thenReturn(null);

            binder.bind(List.of("later.plugin.code"), "fixture");

            verify(bindings, never()).insert(any(com.auraboot.framework.rbac.entity.RolePermission.class));
            verify(users, never()).evictPermissionDefinitions(any());
        } finally { MetaContext.clear(); }
    }
}
