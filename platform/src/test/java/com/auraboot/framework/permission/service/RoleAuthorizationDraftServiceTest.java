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
    private final RoleAuthorizationDraftService service = new RoleAuthorizationDraftService(writes, fieldPolicies, roles, grants,
            permissions, catalog, capabilities, matrix, scopes,
            JsonMapper.builder().addModule(new JavaTimeModule()).build(), bindings, policies);
    private final Role role = new Role();
    private final RolePermission grant = new RolePermission();

    @BeforeEach void setup() {
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

    @AfterEach void cleanup() { MetaContext.clear(); }

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
        order.verify(scopes).setScope(100L, 1L, "order", "read", "none", "MAX");
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
