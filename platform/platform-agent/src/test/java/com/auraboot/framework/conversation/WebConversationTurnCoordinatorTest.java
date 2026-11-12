package com.auraboot.framework.conversation;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Map;

class WebConversationTurnCoordinatorTest {
    @Test void oneConversationCannotOverlapButOtherConversationsAndTenantsCan() {
        var coordinator = new WebConversationTurnCoordinator();
        try (var lease = coordinator.enter(1L, 8L, "request")) {
            assertThat(coordinator.status(1L,8L)).containsEntry("state","running");
            assertThatThrownBy(() -> coordinator.enter(1L,8L,"resume")).isInstanceOf(WebConversationTurnCoordinator.ConversationBusyException.class);
            try (var otherConversation = coordinator.enter(1L,9L,"request"); var otherTenant=coordinator.enter(2L,8L,"request")) {
                assertThat(coordinator.status(2L,8L)).containsEntry("state","running");
            }
        }
        assertThat(coordinator.status(1L,8L)).containsEntry("state","idle");
        try (var lease = coordinator.enter(1L,8L,"resume")) { assertThat(lease).isNotNull(); }
    }
    @Test void staleLeaseCloseCannotReleaseANewerTurn() {
        var coordinator = new WebConversationTurnCoordinator();
        var first=coordinator.enter(1L,8L,"request");first.close();
        try(var second=coordinator.enter(1L,8L,"resume")) {
            first.close();
            assertThatThrownBy(() -> coordinator.enter(1L,8L,"request")).isInstanceOf(WebConversationTurnCoordinator.ConversationBusyException.class);
        }
    }
    @Test void busyResumeDoesNotConsumeThePendingConfirmation() {
        com.auraboot.framework.application.tenant.MetaContext.setContext(1L,2L,"u","User");
        try {
            var pending=mock(com.auraboot.framework.agent.runtime.PendingToolStore.class);
            when(pending.peekRouting("pending",1L,2L)).thenReturn(new com.auraboot.framework.agent.runtime.PendingToolStore.Routing(8L,"web"));
            var service=new ConversationTurnServiceImpl(mock(com.auraboot.framework.aurabot.service.AuraBotChatService.class),
                    mock(com.auraboot.framework.agent.runtime.PendingContinuationService.class),new com.auraboot.framework.agent.runtime.TurnExecutionPlanner(),
                    TurnSideEffects.TRULY_DISABLED,pending,new com.fasterxml.jackson.databind.ObjectMapper(),
                    mock(com.auraboot.framework.agent.identity.ExecutionPrincipalResolver.class),new com.auraboot.framework.agent.runtime.context.ContextEnvelopeFactory());
            var coordinator=new WebConversationTurnCoordinator();
            org.springframework.test.util.ReflectionTestUtils.setField(service,"webTurnCoordinator",coordinator);
            ResponseSink sink=mock(ResponseSink.class);
            try(var lease=coordinator.enter(1L,8L,"request")) {
                assertThat(service.resumeTurn("pending",ConversationTurnService.ConfirmDecision.APPROVED,sink)).isInstanceOf(TurnOutcome.Failed.class);
                verify(pending,never()).consumePendingForOwner(anyString(),any(),any());
                verify(sink).onError(eq("$i18n:aurabot.execution.conversation_busy"),isNull());
            }
        } finally { com.auraboot.framework.application.tenant.MetaContext.clear(); }
    }

    @Test void terminalIsEmittedOnlyAfterFinalizationAndAnErrorCannotBecomeSuccess() {
        ResponseSink sink=mock(ResponseSink.class);
        var deferred=new DeferredTerminalResponseSink(sink);
        deferred.onTextChunk("streamed");deferred.onToolResult("t",Map.of("success",true),true);
        deferred.onDone("complete","trace");
        verify(sink).onTextChunk("streamed");verify(sink,never()).onDone(any(),any());
        deferred.flushTerminal();deferred.flushTerminal();
        verify(sink,times(1)).onDone("complete","trace");
        var failure=new DeferredTerminalResponseSink(sink);
        failure.onError("denied","trace");failure.onDone("success","trace");failure.flushTerminal();
        verify(sink).onError("denied","trace");verify(sink,never()).onDone("success","trace");
    }
}
