package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.service.impl.pipeline.CommandAuthorizationVerdict;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPermitPlan;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipeline;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeferredCommandAuthorizationTest {
    private final CommandExecutionTransaction transactions = mock(CommandExecutionTransaction.class);
    private final CommandPipeline pipeline = mock(CommandPipeline.class);
    private final com.auraboot.framework.permission.mapper.HumanActorMapper actors = mock(com.auraboot.framework.permission.mapper.HumanActorMapper.class);
    private final DeferredCommandAuthorization authorization = new DeferredCommandAuthorization(transactions, pipeline, actors);
    private final Map<String,Object> currentParameters = new HashMap<>(Map.of("async", false));

    @BeforeEach void setup() {
        MetaContext.setContext(1L, 42L, "actor-42", "actor", Set.of(9L));
        MetaContext.setMemberId(7L);
        when(actors.findActive(1L,42L,"actor-42")).thenReturn(java.util.List.of(Map.of("user_id",42L,"member_id",7L,"user_pid","actor-42")));
        when(transactions.execute(eq("bom:process_conversion_task"), eq(false), any())).thenAnswer(call ->
                ((Supplier<?>) call.getArgument(2)).get());
        doAnswer(call -> {
            CommandPipelineContext context = call.getArgument(0);
            context.setExecConfig(Map.of("handlerParams", currentParameters));
            context.setPayload(Map.of("taskId", "task-1"));
            context.setAuthorizationVerdict(CommandAuthorizationVerdict.authorized("current.duty"));
            context.setPermitPlan(CommandPermitPlan.fromPhaseDecisions(
                    java.util.List.of(CommandPermitPlan.PhaseDecision.permit("authorization")), "task-1",
                    CommandPermitPlan.ScopeGrade.TARGET, 11L));
            return null;
        }).when(pipeline).authorizeDeferredHandler(any(), any(), any());
    }

    @AfterEach void cleanup() {
        MetaContext.clear();
        TransactionSynchronizationManager.clear();
    }

    private DeferredCommandAuthorization.Decision authorize() {
        return authorization.authorize("bom:process_conversion_task", "bom:process_conversion_task",
                "bom_conversion_task_pcba", "task-1", Map.of("taskId", "task-1"), "request-1", null);
    }
    private AsyncTaskExecutionContext.Scope claimed(Long tenant, Long user) {
        return AsyncTaskExecutionContext.enter(new AsyncTaskExecutionContext.Snapshot(99L, "job-99", tenant, user, "lease"));
    }

    @Test void currentBoundaryProducesAnImmutableDecisionWithoutInstallingAuthority() {
        try (var ignored = claimed(1L, 42L)) {
            var decision = authorize();
            currentParameters.put("async", true);
            assertThat(decision.authority()).isEqualTo("current.duty");
            assertThat(decision.scope()).isEqualTo("TARGET");
            assertThat(decision.expectedVersion()).isEqualTo(11L);
            assertThat(decision.handlerParams()).containsEntry("async", false);
            assertThatThrownBy(() -> decision.payload().put("other", true)).isInstanceOf(UnsupportedOperationException.class);
            assertThat(MetaContext.hasCommandAuthority()).isFalse();
            assertThat(MetaContext.hasCommandPermitScope()).isFalse();
            verify(pipeline).authorizeDeferredHandler(any(), eq("bom:process_conversion_task"), eq("bom_conversion_task_pcba"));
        }
    }

    @Test void unclaimedDeliveryCannotReachPolicy() {
        assertThatThrownBy(this::authorize).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(pipeline);
    }

    @Test void claimTenantAndCreatorMustMatchTheReloadedActor() {
        for (var identity : java.util.List.of(new Long[]{2L,42L},new Long[]{1L,43L})) {
            try (var ignored = claimed(identity[0], identity[1])) {
                assertThatThrownBy(this::authorize).isInstanceOf(AccessDeniedException.class);
            }
        }
        verifyNoInteractions(pipeline);
    }

    @Test void incompleteHumanIdentityCannotReachPolicy() {
        try (var ignored = claimed(1L, 42L)) {
            MetaContext.setMemberId(null);
            assertThatThrownBy(this::authorize).isInstanceOf(AccessDeniedException.class);
            MetaContext.setMemberId(7L);
            MetaContext.setContext(1L,42L,null,"actor",Set.of(9L));
            MetaContext.setMemberId(7L);
            assertThatThrownBy(this::authorize).isInstanceOf(AccessDeniedException.class);
            verifyNoInteractions(pipeline);
        }
    }

    @Test void inheritedAuthorityCannotEnterFreshEvaluation() {
        try (var ignored = claimed(1L, 42L)) {
            assertThatThrownBy(() -> MetaContext.runWithCommandAuthority("old.allow", this::authorize))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> MetaContext.runWithCommandPermitPlan("ALL", 7L,
                    "bom_conversion_task_pcba","task-1",this::authorize)).isInstanceOf(AccessDeniedException.class);
            verifyNoInteractions(pipeline);
        }
    }

    @Test void deliveryDoesNotJoinACallerTransaction() {
        try (var ignored = claimed(1L,42L)) {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            assertThatThrownBy(this::authorize).isInstanceOf(AccessDeniedException.class);
            verifyNoInteractions(pipeline);
        }
    }

    @Test void stalePidOrMembershipCannotStandInForTheActualHuman() {
        try (var ignored = claimed(1L,42L)) {
            when(actors.findActive(1L,42L,"actor-42")).thenReturn(java.util.List.of(Map.of("user_id",42L,"member_id",8L,"user_pid","actor-42")));
            assertThatThrownBy(this::authorize).isInstanceOf(AccessDeniedException.class);
            verifyNoInteractions(pipeline);
        }
    }

    @Test void currentPolicyRefusalCannotProduceAnExecutableDecision() {
        doThrow(new AccessDeniedException("revoked")).when(pipeline).authorizeDeferredHandler(any(),any(),any());
        try (var ignored = claimed(1L,42L)) {
            assertThatThrownBy(this::authorize).isInstanceOf(AccessDeniedException.class).hasMessage("revoked");
            assertThat(MetaContext.hasCommandAuthority()).isFalse();
        }
    }
}
