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
 * Phase B.0 dispatch tests for {@link ConversationTurnServiceImpl#runTurn}.
 *
 * <p>Replaces the Phase A {@code AuraBotAgentRoutingTest}, which exercised the
 * legacy {@code AuraBotChatService.streamChat(emitter)} async wrapper that B.0
 * removed. The dispatch surface has moved into {@code ConversationTurnService}
 * so this test covers the new chokepoint: agentCode -> aurabot vs named-agent
 * branches.
 *
 * <p>Verifies (mirrors plan §3 test plan):
 * <ol>
 *     <li>agentCode=aurabot           -> chatService.executeAuraBotTurn called;
 *                                       agentChatPort never touched</li>
 *     <li>agentCode=null              -> aurabot fallthrough (same expectations as #1)</li>
 *     <li>agentCode='' (blank)        -> aurabot fallthrough (same as #1)</li>
 *     <li>agentCode='nonexistent'     -> agentExists=false -> Failed outcome via sink;
 *                                       runAgentTurn never called</li>
 *     <li>agentCode='test_agent'      -> dispatch to agentChatPort.runAgentTurn</li>
 *     <li>chat impl throws            -> runTurn translates to TurnOutcome.Failed</li>
 * </ol>
 *
 * <p>Both {@link AuraBotChatService} and {@link AgentChatPort} are MockitoBean'd
 * so the LLM stack and ACP runtime do not have to be live.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@Transactional
@Rollback(true)
@DisplayName("ConversationTurnServiceImpl.runTurn — agentCode dispatch")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConversationTurnServiceImplDispatchTest extends BaseIntegrationTest {

    @Autowired
    private ConversationTurnService turnService;

    /** Mocked so we can assert dispatch without spinning up the real LLM stack. */
    @MockitoBean
    private AuraBotChatService chatService;

    /** Optional in production; we mock it to assert the named-agent branch. */
    @MockitoBean
    private AgentChatPort agentChatPort;

    private ResponseSink sink;

    @BeforeEach
    void setUpSink() {
        sink = mock(ResponseSink.class);
        when(sink.isClientConnected()).thenReturn(true);
    }

    private TurnRequest buildTurnRequest(String agentCode, String message) {
        Long tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();
        Long memberId = getTestTenantMember().getId();
        ChatRequest legacy = new ChatRequest();
        legacy.setMessage(message);
        legacy.setSessionId("test-session-" + System.currentTimeMillis());
        legacy.setAgentCode(agentCode);
        return new TurnRequest(
                tenantId,
                userId,
                memberId,
                "web",
                agentCode,
                null,
                null,
                message,
                null,
                null,
                InboundMode.NEW_FROM_REQUEST,
                null,
                null,                                 // inboundMessageId — D.1
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
    @DisplayName("agentCode=aurabot -> executeAuraBotTurn; agentChatPort never touched")
    void aurabotExplicit_dispatchesToChatService() {
        withTestIdentity(() -> {
            when(chatService.executeAuraBotTurn(any(), any(), any()))
                    .thenReturn(new TurnOutcome.Success("ok", java.util.Map.of()));

            TurnOutcome outcome = turnService.runTurn(buildTurnRequest("aurabot", "hi"), sink);

            assertThat(outcome).isInstanceOf(TurnOutcome.Success.class);
            verify(chatService, times(1)).executeAuraBotTurn(any(), any(), any());
            verify(agentChatPort, never()).agentExists(any(), any());
            verify(agentChatPort, never()).runAgentTurn(any(), any(), any());
        });
    }

    @Test
    @Order(2)
    @DisplayName("agentCode=null -> aurabot fallthrough")
    void nullAgentCode_dispatchesToChatService() {
        withTestIdentity(() -> {
            when(chatService.executeAuraBotTurn(any(), any(), any()))
                    .thenReturn(new TurnOutcome.Success("ok", java.util.Map.of()));

            TurnOutcome outcome = turnService.runTurn(buildTurnRequest(null, "hi"), sink);

            assertThat(outcome).isInstanceOf(TurnOutcome.Success.class);
            verify(chatService, times(1)).executeAuraBotTurn(any(), any(), any());
            verify(agentChatPort, never()).runAgentTurn(any(), any(), any());
        });
    }

    @Test
    @Order(3)
    @DisplayName("agentCode='   ' (blank) -> aurabot fallthrough")
    void blankAgentCode_dispatchesToChatService() {
        withTestIdentity(() -> {
            when(chatService.executeAuraBotTurn(any(), any(), any()))
                    .thenReturn(new TurnOutcome.Success("ok", java.util.Map.of()));

            TurnOutcome outcome = turnService.runTurn(buildTurnRequest("   ", "hi"), sink);

            assertThat(outcome).isInstanceOf(TurnOutcome.Success.class);
            verify(chatService, times(1)).executeAuraBotTurn(any(), any(), any());
            verify(agentChatPort, never()).runAgentTurn(any(), any(), any());
        });
    }

    @Test
    @Order(4)
    @DisplayName("agentCode='nonexistent' + agentExists=false -> Failed; runAgentTurn never called")
    void agentMissing_failedOutcome() {
        withTestIdentity(() -> {
            Long tenantId = getTestTenant().getId();
            when(agentChatPort.agentExists(eq(tenantId), eq("nonexistent"))).thenReturn(false);

            TurnOutcome outcome = turnService.runTurn(buildTurnRequest("nonexistent", "hi"), sink);

            assertThat(outcome).isInstanceOf(TurnOutcome.Failed.class);
            assertThat(((TurnOutcome.Failed) outcome).errorMessage())
                    .contains("Agent not found or inactive: nonexistent");

            verify(agentChatPort, times(1)).agentExists(eq(tenantId), eq("nonexistent"));
            verify(agentChatPort, never()).runAgentTurn(any(), any(), any());
            verify(chatService, never()).executeAuraBotTurn(any(), any(), any());
            verify(sink, atLeastOnce()).onError(contains("Agent not found"), any());
        });
    }

    @Test
    @Order(5)
    @DisplayName("agentCode='test_agent' -> agentChatPort.runAgentTurn dispatched")
    void agentExists_dispatchesToAgentChatPort() {
        withTestIdentity(() -> {
            Long tenantId = getTestTenant().getId();
            String agentCode = "test_agent";
            when(agentChatPort.agentExists(eq(tenantId), eq(agentCode))).thenReturn(true);
            when(agentChatPort.runAgentTurn(any(), any(), any()))
                    .thenReturn(new TurnOutcome.Success("delegated-ok", java.util.Map.of()));

            TurnOutcome outcome = turnService.runTurn(buildTurnRequest(agentCode, "hi agent"), sink);

            assertThat(outcome).isInstanceOf(TurnOutcome.Success.class);
            assertThat(((TurnOutcome.Success) outcome).finalResponse()).isEqualTo("delegated-ok");

            verify(agentChatPort, times(1)).agentExists(eq(tenantId), eq(agentCode));
            verify(agentChatPort, times(1)).runAgentTurn(
                    argThat(ctx -> ctx.tenantId() == tenantId),
                    argThat(req -> agentCode.equals(req.getAgentCode())
                            && "hi agent".equals(req.getMessage())),
                    same(sink));
            verify(chatService, never()).executeAuraBotTurn(any(), any(), any());
        });
    }

    @Test
    @Order(6)
    @DisplayName("chat impl throws -> runTurn translates to Failed")
    void chatImplThrows_translatesToFailed() {
        withTestIdentity(() -> {
            when(chatService.executeAuraBotTurn(any(), any(), any()))
                    .thenThrow(new RuntimeException("boom inside executeAuraBotTurn"));

            TurnOutcome outcome = turnService.runTurn(buildTurnRequest("aurabot", "hi"), sink);

            assertThat(outcome).isInstanceOf(TurnOutcome.Failed.class);
            assertThat(((TurnOutcome.Failed) outcome).errorMessage())
                    .contains("boom inside executeAuraBotTurn");
        });
    }
}
