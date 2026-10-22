package com.auraboot.framework.agent.integration.context;

import com.auraboot.framework.agent.identity.ExecutionPrincipal;
import com.auraboot.framework.agent.identity.ExecutionPrincipalContext;
import com.auraboot.framework.agent.runtime.context.ContextEnvelope;
import com.auraboot.framework.agent.runtime.context.ContextEnvelopeContext;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.event.context.ThreadContextScopes;
import com.auraboot.framework.eventpolicy.entity.DrtOutboxEntity;
import com.auraboot.framework.eventpolicy.entity.DrtPolicyExecLogEntity;
import com.auraboot.framework.eventpolicy.executor.EventPolicyActionRetryService;
import com.auraboot.framework.eventpolicy.executor.PolicyExecutor;
import com.auraboot.framework.eventpolicy.executor.PolicyExecutionResult;
import com.auraboot.framework.eventpolicy.mapper.DrtOutboxMapper;
import com.auraboot.framework.eventpolicy.mapper.DrtPolicyExecLogMapper;
import com.auraboot.framework.eventpolicy.model.EventPolicyExecutionResult;
import com.auraboot.framework.eventpolicy.model.EventPolicyResult;
import com.auraboot.framework.eventpolicy.service.EventPolicyRuntimeService;
import com.auraboot.framework.eventpolicy.service.impl.OutboxServiceImpl;
import com.auraboot.framework.plugin.extension.IdentityDirectoryAccessor;
import com.auraboot.framework.tenant.dao.mapper.TenantMemberMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real context contributor with hermetic action collaborators: no inherited AI authority. */
class EventPolicyAgentContextTest {
    private final ExecutionPrincipal worker = mock(ExecutionPrincipal.class);
    private final ContextEnvelope envelope = mock(ContextEnvelope.class);
    private final ThreadContextScopes scopes = new ThreadContextScopes(List.of(new AgentThreadContextContributor()));

    private void bindWorker() {
        MetaContext.setContext(7L, 99L, "worker", "Worker");
        ExecutionPrincipalContext.restore(worker);
        ContextEnvelopeContext.restore(envelope);
    }

    private void assertCleanAction() {
        assertThat(ExecutionPrincipalContext.current()).isEmpty();
        assertThat(ContextEnvelopeContext.current()).isEmpty();
    }

    private void assertWorkerRestored() {
        assertThat(ExecutionPrincipalContext.current()).containsSame(worker);
        assertThat(ContextEnvelopeContext.current()).containsSame(envelope);
        assertThat(MetaContext.getCurrentUserId()).isEqualTo(99L);
    }

    @AfterEach
    void clearContexts() {
        MetaContext.clear();
        ExecutionPrincipalContext.clear();
        ContextEnvelopeContext.clear();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void retryClearsAndRestoresAgentAuthorityEvenWhenActionThrows(boolean fail) {
        bindWorker();
        var logs = mock(DrtPolicyExecLogMapper.class);
        var executor = mock(PolicyExecutor.class);
        var row = new DrtPolicyExecLogEntity();
        row.setTenantId(8L);
        row.setActionType("EXECUTE_COMMAND");
        row.setIdempotencyKey("owned-retry");
        row.setActionPayload(Map.of("type", "EXECUTE_COMMAND", "payload", Map.of("commandCode", "item:update")));
        when(logs.findReadyRetryPending(1)).thenReturn(List.of(row));
        when(executor.execute(any(), any(), any(), any(), any(), any())).thenAnswer(call -> {
            assertCleanAction();
            assertThat(MetaContext.getCurrentTenantId()).isEqualTo(8L);
            if (fail) throw new IllegalStateException("action failed");
            return null;
        });
        var service = new EventPolicyActionRetryService(logs, executor, mock(IdentityDirectoryAccessor.class),
                mock(TenantMemberMapper.class), scopes);
        assertThat(service.retryReadyActions(1)).isEqualTo(fail ? 0 : 1);
        verify(executor).execute(any(), any(), any(), any(), any(), any());
        assertWorkerRestored();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void outboxClearsAndRestoresAgentAuthorityAndPreservesDurableOutcome(boolean fail) {
        bindWorker();
        var rows = mock(DrtOutboxMapper.class);
        var runtime = mock(EventPolicyRuntimeService.class);
        var transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var row = new DrtOutboxEntity();
        row.setTenantId(7L);
        row.setEventId("owned-event");
        row.setStatus("PENDING");
        row.setEventType("COMMAND_COMPLETED");
        row.setTargetType("MODEL");
        row.setTargetKey("owned-target");
        when(rows.findByEventIdForUpdate(7L, "owned-event")).thenReturn(row);
        when(runtime.runAndExecute(any(), any(), any(), any())).thenAnswer(call -> {
            assertCleanAction();
            assertThat(MetaContext.getCurrentUserId()).isNull();
            if (fail) throw new IllegalStateException("action failed");
            return new EventPolicyExecutionResult(new EventPolicyResult("owned-policy", EventPolicyResult.Status.MATCHED,
                    List.of(), List.of(), List.of(), List.of()),
                    new PolicyExecutionResult("owned-policy", PolicyExecutionResult.OverallStatus.ALL_SUCCESS, List.of()));
        });
        var service = new OutboxServiceImpl(rows, runtime, new ObjectMapper(), transactions,
                mock(TenantMemberMapper.class), scopes);
        service.processEvent("owned-event");
        assertThat(row.getStatus()).isEqualTo(fail ? "FAILED" : "PROCESSED");
        assertThat(row.getAttempts()).isEqualTo(1);
        if (fail) assertThat(row.getLastError()).isEqualTo("action failed");
        verify(rows).updateById(row);
        verify(transactions).commit(any());
        assertWorkerRestored();
    }
}
