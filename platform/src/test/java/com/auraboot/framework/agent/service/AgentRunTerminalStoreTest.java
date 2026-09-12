package com.auraboot.framework.agent.service;

import com.auraboot.framework.behavior.outcome.BehaviorOutcomeEvent;
import com.auraboot.framework.behavior.outcome.BehaviorOutcomePublisher;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AgentRunTerminalStoreTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final DynamicDataMapper data = mock(DynamicDataMapper.class);
    private final BehaviorOutcomePublisher outcomes = mock(BehaviorOutcomePublisher.class);
    private final Runnable signal = mock(Runnable.class);
    private final AgentRunTerminalStore store = new AgentRunTerminalStore(jdbc, data, outcomes);
    private final Map<String, Object> run = Map.of("run_status", "success");
    private final Map<String, Object> task = Map.of("task_status", "done");

    @BeforeEach void setup() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }
    @AfterEach void cleanup() {
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    private void row(String status) {
        when(jdbc.queryForList(anyString(), eq(7L), eq("run"), eq("task")))
                .thenReturn(List.of(Map.of("run_status", status, "actor_user_id", 91L, "principal_type", "human_delegated")));
    }
    @Test void completionPersistsScopedPairAndDefersSignalUntilCommit() {
        row("running");
        when(data.update(anyString(), anyMap(), anyMap())).thenReturn(1);
        when(outcomes.publish(any())).thenReturn(true);
        assertThat(store.complete(7L, "run", "task", run, task, signal)).isTrue();
        verify(data).update("ab_agent_run", run, Map.of("tenant_id", 7L, "pid", "run"));
        verify(data).update("ab_agent_task", task, Map.of("tenant_id", 7L, "pid", "task"));
        ArgumentCaptor<BehaviorOutcomeEvent> event = ArgumentCaptor.forClass(BehaviorOutcomeEvent.class);
        verify(outcomes).publish(event.capture());
        assertThat(event.getValue().getUserId()).isEqualTo(91L);
        assertThat(event.getValue().getRunId()).isEqualTo("run");
        assertThat(event.getValue().getProps()).containsEntry("status", "success");
        assertThat(event.getValue().getEventId()).hasSize(36);
        verifyNoInteractions(signal);
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());
        verify(signal).run();
    }
    @Test void cancelledRunCannotBeOverwrittenByLateSuccess() {
        row("cancelled");
        assertThat(store.complete(7L, "run", "task", run, task, signal)).isFalse();
        verifyNoInteractions(data, outcomes, signal);
    }
    @Test void missingTenantScopedRelationshipCannotWrite() {
        when(jdbc.queryForList(anyString(), eq(7L), eq("run"), eq("task"))).thenReturn(List.of());
        assertThatThrownBy(() -> store.complete(7L, "run", "task", run, task, signal)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(data, outcomes, signal);
    }
    @Test void outboxFailurePropagatesWithoutCompletionSignal() {
        row("running");
        when(data.update(anyString(), anyMap(), anyMap())).thenReturn(1);
        when(outcomes.publish(any())).thenThrow(new IllegalStateException("outbox unavailable"));
        assertThatThrownBy(() -> store.complete(7L, "run", "task", run, task, signal)).hasMessage("outbox unavailable");
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        verifyNoInteractions(signal);
    }
    @Test void notificationFailureCannotTurnCommittedSuccessIntoFailure() {
        row("running");
        when(data.update(anyString(), anyMap(), anyMap())).thenReturn(1);
        when(outcomes.publish(any())).thenReturn(true);
        doThrow(new IllegalStateException("signal unavailable")).when(signal).run();
        assertThat(store.complete(7L, "run", "task", run, task, signal)).isTrue();
        assertThatCode(() -> TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit()))
                .doesNotThrowAnyException();
        verify(outcomes, times(1)).publish(any());
    }

}
