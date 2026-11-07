package com.auraboot.framework.permission.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.meta.mapper.DataPermissionPolicyMapper;
import com.auraboot.framework.meta.mapper.DataPermissionRoleBindingMapper;
import com.auraboot.framework.permission.capability.CapabilityRegistryService;
import com.auraboot.framework.permission.capability.CapabilitySelectionPreview;
import com.auraboot.framework.permission.capability.CapabilityViewService;
import com.auraboot.framework.permission.dto.DataScopeUpdateRequest;
import com.auraboot.framework.permission.dto.PermissionDTO;
import com.auraboot.framework.permission.dto.RoleAuthorizationDraftRequest;
import com.auraboot.framework.permission.entity.RoleDataScope;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.entity.RolePermission;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.auraboot.framework.rbac.service.RoleService;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoleAuthorizationDraftServiceTest {
    private final RoleAuthorizationAuthoringAccess authoringAccess = mock(RoleAuthorizationAuthoringAccess.class);
    private final RoleFieldPolicyDraftService fieldPolicies = mock(RoleFieldPolicyDraftService.class);
    private final RoleAuthorizationWriteLock writes = mock(RoleAuthorizationWriteLock.class);
    private final RoleService roles = mock(RoleService.class);
    private final RolePermissionMapper grants = mock(RolePermissionMapper.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final CapabilityRegistryService catalog = mock(CapabilityRegistryService.class);
    private final CapabilityViewService capabilities = mock(CapabilityViewService.class);
    private final PermissionMatrixService matrix = mock(PermissionMatrixService.class);
    private final DataScopeService scopes = mock(DataScopeService.class);
    private final DataPermissionRoleBindingMapper bindings = mock(DataPermissionRoleBindingMapper.class);
    private final DataPermissionPolicyMapper policies = mock(DataPermissionPolicyMapper.class);
    private final NativeBusinessBoundaryCatalog nativeBoundaries = mock(NativeBusinessBoundaryCatalog.class);
    private final RoleAuthorizationDraftService service = new RoleAuthorizationDraftService(writes, authoringAccess, fieldPolicies, roles, grants,
            permissions, catalog, capabilities, matrix, scopes,
            JsonMapper.builder().addModule(new JavaTimeModule()).build(), bindings, policies);
    private final Role role = new Role();
    private final RolePermission grant = new RolePermission();

    @BeforeEach void setup() {
        org.springframework.test.util.ReflectionTestUtils.setField(service,"nativeBoundaries",nativeBoundaries);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"roleNavigation",mock(com.auraboot.framework.menu.service.RoleNavigationService.class));
        org.springframework.test.util.ReflectionTestUtils.setField(service,"fieldAccess",mock(RoleFieldAccessService.class));
        org.springframework.test.util.ReflectionTestUtils.setField(service,"grantFields",mock(RoleGrantFieldScopeService.class));
        when(fieldPolicies.preview(any(),any(),any())).thenReturn(new com.auraboot.framework.permission.dto.RoleFieldPolicyPreview(List.of(),List.of()));
        MetaContext.setCurrentTenantId(100L);
        role.setId(1L); role.setPid("role-a"); role.setTenantId(100L);
        grant.setId(10L); grant.setRoleId(1L); grant.setPermissionId(3L); grant.setGrantType("GRANT");
        when(roles.findByPid("role-a")).thenReturn(role);
        when(grants.findByRole(1L)).thenReturn(List.of(grant));
        when(permissions.findAllActive()).thenReturn(List.of(permission("model.order.read", "order", "read")));
        when(permissions.findRolePermissions(1L)).thenReturn(List.of(permission("model.order.read", "order", "read")));
        when(capabilities.previewCapabilitySelection(1L, Set.of("orders.view"), Set.of()))
                .thenReturn(new CapabilitySelectionPreview(List.of(), List.of(), List.of("model.order.read"), List.of(), List.of()));
    }

    @Test void positiveGrantFieldsRejectUnrepresentableMetadataCodes() {
        grant.setTenantId(100L); grant.setPid("binding-native");
        var definition = permission("model.order.read", "order", "read"); definition.setId(3L);
        when(permissions.findAllActive()).thenReturn(List.of(definition));
        var metadata = mock(com.auraboot.framework.meta.service.MetaModelService.class);
        when(metadata.getModelDefinition("order")).thenReturn(java.util.Optional.of(
                com.auraboot.framework.meta.dto.ModelDefinition.builder().code("order").build()));
        when(metadata.getModelFields("order")).thenReturn(List.of(
                com.auraboot.framework.meta.dto.FieldDefinition.builder().code("_invalid").name("Unsupported metadata").build()));
        var editor = new RoleGrantFieldScopeService(grants, permissions, metadata,
                mock(DataPermissionAuthoringAccess.class), authoringAccess, JsonMapper.builder().build());
        assertThat(editor.catalog(role)).singleElement().satisfies(binding -> assertThat(binding.bindingPid()).isEqualTo("binding-native"));
        assertThatThrownBy(() -> editor.preview(role, List.of(
                new com.auraboot.framework.permission.dto.RoleGrantFields.Change("binding-native", List.of("_invalid"), List.of())),
                Set.of("model.order.read"))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test void fieldCandidatesDoNotInventPendingBindingsOutsideTheActualGrantPlan() {
        var definition = permission("model.order.read", "order", "read"); definition.setId(3L);
        when(permissions.findAllActive()).thenReturn(List.of(definition));
        when(grants.findByRole(1L)).thenReturn(List.of());
        var metadata = mock(com.auraboot.framework.meta.service.MetaModelService.class);
        when(metadata.getModelDefinition("order")).thenReturn(java.util.Optional.of(
                com.auraboot.framework.meta.dto.ModelDefinition.builder().code("order").build()));
        when(metadata.getModelFields("order")).thenReturn(List.of(
                com.auraboot.framework.meta.dto.FieldDefinition.builder().code("name").name("Name").build()));
        var editor = new RoleGrantFieldScopeService(grants, permissions, metadata,
                mock(DataPermissionAuthoringAccess.class), authoringAccess, JsonMapper.builder().build());
        assertThat(editor.candidates(role, Set.of("model.order.read"), Set.of())).isEmpty();
        assertThat(editor.candidates(role, Set.of("model.order.read"), Set.of("model.order.read")))
                .singleElement().satisfies(candidate -> assertThat(candidate.bindingPid()).isNull());
    }

    @AfterEach void cleanup() { MetaContext.clear(); org.springframework.transaction.support.TransactionSynchronizationManager.clear(); }

    private void migrationFixture() {
        MetaContext.setCurrentUserId(7L);
        var permissionChecks = mock(DataPermissionAuthoringAccess.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "migrationAccess", permissionChecks);
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        org.springframework.transaction.support.TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
        businessOwner(Set.of("sales.manage"));
        var sales = permission("sales.manage", "sales.manage", "execute");
        sales.setId(3L); sales.setPid("permission-sales"); sales.setResourceType("operation");
        when(permissions.findAllActive()).thenReturn(List.of(sales));
        when(permissions.findRolePermissions(1L)).thenReturn(List.of(sales));
        grant.setPid("binding-sales"); grant.setTenantId(100L); grant.setStatus("active");
    }

    @Test void migrationReportsAnUnconfiguredScopeWithoutAddingAnAllGrant() {
        migrationFixture();
        var result = service.migrationPreview("role-a");
        var operation = result.boundaries().getFirst().operations().getFirst();
        assertThat(operation.scopeStatus()).isEqualTo("REQUIRES_EXPLICIT_SCOPE_DECISION");
        assertThat(operation.currentScopeType()).isNull();
        assertThat(operation.bindingPids()).containsExactly("binding-sales");
        assertThat(result.equivalentMigrationProven()).isFalse();
        assertThat(result.revision()).isEqualTo(service.snapshot("role-a").revision());
        verify(writes, never()).mutate();
        verify(roles, never()).setDefaultDataScopeType(any(), any());
        verify(matrix, never()).setRoleScope(any(), any(), any());
    }

    private NativeBusinessBoundaryCatalog.Definition nativeBoundary(String source) {
        return new NativeBusinessBoundaryCatalog.Definition("sales:native_update",
                new com.auraboot.framework.meta.security.NativeBusinessCommandContract("sales_order", "update", List.of("sales.manage")), source);
    }

    @Test void migrationIncludesNativeRootsWithoutProjectingMissingScopeToAll() {
        migrationFixture();
        var permission = permissions.findAllActive().getFirst(); permission.setResourceCode("sales_order"); permission.setAction("manage");
        when(nativeBoundaries.snapshot()).thenReturn(List.of(nativeBoundary("definition-v1")));
        var result = service.migrationPreview("role-a");
        var boundary = result.boundaries().stream().filter(root -> "sales:native_update".equals(root.commandCode())).findFirst().orElseThrow();
        assertThat(boundary.rootModel()).isEqualTo("sales_order"); assertThat(boundary.targetAction()).isEqualTo("update");
        assertThat(boundary.operations().getFirst().scopeStatus()).isEqualTo("REQUIRES_EXPLICIT_SCOPE_DECISION");
        assertThat(boundary.operations().getFirst().bindingPids()).containsExactly("binding-sales");
        assertThat(result.equivalentMigrationProven()).isFalse(); verify(writes, never()).mutate();
        verify(nativeBoundaries).snapshot();
    }

    @Test void nativeResourceAndActionMismatchCannotAppearAsConfiguredAccess() {
        migrationFixture(); role.setDefaultDataScopeType("all");
        when(nativeBoundaries.snapshot()).thenReturn(List.of(nativeBoundary("definition-v1")));
        var operation = service.migrationPreview("role-a").boundaries().stream()
                .filter(root -> "sales:native_update".equals(root.commandCode())).findFirst().orElseThrow().operations().getFirst();
        assertThat(operation.scopeStatus()).isEqualTo("BOUNDARY_MAPPING_REQUIRES_REVIEW"); verify(writes, never()).mutate();
    }

    @Test void nativeDefinitionChangesInvalidateReviewedDraftBeforeAnyRoleWrites() {
        when(nativeBoundaries.snapshot()).thenReturn(List.of(nativeBoundary("input-fields-v1")));
        String revision = service.snapshot("role-a").revision();
        when(nativeBoundaries.snapshot()).thenReturn(List.of(nativeBoundary("input-fields-v2")));
        assertThatThrownBy(() -> service.apply("role-a", request(revision, false, null, List.of())))
                .isInstanceOfSatisfying(RootUnCheckedException.class,
                        error -> assertThat(error.getResponseCode()).isEqualTo(ResponseCode.CasVersionConflict));
        verify(writes, never()).mutate(); verify(capabilities, never()).applyCapabilitySelection(any(), any(), any());
    }

    @Test void ownerEntryDefinitionChangesInvalidateTheReviewBeforeAnyRoleWrite() {
        businessOwner(Set.of("sales.manage"));
        when(nativeBoundaries.ownerSources(List.of("sales:release"))).thenReturn("entry-v1");
        String revision = service.snapshot("role-a").revision();
        when(nativeBoundaries.ownerSources(List.of("sales:release"))).thenReturn("entry-v2");
        assertThatThrownBy(() -> service.apply("role-a", request(revision, false, null, List.of())))
                .isInstanceOfSatisfying(RootUnCheckedException.class,
                        error -> assertThat(error.getResponseCode()).isEqualTo(ResponseCode.CasVersionConflict));
        verify(writes, never()).mutate();
        verify(capabilities, never()).applyCapabilitySelection(any(), any(), any());
    }

    @Test void migrationRetainsDenyDatesConditionsFieldsAndIndependentSourceWithoutActivatingThem() {
        migrationFixture();
        grant.setGrantType("deny"); grant.setPriority(99); grant.setStatus("inactive");
        grant.setEffectiveDate(LocalDate.of(2030, 1, 1)); grant.setExpiryDate(LocalDate.of(2031, 1, 1));
        grant.setConditions(java.util.Map.of("legacy", true)); grant.setConditionAst(java.util.Map.of("condition", "restricted"));
        grant.setDataScopeAst(java.util.Map.of("row", "restricted")); grant.setFieldScope(java.util.Map.of("fields", List.of("amount")));
        grant.setSourceRef("independent:manual");
        var binding = service.migrationPreview("role-a").bindings().getFirst();
        assertThat(binding.grantType()).isEqualTo("deny"); assertThat(binding.status()).isEqualTo("inactive");
        assertThat(binding.priority()).isEqualTo(99); assertThat(binding.effectiveDate()).isEqualTo(grant.getEffectiveDate());
        assertThat(binding.expiryDate()).isEqualTo(grant.getExpiryDate()); assertThat(binding.sourceRef()).isEqualTo("independent:manual");
        assertThat(binding.conditions().get("legacy").asBoolean()).isTrue();
        assertThat(binding.conditionAst().get("condition").asText()).isEqualTo("restricted");
        assertThat(binding.dataScopeAst().get("row").asText()).isEqualTo("restricted");
        assertThat(binding.fieldScope().get("fields").get(0).asText()).isEqualTo("amount");
        verify(writes, never()).mutate();
    }

    @Test void migrationKeepsExplicitNoneAndLegacyModelScopesSeparate() {
        migrationFixture(); role.setDefaultDataScopeType("all");
        var operation = new RoleDataScope(); operation.setResourceCode("sales.manage"); operation.setActionCode("execute"); operation.setScopeType("none"); operation.setRoleId(1L); operation.setTenantId(100L);
        var legacy = new RoleDataScope(); legacy.setResourceCode("sales_order"); legacy.setActionCode("update"); legacy.setScopeType("self"); legacy.setRoleId(1L); legacy.setTenantId(100L);
        when(scopes.getScopesByRole(100L, 1L)).thenReturn(List.of(operation, legacy));
        var result = service.migrationPreview("role-a"); var view = result.boundaries().getFirst().operations().getFirst();
        assertThat(view.currentScopeType()).isEqualTo("none"); assertThat(view.scopeOrigin()).isEqualTo("EXPLICIT_ACTION");
        assertThat(result.scopes()).hasSize(2); assertThat(result.equivalentMigrationProven()).isFalse();
        operation.setScopeConfig(java.util.Map.of("custom", true));
        assertThat(service.migrationPreview("role-a").boundaries().getFirst().operations().getFirst().scopeStatus())
                .isEqualTo("UNSUPPORTED_SCOPE_CONFIGURATION");
    }

    @Test void missingBusinessAuthorityIsNotInferredFromAnUnrelatedLegacyCode() {
        migrationFixture();
        var legacy = permission("legacy.manage", "legacy.manage", "view"); legacy.setId(4L); legacy.setPid("legacy-permission");
        var sales = permissions.findAllActive().getFirst(); when(permissions.findAllActive()).thenReturn(List.of(sales, legacy));
        grant.setPermissionId(4L);
        var result = service.migrationPreview("role-a");
        assertThat(result.boundaries().getFirst().operations().getFirst().scopeStatus()).isEqualTo("MISSING_BUSINESS_GRANT");
        assertThat(result.unmappedPermissionCodes()).containsExactly("legacy.manage");
    }

    @Test void changingCapturedConstraintsRejectsTheMigrationDraftBeforeWriting() {
        migrationFixture(); grant.setConditionAst(new java.util.HashMap<>(java.util.Map.of("guard", "before")));
        var inspected = service.migrationPreview("role-a"); grant.setConditionAst(java.util.Map.of("guard", "after"));
        assertThat(inspected.bindings().getFirst().conditionAst().get("guard").asText()).isEqualTo("before");
        assertThatThrownBy(() -> service.apply("role-a", request(inspected.revision(), false, null, List.of())))
                .isInstanceOfSatisfying(RootUnCheckedException.class,
                        error -> assertThat(error.getResponseCode()).isEqualTo(ResponseCode.CasVersionConflict));
        verify(writes, never()).mutate();
    }

    @Test void migrationRequiresManagementAndAConsistentServiceTransaction() {
        migrationFixture();
        org.springframework.transaction.support.TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(java.sql.Connection.TRANSACTION_READ_COMMITTED);
        assertThatThrownBy(() -> service.migrationPreview("role-a")).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verify(roles, never()).findByPid(any());
    }

    @Test void migrationRejectsForeignBindingRowsBeforeReturningTheirConditions() {
        migrationFixture(); grant.setTenantId(200L);
        assertThatThrownBy(() -> service.migrationPreview("role-a")).isInstanceOf(RootUnCheckedException.class);
        verify(writes, never()).mutate();
    }

    @Test void migrationDoesNotExposeBindingsWithoutCurrentPermissionManagement() {
        migrationFixture();
        var checks = (DataPermissionAuthoringAccess) org.springframework.test.util.ReflectionTestUtils.getField(service, "migrationAccess");
        doThrow(new org.springframework.security.access.AccessDeniedException("revoked")).when(checks).requireRead();
        assertThatThrownBy(() -> service.migrationPreview("role-a")).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verify(roles, never()).findByPid(any());
    }

    @Test void migrationUsesTheTenantDefinitionInsteadOfAnUnrelatedGlobalMapping() {
        migrationFixture();
        var local = permissions.findAllActive().getFirst(); local.setTenantId(100L);
        var global = permission("sales.manage", "wrong-global-resource", "view"); global.setId(8L); global.setPid("global-permission");
        when(permissions.findAllActive()).thenReturn(List.of(global, local));
        assertThat(service.migrationPreview("role-a").boundaries().getFirst().operations().getFirst().resourceCode()).isEqualTo("sales.manage");
        local.setAction("view");
        assertThat(service.migrationPreview("role-a").boundaries().getFirst().operations().getFirst().scopeStatus())
                .isEqualTo("ENTRY_FALLBACK_REQUIRES_MAPPING");
    }

    @Test void migrationShowsBoundRowPoliciesIncludingDisabledRestrictionsAndRejectsForeignDefinitions() {
        migrationFixture();
        var binding = new com.auraboot.framework.meta.entity.DataPermissionRoleBinding();
        binding.setPolicyPid("row-policy"); binding.setRolePid("role-a"); binding.setTenantId(100L);
        var policy = new com.auraboot.framework.meta.entity.DataPermissionPolicy();
        policy.setPid("row-policy"); policy.setTenantId(100L); policy.setModelCode("sales_order");
        policy.setPolicyType("row"); policy.setScopeType("custom"); policy.setEnabled(false);
        policy.setConditionAst(java.util.Map.of("guard", "restricted")); policy.setScopeExpression("legacy restriction");
        when(bindings.findByRolePid(100L, "role-a")).thenReturn(List.of(binding));
        when(policies.findByPid(100L, "row-policy")).thenReturn(policy);
        var inspected = service.migrationPreview("role-a"); var captured = inspected.boundPolicies().getFirst();
        assertThat(captured.enabled()).isFalse(); assertThat(captured.scopeExpression()).isEqualTo("legacy restriction");
        assertThat(captured.conditionAst().get("guard").asText()).isEqualTo("restricted");
        policy.setTenantId(200L);
        assertThatThrownBy(() -> service.migrationPreview("role-a")).isInstanceOf(RootUnCheckedException.class);
        verify(writes, never()).mutate();
    }

    private com.auraboot.framework.plugin.extension.BusinessResourceAuthorization businessOwner(Set<String> codes) {
        var registry = mock(com.auraboot.framework.plugin.pf4j.ExtensionRegistry.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "extensions", registry);
        var handler = mock(com.auraboot.framework.plugin.extension.CommandHandlerExtension.class,
                org.mockito.Mockito.withSettings().extraInterfaces(com.auraboot.framework.plugin.extension.BusinessResourceAuthorization.class));
        var owner = (com.auraboot.framework.plugin.extension.BusinessResourceAuthorization) handler;
        when(owner.businessBoundary()).thenReturn(new com.auraboot.framework.plugin.extension.BusinessResourceAuthorization.Boundary(
                "sales:release", "sales_order", "update", codes));
        when(registry.getAllCommandHandlers()).thenReturn(List.of(handler));
        when(registry.getBusinessResourceOwner("sales:release")).thenReturn(java.util.Optional.of(handler));
        return owner;
    }

    @Test void aBusinessDutyMakesOnlyItsRootEligibleForFieldRestrictionsWithoutCrudGrants() {
        businessOwner(Set.of("sales.manage"));
        when(capabilities.previewCapabilitySelection(1L, Set.of("orders.view"), Set.of()))
                .thenReturn(new CapabilitySelectionPreview(List.of("sales.manage"), List.of(), List.of(), List.of(), List.of()));
        var baseline = service.snapshot("role-a");
        service.preview("role-a", new RoleAuthorizationDraftRequest(baseline.revision(), Set.of("orders.view"),
                Set.of(), false, null, false, List.of(), Set.of("policy")));
        verify(fieldPolicies).preview(org.mockito.ArgumentMatchers.eq(role), org.mockito.ArgumentMatchers.eq(Set.of("policy")),
                org.mockito.ArgumentMatchers.argThat(models -> models.equals(Set.of("order", "sales_order"))));
        verify(writes, never()).mutate();
    }

    @Test void unrelatedPermissionsCannotMakeTheBusinessRootAFieldCandidate() {
        businessOwner(Set.of("sales.manage", "sales.release"));
        when(capabilities.previewCapabilitySelection(1L, Set.of("orders.view"), Set.of()))
                .thenReturn(new CapabilitySelectionPreview(List.of("unrelated.manage"), List.of(), List.of(), List.of(), List.of()));
        var baseline = service.snapshot("role-a");
        service.preview("role-a", new RoleAuthorizationDraftRequest(baseline.revision(), Set.of("orders.view"),
                Set.of(), false, null, false, List.of(), Set.of("policy")));
        verify(fieldPolicies).preview(org.mockito.ArgumentMatchers.eq(role), org.mockito.ArgumentMatchers.eq(Set.of("policy")),
                org.mockito.ArgumentMatchers.argThat(models -> models.equals(Set.of("order"))));
    }

    @Test void eitherCompleteDutyAlternativeOrAnExplicitWildcardCanRestrictOnlyTheBusinessRoot() {
        businessOwner(Set.of("sales.manage", "sales.release"));
        for (String alternative : List.of("sales.manage", "sales.release", "*")) {
            when(capabilities.previewCapabilitySelection(1L, Set.of("orders.view"), Set.of()))
                    .thenReturn(new CapabilitySelectionPreview(List.of(alternative), List.of(), List.of(), List.of(), List.of()));
            var baseline = service.snapshot("role-a");
            service.preview("role-a", new RoleAuthorizationDraftRequest(baseline.revision(), Set.of("orders.view"),
                    Set.of(), false, null, false, List.of(), Set.of("policy")));
        }
        verify(fieldPolicies, org.mockito.Mockito.times(3)).preview(org.mockito.ArgumentMatchers.eq(role),
                org.mockito.ArgumentMatchers.eq(Set.of("policy")),
                org.mockito.ArgumentMatchers.argThat(models -> models.equals(Set.of("order", "sales_order"))));
        verify(writes, never()).mutate();
    }

    @Test void changingTheCodeOwnedRootInvalidatesTheReviewedDraft() {
        var owner = businessOwner(Set.of("sales.manage"));
        var baseline = service.snapshot("role-a");
        when(owner.businessBoundary()).thenReturn(new com.auraboot.framework.plugin.extension.BusinessResourceAuthorization.Boundary(
                "sales:release", "another_order", "update", Set.of("sales.manage")));
        assertThatThrownBy(() -> service.apply("role-a", request(baseline.revision(), false, null, List.of())))
                .isInstanceOfSatisfying(RootUnCheckedException.class,
                        error -> assertThat(error.getResponseCode()).isEqualTo(ResponseCode.CasVersionConflict));
        verify(writes, never()).mutate();
    }

    @Test void aDutyCanRestrictItsDeclaredPublicSourcesWithoutGrantingInternalModels() {
        var owner = businessOwner(Set.of("sales.manage"));
        when(owner.businessResourceModels()).thenReturn(Set.of("sales_order", "sales_line", "internal_audit"));
        when(owner.publicBusinessResourceFields()).thenReturn(java.util.Map.of("sales_line", Set.of("price")));
        when(capabilities.previewCapabilitySelection(1L, Set.of("orders.view"), Set.of()))
                .thenReturn(new CapabilitySelectionPreview(List.of("sales.manage"), List.of(), List.of(), List.of(), List.of()));
        var baseline = service.snapshot("role-a");
        service.preview("role-a", new RoleAuthorizationDraftRequest(baseline.revision(), Set.of("orders.view"),
                Set.of(), false, null, false, List.of(), Set.of("policy")));
        verify(fieldPolicies).preview(org.mockito.ArgumentMatchers.eq(role), org.mockito.ArgumentMatchers.eq(Set.of("policy")),
                org.mockito.ArgumentMatchers.argThat(models -> models.equals(Set.of("order", "sales_order", "sales_line"))));
        verify(writes, never()).mutate();
        verify(capabilities, never()).applyCapabilitySelection(any(), any(), any());
    }

    @Test void publicSourceDeclarationsDoNotReplaceTheRequiredBusinessDuty() {
        var owner = businessOwner(Set.of("sales.manage"));
        when(owner.businessResourceModels()).thenReturn(Set.of("sales_order", "sales_line"));
        when(owner.publicBusinessResourceFields()).thenReturn(java.util.Map.of("sales_line", Set.of("price")));
        var baseline = service.snapshot("role-a");
        service.preview("role-a", new RoleAuthorizationDraftRequest(baseline.revision(), Set.of("orders.view"),
                Set.of(), false, null, false, List.of(), Set.of("policy")));
        verify(fieldPolicies).preview(org.mockito.ArgumentMatchers.eq(role), org.mockito.ArgumentMatchers.eq(Set.of("policy")),
                org.mockito.ArgumentMatchers.argThat(models -> models.equals(Set.of("order"))));
        verify(writes, never()).mutate();
    }

    @Test void changedPublicSourceFieldsInvalidateTheReviewedDraft() {
        var owner = businessOwner(Set.of("sales.manage"));
        when(owner.businessResourceModels()).thenReturn(Set.of("sales_order", "sales_line"));
        when(owner.publicBusinessResourceFields()).thenReturn(java.util.Map.of("sales_line", Set.of("price")));
        var baseline = service.snapshot("role-a");
        when(owner.publicBusinessResourceFields()).thenReturn(java.util.Map.of("sales_line", Set.of("brand")));
        assertThatThrownBy(() -> service.apply("role-a", request(baseline.revision(), false, null, List.of())))
                .isInstanceOfSatisfying(RootUnCheckedException.class,
                        error -> assertThat(error.getResponseCode()).isEqualTo(ResponseCode.CasVersionConflict));
        verify(writes, never()).mutate();
    }

    @Test void publicSourcesOutsideTheOwnersResourceContractAreRejected() {
        var owner = businessOwner(Set.of("sales.manage"));
        when(owner.businessResourceModels()).thenReturn(Set.of("sales_order"));
        when(owner.publicBusinessResourceFields()).thenReturn(java.util.Map.of("unowned_model", Set.of("price")));
        assertThatThrownBy(() -> service.snapshot("role-a")).isInstanceOf(RootUnCheckedException.class);
        verify(writes, never()).mutate();
    }

    @Test void staleConditionsOrExpiryCannotBeOverwrittenByAnOldDraft() {
        String revision = service.snapshot("role-a").revision();
        grant.setExpiryDate(LocalDate.of(2030, 1, 1));
        assertThat(service.snapshot("role-a").revision()).isNotEqualTo(revision);
        assertThatThrownBy(() -> service.apply("role-a", request(revision, false, null, List.of())))
                .isInstanceOfSatisfying(RootUnCheckedException.class,
                        error -> assertThat(error.getResponseCode()).isEqualTo(ResponseCode.CasVersionConflict));
        verify(capabilities, never()).applyCapabilitySelection(any(), any(), any());
        verify(writes, never()).mutate();
    }

    @Test void validatesEveryScopeAgainstTheResultingGrantBeforeAnyWrite() {
        String revision = service.snapshot("role-a").revision();
        var foreign = new DataScopeUpdateRequest("other-model", "update", "all", "MAX");
        assertThatThrownBy(() -> service.apply("role-a", request(revision, true, "self", List.of(foreign))))
                .isInstanceOfSatisfying(RootUnCheckedException.class,
                        error -> assertThat(error.getResponseCode()).isEqualTo(ResponseCode.BadParam));
        verify(roles, never()).setDefaultDataScopeType(any(), any());
        verify(capabilities, never()).applyCapabilitySelection(any(), any(), any());
        verify(writes, never()).mutate();
    }

    @Test void combinesDutiesDefaultsAndExactActionScopesAndReturnsTheNewBaseline() {
        String revision = service.snapshot("role-a").revision();
        doAnswer(invocation -> { role.setDefaultDataScopeType(invocation.getArgument(1)); return null; })
                .when(roles).setDefaultDataScopeType(1L, "self");
        var scope = new DataScopeUpdateRequest("order", "read", "none", "MAX");
        var saved = service.apply("role-a", request(revision, true, "self", List.of(scope)));
        var order = inOrder(roles, capabilities, matrix, scopes);
        order.verify(roles).setDefaultDataScopeType(1L, "self");
        order.verify(capabilities).applyCapabilitySelection(1L, Set.of("orders.view"), Set.of());
        order.verify(matrix).applyDefaultScopeToCurrentGrants(100L, 1L, "self");
        order.verify(matrix).setRoleScope(100L,1L,new DataScopeUpdateRequest("order","read","none","MAX"));
        assertThat(saved.defaultScopeType()).isEqualTo("self");
        assertThat(saved.revision()).isNotEqualTo(revision);
    }

    @Test void previewLeavesLiveRoleAndScopesUntouched() {
        String revision = service.snapshot("role-a").revision();
        var preview = service.preview("role-a", request(revision, true, "self", List.of()));
        assertThat(preview.defaultScopeChanged()).isTrue();
        assertThat(preview.defaultScopeType()).isEqualTo("self");
        assertThat(service.snapshot("role-a").revision()).isEqualTo(revision);
        verify(roles, never()).setDefaultDataScopeType(any(), any());
        verify(capabilities, never()).applyCapabilitySelection(any(), any(), any());
        verify(writes, never()).mutate();
    }

    @Test void writeFailurePropagatesInsteadOfReturningASavedSnapshot() {
        String revision = service.snapshot("role-a").revision();
        doThrow(new IllegalStateException("injected write failure")).when(capabilities)
                .applyCapabilitySelection(1L, Set.of("orders.view"), Set.of());
        assertThatThrownBy(() -> service.apply("role-a", request(revision, true, "self", List.of())))
                .isInstanceOf(IllegalStateException.class).hasMessage("injected write failure");
        verify(matrix, never()).applyDefaultScopeToCurrentGrants(any(), any(), any());
    }

    @Test void changingAnUnboundFieldCandidateInvalidatesAnEarlierPreview() {
        var policy = new com.auraboot.framework.meta.entity.DataPermissionPolicy();
        policy.setPid("candidate"); policy.setPolicyType("column"); policy.setMaskType("hide");
        when(policies.findAllEnabled(100L)).thenReturn(List.of(policy));
        var baseline = service.snapshot("role-a");
        policy.setMaskType("hash");
        assertThatThrownBy(() -> service.apply("role-a", request(baseline.revision(), false, null, List.of())))
                .isInstanceOfSatisfying(RootUnCheckedException.class,
                        error -> assertThat(error.getResponseCode()).isEqualTo(ResponseCode.CasVersionConflict));
        verify(writes, never()).mutate();
    }

    @Test void revisionsAreIndependentOfStorageReturnOrder() {
        var a = permission("a.read", "a", "read");
        var b = permission("b.read", "b", "read");
        when(permissions.findAllActive()).thenReturn(List.of(b, a));
        String revision = service.snapshot("role-a").revision();
        when(permissions.findAllActive()).thenReturn(List.of(a, b));
        assertThat(service.snapshot("role-a").revision()).isEqualTo(revision);
    }

    @Test void foreignTenantRolesCannotBeInspectedOrMutated() {
        role.setTenantId(200L);
        assertThatThrownBy(() -> service.snapshot("role-a")).isInstanceOf(RootUnCheckedException.class);
        verify(capabilities, never()).resolveForRole(any());
    }

    @Test void directCallCannotBypassTheAuthoringBoundary() {
        String revision = service.snapshot("role-a").revision();
        doThrow(new org.springframework.security.access.AccessDeniedException("administrator required"))
                .when(authoringAccess).requireWrite();
        assertThatThrownBy(() -> service.apply("role-a", request(revision, true, "all", List.of())))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verify(writes, never()).mutate();
        verify(roles, never()).setDefaultDataScopeType(any(), any());
        verify(capabilities, never()).applyCapabilitySelection(any(), any(), any());
        verify(fieldPolicies, never()).apply(any(), any());
    }

    private static PermissionDTO permission(String code, String resource, String action) {
        var permission = new PermissionDTO();
        permission.setCode(code); permission.setResourceCode(resource); permission.setAction(action);
        return permission;
    }

    private static RoleAuthorizationDraftRequest request(String revision, boolean defaultChanged,
            String defaultScope, List<DataScopeUpdateRequest> scopes) {
        return new RoleAuthorizationDraftRequest(revision, Set.of("orders.view"), Set.of(), defaultChanged,
                defaultScope, defaultChanged && defaultScope != null, scopes);
    }
}
