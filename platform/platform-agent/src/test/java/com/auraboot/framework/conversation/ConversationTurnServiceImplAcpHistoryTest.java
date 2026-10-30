package com.auraboot.framework.conversation;

import com.auraboot.framework.agent.identity.ExecutionPrincipalResolver;
import com.auraboot.framework.agent.runtime.*;
import com.auraboot.framework.agent.runtime.context.ContextEnvelopeFactory;
import com.auraboot.framework.agent.service.AgentApprovalGateService;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.aurabot.service.AuraBotChatService;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConversationTurnServiceImplAcpHistoryTest {
    private final AgentApprovalGateService gate = mock(AgentApprovalGateService.class);
    private final DynamicDataMapper data = mock(DynamicDataMapper.class);
    private final DurableWorkflowEngine workflow = mock(DurableWorkflowEngine.class);
    private final TurnSideEffects.Persistence persistence = mock(TurnSideEffects.Persistence.class);
    private ConversationTurnServiceImpl service;

    @BeforeEach void setup() {
        MetaContext.setContext(7L, 9L, "approver-9", "Approver");
        TurnSideEffects effects = new TurnSideEffects() {
            public Persistence persistence() { return persistence; }
            public EventEmitter eventEmitter() { return EventEmitter.NOOP; }
            public AuditWriter auditWriter() { return AuditWriter.NOOP; }
            public MetricsRecorder metricsRecorder() { return MetricsRecorder.NOOP; }
        };
        service = new ConversationTurnServiceImpl(mock(AuraBotChatService.class), mock(PendingContinuationService.class),
                mock(TurnExecutionPlanner.class), effects, mock(PendingToolStore.class), new ObjectMapper(),
                mock(ExecutionPrincipalResolver.class), mock(ContextEnvelopeFactory.class));
        ReflectionTestUtils.setField(service, "agentApprovalGateService", gate);
        ReflectionTestUtils.setField(service, "dynamicDataMapper", data);
        ReflectionTestUtils.setField(service, "durableWorkflowEngine", workflow);
        when(gate.findApproval(7L, "approval-1")).thenReturn(Map.of("pid", "approval-1", "run_id", "run-1",
                "task_id", "task-1", "approval_status", "pending"));
        when(gate.isAuthorizedApprover(7L, "approval-1", 9L)).thenReturn(true);
        when(data.selectByQuery(anyString(), eq(Map.of("tenantId", 7L, "taskPid", "task-1"))))
                .thenReturn(List.of(Map.of("input_data", "{\"conversationId\":77,\"agentCode\":\"aurabot\",\"channel\":\"web\"}")));
    }
    @AfterEach void clear() { MetaContext.clear(); }

    @Test void approvalResumesAndFinalizesInOriginalConversationWithRealSinkTrace() {
        when(gate.approve(7L, "approval-1", 9L, false)).thenReturn(Map.of("pid", "approval-1"));
        when(workflow.resumeConversationRun(any(), eq("task-1"), eq("run-1"), any())).thenAnswer(call -> {
            ResponseSink sink = call.getArgument(3); sink.onDone("Created once", "trace-resume");
            return new TurnOutcome.Success("Created once", Map.of());
        });
        var result = service.resumeTurn("approval-1", ConversationTurnService.ConfirmDecision.APPROVED, mock(ResponseSink.class));
        assertThat(result).isInstanceOf(TurnOutcome.Success.class);
        verify(persistence).persistOutbound(argThat(ctx -> Long.valueOf(77L).equals(ctx.conversationId())
                && ctx.tenantId()==7L && ctx.userId()==9L && "web".equals(ctx.channel())),
                any(), argThat(artifacts -> "trace-resume".equals(artifacts.traceId())));
    }
    @Test void rejectionIsPersistedInOriginalConversationWithoutResuming() {
        when(gate.reject(7L, "approval-1", 9L, "User denied the operation")).thenReturn(Map.of("pid", "approval-1"));
        var result = service.resumeTurn("approval-1", ConversationTurnService.ConfirmDecision.DENIED, mock(ResponseSink.class));
        assertThat(result).isInstanceOf(TurnOutcome.Interrupted.class);
        verify(persistence).persistOutbound(argThat(ctx -> Long.valueOf(77L).equals(ctx.conversationId())), any(), any());
        verifyNoInteractions(workflow);
    }
    @Test void unreadableTaskRoutingFailsBeforeConsumingApproval() {
        when(data.selectByQuery(anyString(), anyMap())).thenReturn(List.of(Map.of("input_data", "{invalid")));
        var result = service.resumeTurn("approval-1", ConversationTurnService.ConfirmDecision.APPROVED, mock(ResponseSink.class));
        assertThat(result).isInstanceOf(TurnOutcome.Failed.class);
        verify(gate, never()).approve(any(), any(), any(), anyBoolean());
        verifyNoInteractions(workflow);
    }
}
