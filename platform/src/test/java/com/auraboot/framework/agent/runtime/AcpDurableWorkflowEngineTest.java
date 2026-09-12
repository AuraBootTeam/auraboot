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
}
