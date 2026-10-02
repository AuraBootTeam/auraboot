package com.auraboot.framework.permission.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.permission.engine.PermissionEvaluator;
import com.auraboot.framework.permission.engine.model.PermissionResult;
import com.auraboot.framework.rbac.mapper.UserRoleMapper;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.dao.mapper.TenantMemberMapper;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PermissionExplanationServiceImplTest {
    @Mock TenantMemberMapper tenantMemberMapper;
    @Mock UserMapper userMapper;
    @Mock UserRoleMapper userRoleMapper;
    @Mock DynamicDataService dynamicDataService;
    @Mock PermissionEvaluator permissionEvaluator;
    @InjectMocks PermissionExplanationServiceImpl service;
    private MetaContext.Snapshot caller;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(100L, 1L, "admin-pid", "admin", Set.of(9L));
        MetaContext.setMemberId(10L);
        MetaContext.setEnvironmentId(3L);
        MetaContext.setSessionContext(1L, 2L, "party", 3L, 4L, "ready", 5L);
        caller = MetaContext.snapshot();
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    private void activeMember() {
        TenantMember member = new TenantMember();
        member.setTenantId(100L);
        member.setUserId(20L);
        member.setStatus("ACTIVE");
        when(tenantMemberMapper.selectById(5L)).thenReturn(member);
        User user = new User();
        user.setId(20L);
        user.setPid("target-user-pid");
        user.setUserName("target");
        when(userMapper.selectById(20L)).thenReturn(user);
    }

    @Test
    void evaluatesActualRecordWithTargetIdentityAndRestoresCaller() {
        activeMember();
        Map<String, Object> record = Map.of("pid", "quote-pid", "created_by", 20L);
        when(dynamicDataService.getById("quote", "quote-pid")).thenAnswer(invocation -> {
            assertThat(MetaContext.snapshot()).isEqualTo(caller);
            return record;
        });
        when(userRoleMapper.findRoleIdsByMemberId(5L)).thenReturn(List.of(7L));
        when(permissionEvaluator.canOperate(5L, "quote", "read", record)).thenAnswer(invocation -> {
            assertThat(MetaContext.getCurrentUserId()).isEqualTo(20L);
            assertThat(MetaContext.getCurrentUserPid()).isEqualTo("target-user-pid");
            assertThat(MetaContext.snapshot().memberId()).isEqualTo(5L);
            assertThat(MetaContext.getCurrentRoleIds()).containsExactly(7L);
            assertThat(MetaContext.snapshot().envId()).isEqualTo(3L);
            assertThat(MetaContext.snapshot().sessionContext()).isNull();
            return PermissionResult.deny("Outside data scope", List.of());
        });
        var explanation = service.explain(5L, "quote", "read", "quote-pid");
        assertThat(explanation.finalResult()).isFalse();
        assertThat(explanation.recordPid()).isEqualTo("quote-pid");
        assertThat(MetaContext.snapshot()).isEqualTo(caller);
        verify(permissionEvaluator).canOperate(5L, "quote", "read", record);
    }

    @Test
    void evaluationFailureRestoresCallerIdentityAndSession() {
        activeMember();
        when(userRoleMapper.findRoleIdsByMemberId(5L)).thenReturn(List.of());
        when(permissionEvaluator.canOperate(5L, "quote", "read", null))
                .thenThrow(new IllegalStateException("evaluation failed"));
        assertThatThrownBy(() -> service.explain(5L, "quote", "read", null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(MetaContext.snapshot()).isEqualTo(caller);
    }

    @Test
    void foreignTenantMemberCannotBeExplainedOrRead() {
        TenantMember foreign = new TenantMember();
        foreign.setTenantId(200L);
        foreign.setStatus("ACTIVE");
        when(tenantMemberMapper.selectById(5L)).thenReturn(foreign);
        assertThatThrownBy(() -> service.explain(5L, "quote", "read", "quote-pid"))
                .isInstanceOf(RootUnCheckedException.class);
        verifyNoInteractions(userMapper, userRoleMapper, dynamicDataService, permissionEvaluator);
    }

    @Test
    void callerReadDenialDoesNotBecomeARecordlessExplanation() {
        activeMember();
        when(dynamicDataService.getById("quote", "quote-pid"))
                .thenThrow(new AccessDeniedException("caller cannot read"));
        assertThatThrownBy(() -> service.explain(5L, "quote", "read", "quote-pid"))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(userRoleMapper, permissionEvaluator);
        assertThat(MetaContext.snapshot()).isEqualTo(caller);
    }

    @Test
    void missingRecordDoesNotBecomeARecordlessExplanation() {
        activeMember();
        when(dynamicDataService.getById("quote", "quote-pid")).thenReturn(null);
        assertThatThrownBy(() -> service.explain(5L, "quote", "read", "quote-pid"))
                .isInstanceOf(RootUnCheckedException.class);
        verifyNoInteractions(userRoleMapper, permissionEvaluator);
    }

    @Test
    void inactiveMemberIsRejectedBeforeLoadingRecord() {
        TenantMember inactive = new TenantMember();
        inactive.setTenantId(100L);
        inactive.setStatus("INACTIVE");
        when(tenantMemberMapper.selectById(5L)).thenReturn(inactive);
        assertThatThrownBy(() -> service.explain(5L, "quote", "read", "quote-pid"))
                .isInstanceOf(RootUnCheckedException.class);
        verifyNoInteractions(userMapper, userRoleMapper, dynamicDataService, permissionEvaluator);
    }
}
