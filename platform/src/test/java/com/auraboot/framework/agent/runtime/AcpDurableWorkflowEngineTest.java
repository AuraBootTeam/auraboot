package com.auraboot.framework.agent.runtime;

import com.auraboot.framework.agent.service.AgentRunService;
import com.auraboot.framework.agent.service.RunOutcome;
import com.auraboot.framework.conversation.SseResponseSink;
import com.auraboot.framework.conversation.TurnContext;
import com.auraboot.framework.conversation.TurnOutcome;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AcpDurableWorkflowEngineTest {
    @Test
    void aReusedTaskStillReachesAtomicRunAdmission() {
        var runs = mock(AgentRunService.class);
        var tasks = mock(com.auraboot.framework.behavior.service.AnalyticsConversationTaskStore.class);
        var context = mock(TurnContext.class);
        when(context.tenantId()).thenReturn(7L);
        var reference = new com.auraboot.framework.behavior.service.AnalyticsConversationTaskStore.Request("adopt", "request");
        var request = new com.auraboot.framework.agent.dto.ChatRequest();
        request.setMessage("Execute adopted goal");
        request.setAnalyticsExecution(reference);
        when(tasks.create(eq(context), eq(reference), anyMap(), anyMap()))
                .thenReturn(new com.auraboot.framework.behavior.service.AnalyticsConversationTaskStore.Stored("task", false));
        when(runs.executeInitialAnalyticsTaskSync(7L, "task", "aurabot"))
                .thenThrow(new com.auraboot.framework.agent.service.AgentRunTerminalStore.AnalyticsRunAlreadyAdmitted());
        var engine = new AcpDurableWorkflowEngine(runs, mock(DynamicDataMapper.class), new ObjectMapper());
        var provider = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getObject()).thenReturn(tasks);
        org.springframework.test.util.ReflectionTestUtils.setField(engine, "analyticsTasks", provider);
        var sink = mock(com.auraboot.framework.conversation.ResponseSink.class);
        assertThat(engine.startConversationRun(context, request, sink)).isInstanceOf(TurnOutcome.Success.class);
        verify(runs).executeInitialAnalyticsTaskSync(7L, "task", "aurabot");
        verify(sink).onDone(contains("already has a task"), isNull());
        verify(sink, never()).onError(any(), any());
    }

    @Test
    void cancelledRunClosesSseWithoutReturningSuccess() {
        var runs = mock(AgentRunService.class);
        var context = mock(TurnContext.class);
        when(context.tenantId()).thenReturn(7L);
        when(runs.executeTaskSync(7L, "task", "aurabot", "run"))
                .thenReturn(new RunOutcome.Cancelled("run", "cancelled by user interrupt"));
        var mapper = new ObjectMapper();
        var engine = new AcpDurableWorkflowEngine(runs, mock(DynamicDataMapper.class), mapper);
        var emitter = mock(SseEmitter.class);

        TurnOutcome outcome = engine.resumeConversationRun(context, "task", "run",
                new SseResponseSink(emitter, mapper));

        assertThat(outcome).isEqualTo(new TurnOutcome.Interrupted(
                "cancelled by user interrupt", "user_cancelled"));
        verify(emitter).complete();
        verify(emitter, never()).completeWithError(any());
    }
    @Test
    void pendingApprovalClosesSseWhilePreservingPendingOutcome() {
        var runs = mock(AgentRunService.class);
        var context = mock(TurnContext.class);
        when(context.tenantId()).thenReturn(7L);
        when(runs.executeTaskSync(7L, "task", "aurabot", "run"))
                .thenReturn(new RunOutcome.PendingApproval("run", "approval", "Review action"));
        var mapper = new ObjectMapper();
        var engine = new AcpDurableWorkflowEngine(runs, mock(DynamicDataMapper.class), mapper);
        var emitter = mock(SseEmitter.class);
        var outcome = engine.resumeConversationRun(context, "task", "run", new SseResponseSink(emitter, mapper));
        assertThat(outcome).isInstanceOf(TurnOutcome.PendingConfirmation.class);
        verify(emitter).complete();
        verify(emitter, never()).completeWithError(any());
    }

}
