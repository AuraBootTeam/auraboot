package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.entity.DataPermissionPolicy;
import com.auraboot.framework.meta.mapper.DataPermissionPolicyMapper;
import com.auraboot.framework.meta.mapper.DataPermissionRoleBindingMapper;
import com.auraboot.framework.meta.service.DataPermissionEngine;
import com.auraboot.framework.permission.service.DataPermissionAuthoringAccess;
import com.auraboot.framework.permission.service.RoleAuthorizationWriteLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DataPermissionPolicyServiceBoundaryTest {
    private final RoleAuthorizationWriteLock writes=mock(RoleAuthorizationWriteLock.class);
    private final DataPermissionAuthoringAccess access=mock(DataPermissionAuthoringAccess.class);
    private final DataPermissionPolicyMapper policies=mock(DataPermissionPolicyMapper.class);
    private final DataPermissionRoleBindingMapper bindings=mock(DataPermissionRoleBindingMapper.class);
    private final DataPermissionEngine engine=mock(DataPermissionEngine.class);
    private final DataPermissionPolicyServiceImpl service=new DataPermissionPolicyServiceImpl(writes,access,policies,bindings,engine);
    private MetaContext.Snapshot target;

    @BeforeEach void identity() {
        MetaContext.setContext(7L,20L,"admin-pid","Admin",Set.of(40L));MetaContext.setMemberId(30L);
        target=new MetaContext.Snapshot(7L,21L,"target-pid","Target",Set.of(),31L,null,null,null);
    }
    @AfterEach void clear() {MetaContext.clear();}

    private com.auraboot.framework.meta.dto.DataPermissionPolicyCreateRequest request(String scope) {
        var request=new com.auraboot.framework.meta.dto.DataPermissionPolicyCreateRequest();
        request.setName("Policy");request.setModelCode("orders");request.setPolicyType("ROW");request.setScopeType(scope);return request;
    }

    @Test void authoringNormalizesDocumentedCaseBeforePersistence() {
        var saved=service.create(request(" SeLf "));
        assertThat(saved.getPolicyType()).isEqualTo("row");assertThat(saved.getScopeType()).isEqualTo("self");
        verify(policies).insert(saved);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullAndEmptySource
    @org.junit.jupiter.params.provider.ValueSource(strings={"unknown","SELF_TYPO","CUSTOM"})
    void invalidRowScopeCannotBeSavedAsAnIgnoredRestriction(String scope) {
        assertThatThrownBy(()->service.create(request(scope))).isInstanceOf(com.auraboot.framework.exception.BusinessException.class);
        verifyNoInteractions(policies,bindings,engine);
    }

    @Test void previewResolvesTheTargetMemberAndNeverBorrowsAdminCommandAuthority() {
        when(access.previewSubject(21L)).thenReturn(target);
        var policy=new DataPermissionPolicy();policy.setPid("target-policy");
        when(policies.findEffectivePolicies(7L,"orders",31L)).thenReturn(List.of(policy));
        when(engine.buildRowFilter(7L,"orders",21L)).thenAnswer(call->{
            assertThat(MetaContext.getCurrentUserId()).isEqualTo(21L);
            assertThat(MetaContext.getCurrentMemberId()).isEqualTo(31L);
            assertThat(MetaContext.getCurrentRoleIds()).isEmpty();
            assertThat(MetaContext.getCommandAuthority()).isNull();
            return "AND created_by=21";
        });
        var result=MetaContext.runWithCommandAuthority("admin-command",()->{
            var preview=service.preview("orders",21L);
            assertThat(MetaContext.getCommandAuthority()).isEqualTo("admin-command");
            return preview;
        });
        assertThat(result.effectivePolicies()).containsExactly(policy);assertThat(result.rowFilter()).isEqualTo("AND created_by=21");
        assertThat(result.userId()).isEqualTo(21L);assertThat(MetaContext.getCurrentUserId()).isEqualTo(20L);
        assertThat(MetaContext.getCurrentMemberId()).isEqualTo(30L);assertThat(MetaContext.getCommandAuthority()).isNull();
    }

    @Test void evaluatorFailureRestoresAllOriginalSubjectState() {
        when(access.previewSubject(21L)).thenReturn(target);when(policies.findEffectivePolicies(7L,"orders",31L)).thenReturn(List.of());
        when(engine.buildRowFilter(7L,"orders",21L)).thenThrow(new IllegalStateException("Actual evaluator failure"));
        var before=MetaContext.snapshot();
        assertThatThrownBy(()->service.preview("orders",21L)).isInstanceOf(IllegalStateException.class);
        assertThat(MetaContext.snapshot()).isEqualTo(before);
    }

    @Test void effectivePoliciesUsesMembershipInsteadOfTheUserIdentity() {
        when(access.previewSubject(21L)).thenReturn(target);when(policies.findEffectivePolicies(7L,"orders",31L)).thenReturn(List.of());
        assertThat(service.getEffectivePolicies(7L,"orders",21L)).isEmpty();
        verify(access).requireTenant(7L);verify(policies).findEffectivePolicies(7L,"orders",31L);
        verify(policies,never()).findEffectivePolicies(7L,"orders",21L);
    }

    @Test void crossTenantBindingIsRejectedBeforeAnyBindingWrite() {
        when(policies.findByPid(7L,"policy")).thenReturn(new DataPermissionPolicy());
        doThrow(new AccessDeniedException("Foreign role")).when(access).requireRole("foreign");
        assertThatThrownBy(()->service.bindToRole("policy","foreign")).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(bindings);verify(writes).mutate();verify(access).requireWrite();
    }

    @Test void revokedAuthoringAuthorityCannotReachPoliciesOrBindings() {
        doThrow(new AccessDeniedException("Revoked")).when(access).requireWrite();
        assertThatThrownBy(()->service.disable("policy")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->service.bindToRole("policy","role")).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(policies,bindings,engine);
    }

    @Test void previewGuardRunsBeforeTargetResolutionAndEvaluation() {
        doThrow(new AccessDeniedException("No administrative authority")).when(access).requireRead();
        assertThatThrownBy(()->service.preview("orders",21L)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(policies,bindings,engine);verify(access,never()).previewSubject(any());
    }
}
