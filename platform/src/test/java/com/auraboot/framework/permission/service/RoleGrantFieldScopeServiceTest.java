package com.auraboot.framework.permission.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.permission.dto.PermissionDTO;
import com.auraboot.framework.permission.dto.RoleGrantFields.Change;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.entity.RolePermission;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoleGrantFieldScopeServiceTest {
    final RolePermissionMapper grants = mock(RolePermissionMapper.class);
    final PermissionService permissions = mock(PermissionService.class);
    final MetaModelService models = mock(MetaModelService.class);
    final DataPermissionAuthoringAccess policies = mock(DataPermissionAuthoringAccess.class);
    final RoleAuthorizationAuthoringAccess authoring = mock(RoleAuthorizationAuthoringAccess.class);
    final RoleGrantFieldScopeService service = new RoleGrantFieldScopeService(grants, permissions, models, policies, authoring, new ObjectMapper());
    final Role role = new Role();
    final PermissionDTO permission = new PermissionDTO();
    final RolePermission grant = new RolePermission();

    @BeforeEach void fixture() {
        MetaContext.setContext(7L, 9L, "actor", "Actor");
        role.setId(5L); role.setTenantId(7L); role.setPid("role");
        permission.setId(11L); permission.setTenantId(7L); permission.setCode("bom.project.manage");
        permission.setResourceCode("bom_project"); permission.setResourceType("operation"); permission.setAction("manage");
        grant.setPid("binding"); grant.setRoleId(5L); grant.setTenantId(7L); grant.setPermissionId(11L); grant.setGrantType("GRANT");
        when(permissions.findAllActive()).thenReturn(List.of(permission));
        when(grants.findByRole(5L)).thenReturn(List.of(grant));
        when(models.getModelDefinition("bom_project")).thenReturn(Optional.of(ModelDefinition.builder().displayName("BOM project").build()));
        when(models.getModelFields("bom_project")).thenReturn(List.of(
                FieldDefinition.builder().code("remark").displayName("Remark").build(),
                FieldDefinition.builder().code("pid").primaryKey(true).build()));
    }
    @AfterEach void clear() { MetaContext.clear(); }

    @ParameterizedTest @ValueSource(strings = {"operation", "data"})
    void businessBindingsExposeOnlyRealModelFieldsWithoutWriting(String resourceType) {
        permission.setResourceType(resourceType);
        var result = service.catalog(role);
        assertThat(result).singleElement().satisfies(binding -> {
            assertThat(binding.permissionCode()).isEqualTo("bom.project.manage");
            assertThat(binding.fields()).extracting(field -> field.code()).containsExactly("remark");
            assertThat(binding.editable()).isTrue(); assertThat(binding.legacy()).isTrue();
        });
        verify(grants, never()).updateDirectFieldScope(any(), any(), any(), any(), any(), any());
    }

    @ParameterizedTest @ValueSource(strings = {"MENU", "API", "model"})
    void unrelatedPermissionTypesDoNotBecomeBusinessFieldAuthoring(String resourceType) {
        permission.setResourceType(resourceType);
        assertThat(service.catalog(role)).isEmpty();
        verifyNoInteractions(models);
    }

    @Test void missingModelNeverInventsFieldCandidates() {
        when(models.getModelDefinition("bom_project")).thenReturn(Optional.empty());
        assertThat(service.catalog(role)).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"snapshot", "external", "deny"})
    void businessFieldEditsCannotReplaceAnotherSourceOrNegativeBinding(String source) {
        if (source.equals("snapshot")) grant.setSnapshotId(991L);
        if (source.equals("external")) grant.setSourceRef("package:owner");
        if (source.equals("deny")) grant.setGrantType("DENY");
        assertThat(service.catalog(role)).singleElement().satisfies(binding -> assertThat(binding.editable()).isFalse());
        assertThatThrownBy(() -> service.preview(role, List.of(new Change("binding", List.of("remark"), List.of())), Set.of(permission.getCode())))
                .isInstanceOf(AccessDeniedException.class);
        verify(grants, never()).updateDirectFieldScope(any(), any(), any(), any(), any(), any());
    }

    @Test void newlySelectedBusinessGrantNeedsMetadataAndCannotEditAnUnselectedPermission() {
        when(grants.findByRole(5L)).thenReturn(List.of());
        String code = permission.getCode();
        var candidate = service.candidates(role, Set.of(code), Set.of(code)).getFirst();
        assertThat(candidate.bindingPid()).isNull();
        var missingRevision = new Change(null, code, null, List.of("remark"), List.of());
        assertThatThrownBy(() -> service.preview(role, List.of(missingRevision), Set.of(code), Set.of(code)))
                .isInstanceOf(AccessDeniedException.class);
        var reviewed = new Change(null, code, candidate.metadataRevision(), List.of("remark"), List.of());
        assertThat(service.preview(role, List.of(reviewed), Set.of(code), Set.of(code))).hasSize(1);
        assertThatThrownBy(() -> service.preview(role, List.of(reviewed), Set.of(), Set.of()))
                .isInstanceOf(AccessDeniedException.class);
        verify(grants, never()).updateDirectFieldScope(any(), any(), any(), any(), any(), any());
    }
}
