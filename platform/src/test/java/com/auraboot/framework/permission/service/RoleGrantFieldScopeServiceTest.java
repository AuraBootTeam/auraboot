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
import java.util.Map;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.mockito.ArgumentCaptor;

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
    @AfterEach void clear() { MetaContext.clear(); TransactionSynchronizationManager.clear(); }

    private List<RoleGrantFieldScopeService.BusinessBoundary> boundaries() {
        return List.of(new RoleGrantFieldScopeService.BusinessBoundary("bom.project.manage", "bom_project", "update"));
    }

    @Test void legacyOrMismatchedBusinessBoundaryCannotAdvertiseEditableFieldProtection() {
        assertThat(service.catalog(role, List.of())).isEmpty();
        permission.setAction("approve");
        assertThat(service.catalog(role, boundaries())).isEmpty();
        verifyNoInteractions(models);
    }

    private List<RoleGrantFieldScopeService.BusinessBoundary> multipleRoots() {
        when(models.getModelDefinition("bom_line")).thenReturn(Optional.of(ModelDefinition.builder().displayName("BOM line").build()));
        when(models.getModelFields("bom_line")).thenReturn(List.of(FieldDefinition.builder().code("excluded").displayName("Excluded").build()));
        return List.of(new RoleGrantFieldScopeService.BusinessBoundary("bom.project.manage", "bom_project", "update"),
                new RoleGrantFieldScopeService.BusinessBoundary("bom.project.manage", "bom_line", "update"));
    }

    private String applied(List<com.auraboot.framework.permission.dto.RoleGrantFields.Reviewed> changes,
                           List<RoleGrantFieldScopeService.BusinessBoundary> roots) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(grants.updateDirectFieldScope(any(),any(),any(),any(),any(),any())).thenReturn(1);
        service.apply(role,changes,roots);
        var scope = ArgumentCaptor.forClass(String.class);
        verify(grants).updateDirectFieldScope(eq(7L),eq(5L),eq("binding"),scope.capture(),eq(9L),isNull());
        return scope.getValue();
    }

    @Test void oneBindingExposesOnlyItsRegisteredBusinessObjectRoots() {
        var result = service.catalog(role,multipleRoots());
        assertThat(result).extracting(b -> b.modelCode()).containsExactly("bom_line","bom_project");
        assertThat(result).allSatisfy(b -> {
            assertThat(b.bindingPid()).isEqualTo("binding");assertThat(b.legacy()).isTrue();assertThat(b.configured()).isTrue();
        });
        assertThatThrownBy(() -> service.preview(role,List.of(new Change("binding",null,null,List.of(),List.of(),"unregistered")),
                Set.of(permission.getCode()),Set.of(),multipleRoots())).isInstanceOf(AccessDeniedException.class);
    }

    @Test void narrowingOneLegacyObjectPreservesOtherRootsRatherThanRevokingThem() {
        var roots = multipleRoots();
        var changes = service.preview(role,List.of(new Change("binding",null,null,List.of("excluded"),List.of("excluded"),"bom_line")),
                Set.of(permission.getCode()),Set.of(),roots);
        String encoded = applied(changes,roots);
        assertThat(BusinessGrantFieldScope.parse(new ObjectMapper(),encoded,"bom_project").unrestricted()).isTrue();
        assertThat(BusinessGrantFieldScope.parse(new ObjectMapper(),encoded,"bom_line").writable()).containsExactly("excluded");
        assertThat(BusinessGrantFieldScope.select(new ObjectMapper(),encoded,"future_model")).isEmpty();
    }

    @Test void bothObjectChangesMergeIntoOneBindingWriteAndOldClientsStillChooseTheCanonicalModel() {
        var roots = multipleRoots();
        var changes = service.preview(role,List.of(new Change("binding",List.of("remark"),List.of()),
                new Change("binding",null,null,List.of("excluded"),List.of("excluded"),"bom_line")),Set.of(permission.getCode()),Set.of(),roots);
        String encoded = applied(changes,roots);
        assertThat(BusinessGrantFieldScope.parse(new ObjectMapper(),encoded,"bom_project").readable()).containsExactly("remark");
        assertThat(BusinessGrantFieldScope.parse(new ObjectMapper(),encoded,"bom_project").writable()).isEmpty();
        assertThat(BusinessGrantFieldScope.parse(new ObjectMapper(),encoded,"bom_line").writable()).containsExactly("excluded");
    }

    @Test void singleModelStorageDoesNotSilentlyAuthorizeAnotherObject() {
        var roots = multipleRoots();
        grant.setFieldScope(Map.of("modelCode","bom_project","readFields",List.of("remark"),"writeFields",List.of()));
        var line = service.catalog(role,roots).stream().filter(b -> b.modelCode().equals("bom_line")).findFirst().orElseThrow();
        assertThat(line.configured()).isFalse();assertThat(line.legacy()).isFalse();assertThat(line.readFields()).isEmpty();
        var changes = service.preview(role,List.of(new Change("binding",null,null,List.of(),List.of(),"bom_line")),Set.of(permission.getCode()),Set.of(),roots);
        assertThat(changes).hasSize(1);
        String encoded=applied(changes,roots);
        assertThat(BusinessGrantFieldScope.parse(new ObjectMapper(),encoded,"bom_project").readable()).containsExactly("remark");
        assertThat(BusinessGrantFieldScope.parse(new ObjectMapper(),encoded,"bom_line").writable()).isEmpty();
    }

    @Test void duplicateObjectOrChangedSecondMetadataCannotPartiallyApply() {
        var roots=multipleRoots(); var code=permission.getCode();
        var line=new Change("binding",null,null,List.of("excluded"),List.of(),"bom_line");
        assertThatThrownBy(() -> service.preview(role,List.of(line,line),Set.of(code),Set.of(),roots)).isInstanceOf(AccessDeniedException.class);
        var changes=service.preview(role,List.of(new Change("binding",List.of("remark"),List.of()),line),Set.of(code),Set.of(),roots);
        when(models.getModelFields("bom_line")).thenReturn(List.of(FieldDefinition.builder().code("changed").build()));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> service.apply(role,changes,roots)).isInstanceOf(com.auraboot.framework.exception.BusinessException.class);
        verify(grants,never()).updateDirectFieldScope(any(),any(),any(),any(),any(),any());
    }

    @ParameterizedTest @ValueSource(strings = {"operation", "data"})
    void businessBindingsExposeOnlyRealModelFieldsWithoutWriting(String resourceType) {
        permission.setResourceType(resourceType);
        var result = service.catalog(role, boundaries());
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
        assertThat(service.catalog(role, boundaries())).isEmpty();
        verifyNoInteractions(models);
    }

    @Test void missingModelNeverInventsFieldCandidates() {
        when(models.getModelDefinition("bom_project")).thenReturn(Optional.empty());
        assertThat(service.catalog(role, boundaries())).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"snapshot", "external", "deny"})
    void businessFieldEditsCannotReplaceAnotherSourceOrNegativeBinding(String source) {
        if (source.equals("snapshot")) grant.setSnapshotId(991L);
        if (source.equals("external")) grant.setSourceRef("package:owner");
        if (source.equals("deny")) grant.setGrantType("DENY");
        assertThat(service.catalog(role, boundaries())).singleElement().satisfies(binding -> assertThat(binding.editable()).isFalse());
        assertThatThrownBy(() -> service.preview(role, List.of(new Change("binding", List.of("remark"), List.of())), Set.of(permission.getCode()), Set.of(), boundaries()))
                .isInstanceOf(AccessDeniedException.class);
        verify(grants, never()).updateDirectFieldScope(any(), any(), any(), any(), any(), any());
    }

    @Test void newlySelectedBusinessGrantNeedsMetadataAndCannotEditAnUnselectedPermission() {
        when(grants.findByRole(5L)).thenReturn(List.of());
        String code = permission.getCode();
        var candidate = service.candidates(role, Set.of(code), Set.of(code), boundaries()).getFirst();
        assertThat(candidate.bindingPid()).isNull();
        var missingRevision = new Change(null, code, null, List.of("remark"), List.of());
        assertThatThrownBy(() -> service.preview(role, List.of(missingRevision), Set.of(code), Set.of(code), boundaries()))
                .isInstanceOf(AccessDeniedException.class);
        var reviewed = new Change(null, code, candidate.metadataRevision(), List.of("remark"), List.of());
        assertThat(service.preview(role, List.of(reviewed), Set.of(code), Set.of(code), boundaries())).hasSize(1);
        assertThatThrownBy(() -> service.preview(role, List.of(reviewed), Set.of(), Set.of(), boundaries()))
                .isInstanceOf(AccessDeniedException.class);
        verify(grants, never()).updateDirectFieldScope(any(), any(), any(), any(), any(), any());
    }
}
