package com.auraboot.framework.eventpolicy.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.eventpolicy.entity.DrtOutboxEntity;
import com.auraboot.framework.eventpolicy.mapper.DrtOutboxMapper;
import com.auraboot.framework.eventpolicy.model.*;
import com.auraboot.framework.eventpolicy.executor.PolicyExecutionResult;
import com.auraboot.framework.eventpolicy.service.EventPolicyRuntimeService;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.dao.mapper.TenantMemberMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutboxServiceImplTest {
    private final DrtOutboxMapper rows = mock(DrtOutboxMapper.class);
    private final EventPolicyRuntimeService runtime = mock(EventPolicyRuntimeService.class);
    private final TenantMemberMapper members = mock(TenantMemberMapper.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final ObjectMapper json = new ObjectMapper();
    private final OutboxServiceImpl service = new OutboxServiceImpl(rows, runtime, json, transactions, members, new com.auraboot.framework.event.context.ThreadContextScopes(List.of()));
    private DrtOutboxEntity row;
    private TenantMember member;

    @BeforeEach void setup() {
        MetaContext.setContext(7L, 99L, "worker", "Worker");
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        row = new DrtOutboxEntity(); row.setTenantId(7L); row.setEventId("event-1"); row.setStatus("PENDING");
        row.setEventType("COMMAND_COMPLETED"); row.setTargetType("MODEL"); row.setTargetKey("acceptance");
        row.setContextJson(json.valueToTree(Map.of("actor", Map.of("userId", 42L, "userPid", "operator", "username", "Operator"))));
        when(rows.findByEventIdForUpdate(7L, "event-1")).thenReturn(row);
        member = new TenantMember(); member.setId(123L); member.setTenantId(7L); member.setUserId(42L); member.setStatus("active");
        when(members.findByTenantIdAndUserId(7L, 42L)).thenReturn(member);
    }
    @AfterEach void cleanup() { MetaContext.clear(); }
    private EventPolicyExecutionResult result(PolicyExecutionResult.OverallStatus status) {
        return new EventPolicyExecutionResult(new EventPolicyResult("accepted", EventPolicyResult.Status.MATCHED,
                List.of("register"), List.of(), List.of(), List.of()), new PolicyExecutionResult("accepted", status, List.of()));
    }
    @Test void restoresCurrentMembershipAndRestoresWorkerAfterExecution() {
        when(runtime.runAndExecute(any(), any(), any(), any())).thenAnswer(call -> {
            assertThat(MetaContext.getCurrentUserId()).isEqualTo(42L);
            assertThat(MetaContext.snapshot().memberId()).isEqualTo(123L);
            return result(PolicyExecutionResult.OverallStatus.ALL_SUCCESS);
        });
        service.processEvent("event-1");
        assertThat(row.getStatus()).isEqualTo("PROCESSED");
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(MetaContext.getCurrentUserId()).isEqualTo(99L);
        verify(transactions).commit(any());
    }
    @Test void revokedOrMismatchedMembershipNeverRunsBusinessCommands() {
        for (String scenario : List.of("inactive", "foreign", "deleted")) {
            member.setStatus("active");member.setTenantId(7L);member.setDeletedFlag(false);
            if (scenario.equals("inactive")) member.setStatus("inactive");
            if (scenario.equals("foreign")) member.setTenantId(8L);
            if (scenario.equals("deleted")) member.setDeletedFlag(true);
            row.setStatus("PENDING");service.processEvent("event-1");
            assertThat(row.getStatus()).isEqualTo("FAILED");
        }
        verifyNoInteractions(runtime);
    }
    @Test void partialExecutionIsNotMarkedProcessedAndCompletedEventsAreNotRepeated() {
        when(runtime.runAndExecute(any(), any(), any(), any())).thenReturn(result(PolicyExecutionResult.OverallStatus.PARTIAL_SUCCESS));
        service.processEvent("event-1");
        assertThat(row.getStatus()).isEqualTo("FAILED");
        service.processEvent("event-1");
        verify(runtime, times(1)).runAndExecute(any(),any(),any(),any());
    }
    @Test void batchUsesTheSameLockedExactEventPath() {
        when(rows.findPending(7L, 10)).thenReturn(List.of(row));
        when(runtime.runAndExecute(any(), any(), any(), any())).thenReturn(result(PolicyExecutionResult.OverallStatus.NOTHING_TO_DO));
        assertThat(service.processPending(10)).isEqualTo(1);
        verify(rows).findByEventIdForUpdate(7L, "event-1");
        assertThat(row.getStatus()).isEqualTo("PROCESSED");
    }
}
