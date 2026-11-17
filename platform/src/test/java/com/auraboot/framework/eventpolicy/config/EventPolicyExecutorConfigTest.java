package com.auraboot.framework.eventpolicy.config;

import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.eventpolicy.executor.*;
import com.auraboot.framework.eventpolicy.model.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.util.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Hermetic wiring contract; actual source authorization and persistence run in the browser journey. */
class EventPolicyExecutorConfigTest {
    @Test void actionAndFailureRecordingUseCoherentIndependentTransactions() throws Exception {
        for (boolean fails : List.of(false, true)) {
            var manager = mock(PlatformTransactionManager.class);
            when(manager.getTransaction(any())).thenAnswer(call -> {
                TransactionDefinition definition = call.getArgument(0);
                assertThat(definition.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
                assertThat(definition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                return new SimpleTransactionStatus();
            });
            var handler = mock(ActionHandler.class);
            when(handler.supports("EXECUTE_COMMAND")).thenReturn(true);
            when(handler.executeWithResult(any(), any())).thenAnswer(call -> {
                if (fails) throw new IllegalStateException("rejected business write");
                return Map.of("recordPid", "approved-record");
            });
            @SuppressWarnings("unchecked") ObjectProvider<ActionHandler> handlers = mock(ObjectProvider.class);
            when(handlers.stream()).thenAnswer(call -> Stream.of(handler));
            var executor = new EventPolicyExecutorConfig().policyExecutor(handlers, mock(IdempotencyStore.class), manager);
            var plan = new ResolvedActionPlan("rule", "EXECUTE_COMMAND", "command", 1, Map.of(), "claim");
            var policy = new EventPolicyResult("policy", EventPolicyResult.Status.MATCHED,
                    List.of("rule"), List.of(), List.of(plan), List.of());
            var result = executor.execute(policy, DecisionContext.of(Map.of()), FailureStrategy.FAIL_FAST, 1L);
            assertThat(result.overallStatus()).isEqualTo(fails ? PolicyExecutionResult.OverallStatus.FAILED
                    : PolicyExecutionResult.OverallStatus.ALL_SUCCESS);
            verify(handler).executeWithResult(any(), any());
            verify(manager, times(fails ? 2 : 1)).getTransaction(any());
            verify(manager, times(fails ? 1 : 0)).rollback(any());
            verify(manager).commit(any());
        }
    }
}
