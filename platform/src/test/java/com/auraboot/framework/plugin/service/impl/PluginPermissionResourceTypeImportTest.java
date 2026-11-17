package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.permission.dto.PermissionDTO;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.PermissionService;
import com.auraboot.framework.permission.service.RolePermissionService;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.dto.imports.PermissionDefinitionDTO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Definition publication must not erase classification or rewrite role grants. */
class PluginPermissionResourceTypeImportTest {
    private final PluginResourceImporterImpl facade = mock(PluginResourceImporterImpl.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final PermissionMapper definitions = mock(PermissionMapper.class);
    private final RolePermissionService grants = mock(RolePermissionService.class);
    private final PluginAccessPolicyImporter importer = new PluginAccessPolicyImporter(facade,permissions,null,
            grants,null,null,null,null,null,definitions,null,null,null,mock(Logger.class),mock(PluginImportSupport.class));

    private void update(String declaredType,String previousType,String expectedType) {
        var dto = new PermissionDefinitionDTO(); dto.setCode("model.project.read"); dto.setName("Read projects");
        dto.setResourceCode("project"); dto.setAction("read"); dto.setResourceType(declaredType);
        var previous = new PermissionDTO(); previous.setCode(dto.getCode()); previous.setPid("existing-permission");
        previous.setResourceType(previousType);
        when(facade.checkPermissionExists(42L,dto.getCode())).thenReturn(true);
        when(permissions.findByCode(dto.getCode())).thenReturn(previous);
        var ledger = importer.importPermission(dto,"plugin","import",42L,ImportRequest.ConflictStrategy.OVERWRITE);
        var type = ArgumentCaptor.forClass(String.class);
        verify(definitions).updateForPluginImport(eq("Read projects"),isNull(),isNull(),type.capture(),
                eq("project"),eq("read"),isNull(),isNull(),isNull(),isNull(),isNull(),eq("plugin"),eq(42L),eq(dto.getCode()));
        assertThat(type.getValue()).isEqualTo(expectedType);
        assertThat(ledger.getResourcePid()).isEqualTo("existing-permission");
        verifyNoInteractions(grants);
    }
    @Test void omittedTypePreservesExistingModelClassification() { update(null,"model","model"); }
    @Test void explicitOwnerTypeCanUpgradeAnUntypedDefinitionInPlace() { update("model",null,"model"); }
    @Test void untypedLegacyDefinitionsAreNotGuessedFromPermissionNames() { update(null,null,null); }
    @Test void newModelPermissionUsesTheExplicitOwnerClassification() {
        var dto = new PermissionDefinitionDTO(); dto.setCode("model.project.read"); dto.setName("Read projects");
        dto.setResourceCode("project"); dto.setAction("read"); dto.setResourceType("model");
        var created = new PermissionDTO(); created.setPid("new-permission"); created.setId(9L);
        when(permissions.create(any())).thenReturn(created);
        var ledger = importer.importPermission(dto,"plugin","import",42L,ImportRequest.ConflictStrategy.OVERWRITE);
        var request = ArgumentCaptor.forClass(com.auraboot.framework.permission.dto.PermissionCreateRequest.class);
        verify(permissions).create(request.capture());
        assertThat(request.getValue().getResourceType()).isEqualTo("model");
        assertThat(request.getValue().getResourceCode()).isEqualTo("project");
        assertThat(request.getValue().getAction()).isEqualTo("read");
        assertThat(ledger.getResourcePid()).isEqualTo("new-permission");
        verifyNoInteractions(grants);
    }

    @Test void omittedResourceAndActionPreserveThePublishedBoundary() {
        var dto = new PermissionDefinitionDTO(); dto.setCode("model.project.read"); dto.setName("Read projects");
        var previous = new PermissionDTO(); previous.setCode(dto.getCode()); previous.setPid("existing-permission");
        previous.setResourceType("data"); previous.setResourceCode("project"); previous.setAction("read");
        when(facade.checkPermissionExists(42L,dto.getCode())).thenReturn(true);
        when(permissions.findByCode(dto.getCode())).thenReturn(previous);
        var ledger = importer.importPermission(dto,"plugin","import",42L,ImportRequest.ConflictStrategy.OVERWRITE);
        verify(definitions).updateForPluginImport(eq("Read projects"),isNull(),isNull(),eq("data"),
                eq("project"),eq("read"),isNull(),isNull(),isNull(),isNull(),isNull(),eq("plugin"),eq(42L),eq(dto.getCode()));
        assertThat(ledger.getResourcePid()).isEqualTo("existing-permission");
        verifyNoInteractions(grants);
    }

}
