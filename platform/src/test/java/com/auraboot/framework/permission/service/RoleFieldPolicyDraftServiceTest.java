package com.auraboot.framework.permission.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.entity.DataPermissionPolicy;
import com.auraboot.framework.meta.entity.DataPermissionRoleBinding;
import com.auraboot.framework.meta.mapper.DataPermissionPolicyMapper;
import com.auraboot.framework.meta.mapper.DataPermissionRoleBindingMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.rbac.entity.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoleFieldPolicyDraftServiceTest {
    final DataPermissionPolicyMapper policies = mock(DataPermissionPolicyMapper.class);
    final DataPermissionRoleBindingMapper bindings = mock(DataPermissionRoleBindingMapper.class);
    final MetaModelService models = mock(MetaModelService.class);
    final UserPermissionService permissions = mock(UserPermissionService.class);
    final RoleFieldPolicyDraftService service = new RoleFieldPolicyDraftService(policies, bindings, models, permissions);
    final Role role = new Role();

    @BeforeEach void prepare() {
        MetaContext.setCurrentTenantId(7L); MetaContext.setCurrentUserId(20L);
        role.setPid("role-a"); role.setTenantId(7L);
        when(permissions.hasPermission(20L, MetaPermission.PERMISSION_MANAGE)).thenReturn(true);
        when(models.getModelFields("orders")).thenReturn(List.of(FieldDefinition.builder().code("cost").build()));
    }
    @AfterEach void clear() { MetaContext.clear(); }
    DataPermissionPolicy policy(String pid, String type) {
        var policy = new DataPermissionPolicy(); policy.setPid(pid); policy.setTenantId(7L);
        policy.setPolicyType(type); policy.setMaskType("HIDE"); policy.setModelCode("orders");
        policy.setFieldCode("cost"); policy.setEnabled(true);
        when(policies.findByPid(7L, pid)).thenReturn(policy); return policy;
    }
    void bound(String... pids) {
        when(bindings.findByRolePid(7L,"role-a")).thenReturn(Arrays.stream(pids).map(pid -> {
            var binding = new DataPermissionRoleBinding(); binding.setPolicyPid(pid); return binding;
        }).toList());
    }
    @Test void previewAndSnapshotDoNotWriteAndCannotExposePolicyExpressions() {
        var policy = policy("hide-cost", "COLUMN"); policy.setMaskExpression("sensitive-policy-expression");
        bound("hide-cost");
        assertThat(service.snapshot(role)).singleElement().satisfies(view -> {
            assertThat(view.fieldCode()).isEqualTo("cost");
            assertThat(view.toString()).doesNotContain("sensitive-policy-expression");
        });
        assertThat(service.preview(role, Set.of("hide-cost"), Set.of("orders")).added()).isEmpty();
        verify(bindings, never()).insertBinding(any(),any(),any());
        verify(bindings, never()).deleteBinding(any(),any(),any());
    }
    @Test void omissionPreservesAllBindingsWithoutRequiringNewManagementAuthority() {
        when(permissions.hasPermission(20L, MetaPermission.PERMISSION_MANAGE)).thenReturn(false);
        var preview = service.preview(role, null, Set.of());
        assertThat(preview.added()).isEmpty(); assertThat(preview.removed()).isEmpty();
        verifyNoInteractions(bindings, policies);
    }
    @Test void roleManagementAloneCannotAlterFieldRestrictions() {
        when(permissions.hasPermission(20L, MetaPermission.PERMISSION_MANAGE)).thenReturn(false);
        assertThatThrownBy(() -> service.preview(role, Set.of(), Set.of()))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(bindings, policies);
    }
    @Test void replacingFieldBindingsKeepsRowPoliciesAndSharedDefinitionsIntact() {
        policy("hide-old","column"); policy("read-only","field_write"); policy("row-limit","row");
        bound("hide-old","row-limit");
        var preview = service.preview(role, Set.of("read-only"), Set.of("orders"));
        assertThat(preview.added()).extracting(view -> view.policyPid()).containsExactly("read-only");
        assertThat(preview.removed()).extracting(view -> view.policyPid()).containsExactly("hide-old");
        when(bindings.deleteBinding(7L,"hide-old","role-a")).thenReturn(1);
        when(bindings.insertBinding(7L,"read-only","role-a")).thenReturn(1);
        service.apply(role, preview);
        verify(bindings, never()).deleteBinding(7L,"row-limit","role-a");
        verify(policies, never()).deleteByPid(any(),any());
        verify(policies, never()).updateById(any(DataPermissionPolicy.class));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"foreign-tenant","disabled","row","missing-field","unknown-mask","ungranted-model"})
    void invalidSelectionsFailBeforeAnyBindingWrite(String mode) {
        var policy = policy("candidate","column");
        switch (mode) {
            case "foreign-tenant" -> policy.setTenantId(8L);
            case "disabled" -> policy.setEnabled(false);
            case "row" -> policy.setPolicyType("row");
            case "missing-field" -> policy.setFieldCode("nonexistent");
            case "unknown-mask" -> policy.setMaskType("unknown");
            case "ungranted-model" -> policy.setModelCode("other");
        }
        assertThatThrownBy(() -> service.preview(role, Set.of("candidate"), Set.of("orders")))
                .isInstanceOf(com.auraboot.framework.exception.RootUnCheckedException.class);
        verify(bindings, never()).insertBinding(any(),any(),any());
    }
    @Test void lostBindingWriteCannotBeReportedAsSuccess() {
        policy("old","column"); bound("old");
        var preview = service.preview(role, Set.of(), Set.of());
        assertThatThrownBy(() -> service.apply(role, preview)).isInstanceOf(IllegalStateException.class);
    }
}
