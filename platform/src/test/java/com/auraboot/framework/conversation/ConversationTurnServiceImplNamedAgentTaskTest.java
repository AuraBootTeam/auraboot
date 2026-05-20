package com.auraboot.framework.conversation;

import com.auraboot.framework.agent.port.AgentChatPort;
import com.auraboot.framework.agent.runtime.TurnExecutionPlanner;
import com.auraboot.framework.agent.runtime.PendingContinuationService;
import com.auraboot.framework.agent.runtime.PendingToolStore;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.aurabot.dto.ChatRequest;
import com.auraboot.framework.aurabot.service.AuraBotChatService;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConversationTurnServiceImpl named-agent task lifecycle")
class ConversationTurnServiceImplNamedAgentTaskTest {

    @Mock private AuraBotChatService chatService;
    @Mock private PendingContinuationService pendingContinuationService;
    @Mock private PendingToolStore pendingToolStore;
    @Mock private AgentChatPort agentChatPort;
    @Mock private DynamicDataMapper dynamicDataMapper;
    @Mock private ResponseSink sink;
    @Mock private TurnSideEffects sideEffects;
    @Mock private TurnSideEffects.Persistence persistence;
    @Mock private TurnSideEffects.EventEmitter eventEmitter;
    @Mock private TurnSideEffects.AuditWriter auditWriter;
    @Mock private TurnSideEffects.MetricsRecorder metricsRecorder;

    @AfterEach
    void clearMeta() {
        MetaContext.clear();
    }

    @Test
    @DisplayName("named-agent task insert failure fails closed before AgentChatPort runs")
    void namedAgentTaskInsertFailureFailsClosed() {
        ConversationTurnServiceImpl service = newService();
        Long tenantId = 1L;
        Long userId = 2L;
        MetaContext.setContext(tenantId, userId, "user-2", "tester");
        when(agentChatPort.agentExists(eq(tenantId), eq("sales_agent"))).thenReturn(true);
        doThrow(new RuntimeException("task table down"))
                .when(dynamicDataMapper).insert(eq("ab_agent_task"), any());

        TurnOutcome outcome = service.runTurn(namedAgentTurn(tenantId, userId, "sales_agent"), sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Failed.class);
        assertThat(((TurnOutcome.Failed) outcome).errorMessage()).contains("task table down");
        verify(agentChatPort, never()).runAgentTurn(any(), any(), any(), any());
    }

    @Test
    @DisplayName("named-agent task close failure is audited without changing returned outcome")
    void namedAgentTaskCloseFailureIsAudited() {
        when(sideEffects.persistence()).thenReturn(persistence);
        when(sideEffects.eventEmitter()).thenReturn(eventEmitter);
        when(sideEffects.auditWriter()).thenReturn(auditWriter);
        when(sideEffects.metricsRecorder()).thenReturn(metricsRecorder);
        ConversationTurnServiceImpl service = newService(sideEffects);
        Long tenantId = 1L;
        Long userId = 2L;
        MetaContext.setContext(tenantId, userId, "user-2", "tester");
        when(agentChatPort.agentExists(eq(tenantId), eq("sales_agent"))).thenReturn(true);
        when(dynamicDataMapper.insert(eq("ab_agent_task"), any())).thenReturn(1);
        when(agentChatPort.runAgentTurn(any(), any(), any(), any()))
                .thenReturn(new TurnOutcome.Success("done", Map.of()));
        doThrow(new RuntimeException("task update down"))
                .when(dynamicDataMapper).update(eq("ab_agent_task"), any(), any());

        TurnOutcome outcome = service.runTurn(namedAgentTurn(tenantId, userId, "sales_agent"), sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Success.class);
        verify(auditWriter).writeFailure(any(), argThat(f ->
                f.errorMessage() != null
                        && f.errorMessage().contains("Named-agent task close failed")
                        && f.errorMessage().contains("task update down")));
    }

    private ConversationTurnServiceImpl newService() {
        return newService(TurnSideEffects.TRULY_DISABLED);
    }

    private ConversationTurnServiceImpl newService(TurnSideEffects sideEffects) {
        ConversationTurnServiceImpl service = new ConversationTurnServiceImpl(
                chatService,
                pendingContinuationService,
                new TurnExecutionPlanner(),
                sideEffects,
                pendingToolStore,
                new ObjectMapper());
        ReflectionTestUtils.setField(service, "agentChatPort", agentChatPort);
        ReflectionTestUtils.setField(service, "dynamicDataMapper", dynamicDataMapper);
        return service;
    }

    private TurnRequest namedAgentTurn(Long tenantId, Long userId, String agentCode) {
        ChatRequest legacy = new ChatRequest();
        legacy.setAgentCode(agentCode);
        legacy.setMessage("hello agent");
        legacy.setSessionId("named-agent-task-test");
        return new TurnRequest(
                tenantId,
                userId,
                3L,
                "web",
                agentCode,
                null,
                null,
                "hello agent",
                null,
                null,
                InboundMode.NEW_FROM_REQUEST,
                null,
                null,
                null,
                null,
                legacy);
    }
}
