package com.auraboot.framework.plugin.service.impl;
import com.auraboot.framework.plugin.dto.imports.*;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.auraboot.framework.meta.service.FieldMaskService;
import com.auraboot.framework.permission.capability.CapabilityRegistryService;
import com.auraboot.framework.permission.service.*;
import com.auraboot.framework.permission.dto.PermissionDTO;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.entity.RolePermission;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Permission imports grant fresh creations to this tenant's admin without restoring revoked bindings. */
class PluginAccessResourceImporterTest {
    final PluginResourceImporter resources=mock(PluginResourceImporter.class);
    final FieldMaskService masks=mock(FieldMaskService.class);
    final CapabilityRegistryService capabilities=mock(CapabilityRegistryService.class);
    final PermissionService permissions=mock(PermissionService.class);
    final UserPermissionService users=mock(UserPermissionService.class);
    final RoleService roles=mock(RoleService.class);
    final RolePermissionMapper bindings=mock(RolePermissionMapper.class);
    final PluginAccessResourceImporter.GenerateMenuI18nRecordsOperation0 menuI18n=mock(PluginAccessResourceImporter.GenerateMenuI18nRecordsOperation0.class);
    final PluginAccessResourceImporter.GeneratePermissionI18nRecordsOperation1 permissionI18n=mock(PluginAccessResourceImporter.GeneratePermissionI18nRecordsOperation1.class);
    final PluginAccessResourceImporter.SaveOrUpdatePluginResourceOperation save=mock(PluginAccessResourceImporter.SaveOrUpdatePluginResourceOperation.class);
    final PluginAccessResourceImporter.CaptureImportSnapshotOperation snapshot=mock(PluginAccessResourceImporter.CaptureImportSnapshotOperation.class);
    final PluginAccessResourceImporter importer=new PluginAccessResourceImporter(resources,masks,capabilities,permissions,users,roles,bindings,menuI18n,permissionI18n,save,snapshot);
    PermissionDefinitionDTO permission(String code) { return PermissionDefinitionDTO.builder().code(code).build(); }
    PluginResource trackedResource(String action, Long permissionId, String code) {
        var resource=new PluginResource(); resource.setAction(action); resource.setResourceId(permissionId); resource.setResourceCode(code); return resource;
    }
    Role tenantAdmin() { var admin=new Role(); admin.setCode("tenant_admin"); admin.setId(7L); return admin; }
    @Test void createdPermissionsBindToThisTenantAdminAndInvalidateCaches() {
        when(roles.findByTenantId(42L)).thenReturn(List.of(tenantAdmin()));
        when(permissions.findRolePermissions(7L)).thenReturn(List.of());
        var manifest=new PluginManifestExtended();
        manifest.setPermissions(List.of(permission("invoice.read"),permission("invoice.manage")));
        when(resources.importPermission(any(),eq("p"),eq("i"),eq(42L),any()))
                .thenReturn(trackedResource("CREATE",101L,"invoice.read"))
                .thenReturn(trackedResource("CREATE",102L,"invoice.manage"));
        importer.importPermissions(manifest,new ImportRequest(),new ImportExecuteResult(),"p","i",42L);
        verify(bindings,times(2)).insert(any(RolePermission.class));
        verify(bindings).insert(argThat((RolePermission b) -> Long.valueOf(42L).equals(b.getTenantId()) && Long.valueOf(7L).equals(b.getRoleId()) && Long.valueOf(101L).equals(b.getPermissionId())));
        verify(bindings).insert(argThat((RolePermission b) -> Long.valueOf(7L).equals(b.getRoleId()) && Long.valueOf(102L).equals(b.getPermissionId())));
        verify(users).evictRoleUsers(42L,7L);
        verify(users,atLeastOnce()).evictPermissionDefinitions(42L);
    }
    @Test void createdPermissionsAlreadyBoundToAdminAreNotDuplicated() {
        when(roles.findByTenantId(42L)).thenReturn(List.of(tenantAdmin()));
        var bound=new PermissionDTO(); bound.setId(101L);
        when(permissions.findRolePermissions(7L)).thenReturn(List.of(bound));
        var manifest=new PluginManifestExtended(); manifest.setPermissions(List.of(permission("invoice.read")));
        when(resources.importPermission(any(),eq("p"),eq("i"),eq(42L),any())).thenReturn(trackedResource("CREATE",101L,"invoice.read"));
        importer.importPermissions(manifest,new ImportRequest(),new ImportExecuteResult(),"p","i",42L);
        verify(bindings,never()).insert(any(RolePermission.class));
        verify(users,never()).evictRoleUsers(any(),any());
    }
    @Test void reimportUpdatesAndSkipsNeverGrantOrRestoreRevokedBindings() {
        var manifest=new PluginManifestExtended();
        manifest.setPermissions(List.of(permission("invoice.read"),permission("invoice.manage")));
        when(resources.importPermission(any(),eq("p"),eq("i"),eq(42L),any()))
                .thenReturn(trackedResource("UPDATE",101L,"invoice.read"))
                .thenReturn(trackedResource("SKIP",null,"invoice.manage"));
        importer.importPermissions(manifest,new ImportRequest(),new ImportExecuteResult(),"p","i",42L);
        verifyNoInteractions(bindings, permissions, roles);
    }
    @Test void missingTenantAdminDoesNotFailImportOrCreateBindings() {
        when(roles.findByTenantId(42L)).thenReturn(List.of());
        var manifest=new PluginManifestExtended(); manifest.setPermissions(List.of(permission("invoice.read")));
        when(resources.importPermission(any(),eq("p"),eq("i"),eq(42L),any())).thenReturn(trackedResource("CREATE",101L,"invoice.read"));
        assertThatCode(() -> importer.importPermissions(manifest,new ImportRequest(),new ImportExecuteResult(),"p","i",42L)).doesNotThrowAnyException();
        verify(bindings,never()).insert(any(RolePermission.class));
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
        when(roles.findByTenantId(42L)).thenReturn(List.of());
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
