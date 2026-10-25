package com.auraboot.framework.automation.listener;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.agent.identity.ExecutionPrincipal;
import com.auraboot.framework.agent.identity.ExecutionPrincipalContext;
import com.auraboot.framework.agent.runtime.context.ContextEnvelope;
import com.auraboot.framework.agent.runtime.context.ContextEnvelopeContext;
import com.auraboot.framework.automation.trigger.AutomationTriggerService;
import com.auraboot.framework.meta.service.impl.CommandStateCheckExecutor;
import com.auraboot.module.meta.event.CommandCompletedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AutomationCommandEventBridgeContextTest {
    private final AutomationTriggerService triggers = mock(AutomationTriggerService.class);
    private final CommandStateCheckExecutor states = mock(CommandStateCheckExecutor.class);
    private final AutomationCommandEventBridge bridge = new AutomationCommandEventBridge(triggers, states,
            new com.auraboot.framework.event.context.ThreadContextScopes(java.util.List.of(
                new com.auraboot.framework.agent.integration.context.AgentThreadContextContributor())));
    @AfterEach void cleanup() {
        MetaContext.clear();
        ExecutionPrincipalContext.clear();
        ContextEnvelopeContext.clear();
    }

    private CommandCompletedEvent event(Long tenant) {
        CommandCompletedEvent event = new CommandCompletedEvent(tenant, "owned-record", "orders", Map.of(), "orders:create", "create");
        event.addMetadata("actorId", 11L);
        event.addMetadata("actorPid", "actor");
        event.addMetadata("actorName", "Actor");
        return event;
    }

    @Test void eventCarriesTenantAndActorAfterRequestCleanup() {
        AtomicReference<MetaContext.Snapshot> observed = new AtomicReference<>();
        doAnswer(invocation -> { observed.set(MetaContext.snapshot()); return null; }).when(triggers).onRecordCreate(eq("orders"), eq("owned-record"), any());
        bridge.onCommandCompleted(event(99L));
        assertThat(observed.get()).isNotNull();
        assertThat(observed.get().tenantId()).isEqualTo(99L);
        assertThat(observed.get().userId()).isEqualTo(11L);
        assertThat(MetaContext.snapshot()).isNull();
    }

    @Test void failedDispatchRestoresExecutingThreadWithoutInheritingItsBypass() {
        MetaContext.setContext(199L, 22L, "worker", "Worker");
        ExecutionPrincipal principal = mock(ExecutionPrincipal.class);
        ContextEnvelope envelope = mock(ContextEnvelope.class);
        ExecutionPrincipalContext.restore(principal);
        ContextEnvelopeContext.restore(envelope);
        MetaContext.Snapshot previous = MetaContext.snapshot();
        AtomicReference<Long> tenant = new AtomicReference<>();
        AtomicReference<Boolean> bypass = new AtomicReference<>();
        doAnswer(invocation -> {
            tenant.set(MetaContext.getCurrentTenantId()); bypass.set(MetaContext.isTenantFilterBypassed());
            assertThat(ExecutionPrincipalContext.current()).isEmpty();
            assertThat(ContextEnvelopeContext.current()).isEmpty();
            throw new IllegalStateException("fixture trigger failed");
        }).when(triggers).onRecordCreate(eq("orders"), eq("owned-record"), any());
        MetaContext.runWithoutTenantFilter(() -> {
            bridge.onCommandCompleted(event(99L));
            assertThat(MetaContext.isTenantFilterBypassed()).isTrue();
        });
        assertThat(tenant.get()).isEqualTo(99L);
        assertThat(bypass.get()).isFalse();
        assertThat(MetaContext.snapshot()).isEqualTo(previous);
        assertThat(ExecutionPrincipalContext.current()).contains(principal);
        assertThat(ContextEnvelopeContext.current()).contains(envelope);
    }

    @Test void updateWithServerTransitionDispatchesStateAndUpdateExactlyOnce() {
        CommandCompletedEvent event = new CommandCompletedEvent(99L, "owned-record", "orders",
                Map.of("status", "client-value"), "orders:complete", "UPDATE");
        event.addMetadata("actorId", 11L);
        event.addMetadata("beforeSnapshot", Map.of("order_status", "in_review"));
        event.addMetadata("commandStateTransition", Map.of("stateField", "order_status", "toState", "done"));
        bridge.onCommandCompleted(event);
        verify(triggers).onStateChange("orders", "owned-record", "in_review", "done");
        verifyNoInteractions(states);
        verify(triggers).onRecordUpdate(eq("orders"), eq("owned-record"), any(), any());
        verifyNoMoreInteractions(triggers);
    }

    @Test void ordinaryUpdatePayloadCannotImpersonateServerTransitionMetadata() {
        CommandCompletedEvent event = new CommandCompletedEvent(99L, "owned-record", "orders",
                Map.of("commandStateTransition", Map.of("stateField", "order_status", "toState", "done")),
                "orders:update", "UPDATE");
        event.addMetadata("actorId", 11L);
        bridge.onCommandCompleted(event);
        verify(triggers).onRecordUpdate(eq("orders"), eq("owned-record"), isNull(), any());
        verifyNoMoreInteractions(triggers);
    }

    @Test void malformedTransitionMetadataDoesNotPromoteOrdinaryUpdate() {
        CommandCompletedEvent event = new CommandCompletedEvent(99L, "owned-record", "orders",
                Map.of(), "orders:update", "UPDATE");
        event.addMetadata("actorId", 11L);
        event.addMetadata("commandStateTransition", Map.of("stateField", "order_status", "toState", ""));
        bridge.onCommandCompleted(event);
        verify(triggers).onRecordUpdate(eq("orders"), eq("owned-record"), isNull(), any());
        verifyNoMoreInteractions(triggers);
    }

    @Test void explicitLegacyTransitionStillResolvesCommittedState() {
        CommandStateCheckExecutor states = mock(CommandStateCheckExecutor.class);
        when(states.getStateFieldForModel("orders")).thenReturn("order_status");
        when(states.readCurrentState(99L, "orders", "owned-record", "order_status")).thenReturn("done");
        CommandCompletedEvent event = new CommandCompletedEvent(99L, "owned-record", "orders", Map.of(),
                "orders:complete", "state_transition");
        event.addMetadata("actorId", 11L);
        event.addMetadata("beforeSnapshot", Map.of("order_status", "in_review"));
        new AutomationCommandEventBridge(triggers, states,
            new com.auraboot.framework.event.context.ThreadContextScopes(java.util.List.of(
                new com.auraboot.framework.agent.integration.context.AgentThreadContextContributor()))).onCommandCompleted(event);
        verify(triggers).onStateChange("orders", "owned-record", "in_review", "done");
        verify(triggers).onRecordUpdate(eq("orders"), eq("owned-record"), any(), any());
        verifyNoMoreInteractions(triggers);
    }

    @Test void missingTenantFailsClosedWithoutDispatch() {
        bridge.onCommandCompleted(event(null));
        verifyNoInteractions(triggers);
    }

    @Test void invalidActorFailsClosedWithoutDispatch() {
        CommandCompletedEvent event = event(99L);
        event.addMetadata("actorId", "not-an-identity");
        bridge.onCommandCompleted(event);
        verifyNoInteractions(triggers);
        assertThat(MetaContext.snapshot()).isNull();
    }
}
