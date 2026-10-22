package com.auraboot.framework.eventpolicy.executor;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.eventpolicy.entity.DrtPolicyExecLogEntity;
import com.auraboot.framework.eventpolicy.mapper.DrtPolicyExecLogMapper;
import com.auraboot.framework.plugin.extension.IdentityDirectoryAccessor;
import com.auraboot.framework.tenant.dao.mapper.TenantMemberMapper;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EventPolicyActionRetryServiceTest {
    @AfterEach void clear() { MetaContext.clear(); }
    @Test void restoresOriginalActorWithCurrentMembershipAndRestoresTheCallingContext() {
        var logs=mock(DrtPolicyExecLogMapper.class);var executor=mock(PolicyExecutor.class);
        var identities=mock(IdentityDirectoryAccessor.class);var members=mock(TenantMemberMapper.class);
        var row=row();when(logs.findReadyRetryPending(1)).thenReturn(List.of(row));
        when(identities.findActiveTenantUser(7L,"42")).thenReturn(new IdentityDirectoryAccessor.UserIdentity(42L,"original","original-user","Original user"));
        var member=new TenantMember();member.setId(88L);member.setTenantId(7L);member.setUserId(42L);member.setStatus("active");member.setDeletedFlag(false);
        when(members.findByTenantIdAndUserId(7L,42L)).thenReturn(member);
        when(executor.execute(any(),any(),any(),any(),any(),any())).thenAnswer(invocation->{
            assertThat(MetaContext.getCurrentTenantId()).isEqualTo(7L);assertThat(MetaContext.getCurrentUserId()).isEqualTo(42L);
            assertThat(MetaContext.getCurrentMemberId()).isEqualTo(88L);assertThat(MetaContext.getCommandPermitScope()).isNotEqualTo("ALL");return null;
        });
        MetaContext.setContext(9L,99L,"operator","operator");
        assertThat(new EventPolicyActionRetryService(logs,executor,identities,members, new com.auraboot.framework.event.context.ThreadContextScopes(List.of())).retryReadyActions(1)).isEqualTo(1);
        assertThat(MetaContext.getCurrentTenantId()).isEqualTo(9L);assertThat(MetaContext.getCurrentUserId()).isEqualTo(99L);
    }
    @Test void refusesReplayAfterTheOriginalMemberLosesAccess() {
        var logs=mock(DrtPolicyExecLogMapper.class);var executor=mock(PolicyExecutor.class);var identities=mock(IdentityDirectoryAccessor.class);var members=mock(TenantMemberMapper.class);
        when(logs.findReadyRetryPending(1)).thenReturn(List.of(row()));
        assertThat(new EventPolicyActionRetryService(logs,executor,identities,members, new com.auraboot.framework.event.context.ThreadContextScopes(List.of())).retryReadyActions(1)).isZero();verifyNoInteractions(executor);
    }
    private DrtPolicyExecLogEntity row() {
        var row=new DrtPolicyExecLogEntity();row.setTenantId(7L);row.setActionType("EXECUTE_COMMAND");row.setIdempotencyKey("owned-key");row.setPolicyCode("owned-policy");row.setActionPayload(Map.of("type","EXECUTE_COMMAND","payload",Map.of("modelCode","project","commandCode","project:update")));
        row.setContextPayload(Map.of("actor",Map.of("userId",42L,"userPid","original")));return row;
    }
}
