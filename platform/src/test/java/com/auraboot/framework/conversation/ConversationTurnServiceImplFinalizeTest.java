package com.auraboot.framework.conversation;

import com.auraboot.framework.agent.port.AgentChatPort;
import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.aurabot.dto.ChatRequest;
import com.auraboot.framework.aurabot.service.AuraBotChatService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase A.4 / B.0 finalize-dispatch tests for {@link ConversationTurnServiceImpl#runTurn}.
 *
 * <p>Asserts that {@code finalizeTurn} fires the right side-effect bundle
 * for each {@link TurnOutcome} variant. Phase A injects
 * {@link TurnSideEffects#observeOnly} so {@code Persistence}, {@code EventEmitter},
 * {@code AuditWriter} are NOOP and {@code MetricsRecorder} is a real
 * Micrometer-backed bean — these tests bypass that real wiring by
 * MockitoBean'ing the entire {@link TurnSideEffects} chokepoint, so we can
 * assert per-branch side-effect calls without depending on the production
 * profile.
 *
 * <p>Branches covered:
 * <ol>
 *     <li>{@link TurnOutcome.Success}            -> persistOutbound + TurnCompletedEvent</li>
 *     <li>{@link TurnOutcome.Interrupted}        -> persistOutbound + TurnCompletedEvent</li>
 *     <li>{@link TurnOutcome.Failed}             -> auditWriter.writeFailure + TurnCompletedEvent</li>
 *     <li>{@link TurnOutcome.PendingConfirmation} with empty partial
 *                                                -> NO persistOutbound + TurnSuspendedEvent</li>
 *     <li>{@link TurnOutcome.PendingConfirmation} with non-blank partial
 *                                                -> persistOutbound + TurnSuspendedEvent</li>
 *     <li>chat impl returns null                 -> defensive translation to Failed branch</li>
 * </ol>
 *
 * <p>All branches must call {@code metricsRecorder.recordTurnEnd}.
 *
 * <p>Companion to {@link ConversationTurnServiceImplDispatchTest} (which covers
 * agentCode dispatch in front of the chat impl) — together they pin down
 * runTurn's full pre/post behaviour.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@Transactional
@Rollback(true)
@DisplayName("ConversationTurnServiceImpl.runTurn — finalize dispatch")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConversationTurnServiceImplFinalizeTest extends BaseIntegrationTest {

    @Autowired
    private ConversationTurnService turnService;

    @MockitoBean
    private AuraBotChatService chatService;

    @MockitoBean
    private AgentChatPort agentChatPort;

    /** The whole side-effect bundle is replaced so each branch's calls are
     *  observable in isolation from the production observeOnly profile. */
    @MockitoBean(name = "turnSideEffects")
    private TurnSideEffects sideEffects;

    private TurnSideEffects.Persistence persistence;
    private TurnSideEffects.EventEmitter eventEmitter;
    private TurnSideEffects.AuditWriter auditWriter;
    private TurnSideEffects.MetricsRecorder metricsRecorder;

    private ResponseSink sink;

    @BeforeEach
    void setUp() {
        persistence    = mock(TurnSideEffects.Persistence.class);
        eventEmitter   = mock(TurnSideEffects.EventEmitter.class);
        auditWriter    = mock(TurnSideEffects.AuditWriter.class);
        metricsRecorder = mock(TurnSideEffects.MetricsRecorder.class);
        when(sideEffects.persistence()).thenReturn(persistence);
        when(sideEffects.eventEmitter()).thenReturn(eventEmitter);
        when(sideEffects.auditWriter()).thenReturn(auditWriter);
        when(sideEffects.metricsRecorder()).thenReturn(metricsRecorder);

        sink = mock(ResponseSink.class);
        when(sink.isClientConnected()).thenReturn(true);
    }

    private TurnRequest auraBotTurn(String message) {
        Long tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();
        Long memberId = getTestTenantMember().getId();
        ChatRequest legacy = new ChatRequest();
        legacy.setMessage(message);
        legacy.setSessionId("finalize-test-" + System.currentTimeMillis());
        legacy.setAgentCode("aurabot");
        return new TurnRequest(
                tenantId, userId, memberId, "web",
                "aurabot",
                null, null,
                message,
                null, null,
                InboundMode.NEW_FROM_REQUEST,
                null,
                null,                                 // inboundMessageId — D.1
                null,                                 // parentTaskPid (DC.3c)
                null,                                 // overrides (DC.3c)
                legacy);
    }

    private void withTestIdentity(Runnable body) {
        Long tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();
        String userPid = getTestUser().getPid();
        String username = getTestUser().getUserName();
        Long memberId = getTestTenantMember().getId();
        MetaContext.setContext(tenantId, userId, userPid, username);
        MetaContext.setMemberId(memberId);
        try {
            body.run();
        } finally {
            MetaContext.clear();
        }
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("Success -> persistOutbound + TurnCompletedEvent + recordTurnEnd")
    void successBranch_firesEndTurnSideEffects() {
        withTestIdentity(() -> {
            TurnOutcome.Success success = new TurnOutcome.Success("hello-final", java.util.Map.of());
            when(chatService.executeAuraBotTurn(any(), any(), any())).thenReturn(success);

            TurnOutcome outcome = turnService.runTurn(auraBotTurn("hi"), sink);

            assertThat(outcome).isSameAs(success);
            verify(metricsRecorder, times(1)).recordTurnBegin(any());
            verify(persistence, times(1)).persistInbound(
                    argThat(req -> "hi".equals(req.userMessage())),
                    nullable(com.auraboot.framework.agent.triage.TriageVerdict.class));
            verify(persistence, times(1)).persistOutbound(any(), same(success), any());
            verify(eventEmitter, times(1)).emit(isA(TurnCompletedEvent.class));
            verify(auditWriter, never()).writeFailure(any(), any());
            verify(metricsRecorder, times(1)).recordTurnEnd(any(), same(success));
        });
    }

    @Test
    @Order(2)
    @DisplayName("Interrupted -> persistOutbound + TurnCompletedEvent (NOT TurnSuspendedEvent)")
    void interruptedBranch_firesEndTurnSideEffects() {
        withTestIdentity(() -> {
            TurnOutcome.Interrupted interrupted = new TurnOutcome.Interrupted("partial-text", "client_disconnect");
            when(chatService.executeAuraBotTurn(any(), any(), any())).thenReturn(interrupted);

            TurnOutcome outcome = turnService.runTurn(auraBotTurn("hi"), sink);

            assertThat(outcome).isSameAs(interrupted);
            verify(persistence, times(1)).persistOutbound(any(), same(interrupted), any());
            verify(eventEmitter, times(1)).emit(isA(TurnCompletedEvent.class));
            verify(eventEmitter, never()).emit(isA(TurnSuspendedEvent.class));
            verify(auditWriter, never()).writeFailure(any(), any());
            verify(metricsRecorder, times(1)).recordTurnEnd(any(), same(interrupted));
        });
    }

    @Test
    @Order(3)
    @DisplayName("Failed -> auditWriter.writeFailure + TurnCompletedEvent (no persistOutbound)")
    void failedBranch_firesAuditAndCompletedEvent() {
        withTestIdentity(() -> {
            TurnOutcome.Failed failed = new TurnOutcome.Failed("LLM timeout", new RuntimeException("boom"));
            when(chatService.executeAuraBotTurn(any(), any(), any())).thenReturn(failed);

            TurnOutcome outcome = turnService.runTurn(auraBotTurn("hi"), sink);

            assertThat(outcome).isSameAs(failed);
            verify(auditWriter, times(1)).writeFailure(any(), same(failed));
            verify(eventEmitter, times(1)).emit(isA(TurnCompletedEvent.class));
            verify(eventEmitter, never()).emit(isA(TurnSuspendedEvent.class));
            verify(persistence, never()).persistOutbound(any(), any(), any());
            verify(metricsRecorder, times(1)).recordTurnEnd(any(), same(failed));
        });
    }

    @Test
    @Order(4)
    @DisplayName("PendingConfirmation w/ empty partial -> TurnSuspendedEvent + NO persistOutbound (P1.4)")
    void pendingConfirmationEmpty_firesSuspendOnly() {
        withTestIdentity(() -> {
            TurnOutcome.PendingConfirmation pc =
                    new TurnOutcome.PendingConfirmation("session-1", "", "tool-1");
            when(chatService.executeAuraBotTurn(any(), any(), any())).thenReturn(pc);

            TurnOutcome outcome = turnService.runTurn(auraBotTurn("hi"), sink);

            assertThat(outcome).isSameAs(pc);
            verify(persistence, never()).persistOutbound(any(), any(), any());
            verify(eventEmitter, times(1)).emit(isA(TurnSuspendedEvent.class));
            verify(eventEmitter, never()).emit(isA(TurnCompletedEvent.class));
            verify(auditWriter, never()).writeFailure(any(), any());
            verify(metricsRecorder, times(1)).recordTurnEnd(any(), same(pc));
        });
    }

    @Test
    @Order(5)
    @DisplayName("PendingConfirmation w/ non-blank partial -> persistOutbound + TurnSuspendedEvent")
    void pendingConfirmationNonBlank_persistsAndSuspends() {
        withTestIdentity(() -> {
            TurnOutcome.PendingConfirmation pc =
                    new TurnOutcome.PendingConfirmation("session-2", "partial assistant text",
                                                          "tool-2");
            when(chatService.executeAuraBotTurn(any(), any(), any())).thenReturn(pc);

            TurnOutcome outcome = turnService.runTurn(auraBotTurn("hi"), sink);

            assertThat(outcome).isSameAs(pc);
            verify(persistence, times(1)).persistOutbound(any(), same(pc), any());
            verify(eventEmitter, times(1)).emit(isA(TurnSuspendedEvent.class));
            verify(eventEmitter, never()).emit(isA(TurnCompletedEvent.class));
            verify(metricsRecorder, times(1)).recordTurnEnd(any(), same(pc));
        });
    }

    @Test
    @Order(6)
    @DisplayName("chat impl returns null -> defensive translation to Failed branch")
    void chatImplReturnsNull_translatesToFailedBranch() {
        withTestIdentity(() -> {
            when(chatService.executeAuraBotTurn(any(), any(), any())).thenReturn(null);

            TurnOutcome outcome = turnService.runTurn(auraBotTurn("hi"), sink);

            assertThat(outcome).isInstanceOf(TurnOutcome.Failed.class);
            assertThat(((TurnOutcome.Failed) outcome).errorMessage()).contains("null outcome");
            verify(auditWriter, times(1)).writeFailure(any(), any());
            verify(eventEmitter, times(1)).emit(isA(TurnCompletedEvent.class));
            verify(metricsRecorder, times(1)).recordTurnEnd(any(), any());
        });
    }

    @Test
    @Order(7)
    @DisplayName("finalizeTurn side-effect throws -> swallowed; outcome still returned to caller")
    void finalizeSideEffectThrows_outcomeStillReturned() {
        withTestIdentity(() -> {
            TurnOutcome.Success success = new TurnOutcome.Success("ok", java.util.Map.of());
            when(chatService.executeAuraBotTurn(any(), any(), any())).thenReturn(success);
            // Make the event emitter throw — this must NOT bubble up to the caller.
            doThrow(new RuntimeException("event bus down"))
                    .when(eventEmitter).emit(any());

            TurnOutcome outcome = turnService.runTurn(auraBotTurn("hi"), sink);

            assertThat(outcome).isSameAs(success);
            // recordTurnEnd may or may not fire depending on whether eventEmitter is
            // before or after metricsRecorder in the dispatch order; we only require
            // that the caller still got the outcome back without a thrown exception.
        });
    }
}
