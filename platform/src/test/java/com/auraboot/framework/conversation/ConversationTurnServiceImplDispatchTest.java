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
import org.springframework.jdbc.core.JdbcTemplate;
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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ResponseSink sink;

    @BeforeEach
    void setUpSink() {
        sink = mock(ResponseSink.class);
        when(sink.isClientConnected()).thenReturn(true);
    }

    private TurnRequest buildTurnRequest(String agentCode, String message) {
        return buildTurnRequest(agentCode, message, null);
    }

    private TurnRequest buildTurnRequest(String agentCode, String message, java.util.Map<String, Object> pageContext) {
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
                pageContext,
                null,
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
    @DisplayName("read-only contextual triage -> chat runtime, not ACP durable run")
    void readOnlyContextualTriage_dispatchesToChatService() {
        withTestIdentity(() -> {
            when(chatService.executeAuraBotTurn(any(), any(), any()))
                    .thenReturn(new TurnOutcome.Success("contextual-ok", java.util.Map.of()));

            TurnOutcome outcome = turnService.runTurn(buildTurnRequest(
                    "aurabot",
                    "what is this page showing",
                    java.util.Map.of("pageKey", "crm_customer_list")), sink);

            assertThat(outcome).isInstanceOf(TurnOutcome.Success.class);
            verify(chatService, times(1)).executeAuraBotTurn(
                    argThat(ctx -> ctx.triageBucket() == com.auraboot.framework.agent.triage.TriageBucket.CONTEXTUAL_ANSWER
                            && !ctx.allowedReadOnlyTools().isEmpty()),
                    any(),
                    any());
            verify(agentChatPort, never()).runAgentTurn(any(), any(), any(),
                    org.mockito.ArgumentMatchers.<com.auraboot.framework.agent.port.AgentTurnOverrides>any());
        });
    }

    @Test
    @Order(6)
    @DisplayName("read-only customer statistics triage -> chat runtime, not ACP durable run")
    void readOnlyCustomerStatistics_dispatchesToChatService() {
        withTestIdentity(() -> {
            when(chatService.executeAuraBotTurn(any(), any(), any()))
                    .thenReturn(new TurnOutcome.Success("stats-ok", java.util.Map.of()));

            TurnOutcome outcome = turnService.runTurn(buildTurnRequest(
                    "aurabot",
                    "统计客户信息"), sink);

            assertThat(outcome).isInstanceOf(TurnOutcome.Success.class);
            verify(chatService, times(1)).executeAuraBotTurn(
                    argThat(ctx -> ctx.triageBucket() == com.auraboot.framework.agent.triage.TriageBucket.CONTEXTUAL_ANSWER
                            && !ctx.allowedReadOnlyTools().isEmpty()),
                    any(),
                    any());
            verify(agentChatPort, never()).runAgentTurn(any(), any(), any(),
                    org.mockito.ArgumentMatchers.<com.auraboot.framework.agent.port.AgentTurnOverrides>any());
        });
    }

    @Test
    @Order(7)
    @DisplayName("simple write triage -> chat runtime for late tool-policy confirmation, not ACP durable run")
    void simpleWriteTriage_dispatchesToChatServiceForLatePolicyBinding() {
        withTestIdentity(() -> {
            when(chatService.executeAuraBotTurn(any(), any(), any()))
                    .thenReturn(new TurnOutcome.Success("write-intent-ok", java.util.Map.of()));

            TurnOutcome outcome = turnService.runTurn(buildTurnRequest(
                    "aurabot",
                    "创建客户",
                    java.util.Map.of("pageKey", "crm_customer_list")), sink);

            assertThat(outcome).isInstanceOf(TurnOutcome.Success.class);
            verify(chatService, times(1)).executeAuraBotTurn(
                    argThat(ctx -> ctx.triageBucket() == com.auraboot.framework.agent.triage.TriageBucket.LIGHT_CHAT
                            && ctx.allowedReadOnlyTools().isEmpty()),
                    any(),
                    any());
            verify(agentChatPort, never()).runAgentTurn(any(), any(), any(),
                    org.mockito.ArgumentMatchers.<com.auraboot.framework.agent.port.AgentTurnOverrides>any());
        });
    }

    @Test
    @Order(9)
    @DisplayName("agentCode='test_agent' -> agentChatPort.runAgentTurn dispatched")
    void agentExists_dispatchesToAgentChatPort() {
        withTestIdentity(() -> {
            Long tenantId = getTestTenant().getId();
            String agentCode = "test_agent";
            when(agentChatPort.agentExists(eq(tenantId), eq(agentCode))).thenReturn(true);
            // DC.3a: chokepoint now invokes the 4-arg overload (overrides arg).
            when(agentChatPort.runAgentTurn(any(), any(), any(),
                    org.mockito.ArgumentMatchers.<com.auraboot.framework.agent.port.AgentTurnOverrides>any()))
                    .thenReturn(new TurnOutcome.Success("delegated-ok", java.util.Map.of()));

            TurnOutcome outcome = turnService.runTurn(buildTurnRequest(agentCode, "hi agent"), sink);

            assertThat(outcome).isInstanceOf(TurnOutcome.Success.class);
            assertThat(((TurnOutcome.Success) outcome).finalResponse()).isEqualTo("delegated-ok");

            verify(agentChatPort, times(1)).agentExists(eq(tenantId), eq(agentCode));
            verify(agentChatPort, times(1)).runAgentTurn(
                    argThat(ctx -> ctx.tenantId() == tenantId),
                    argThat(req -> agentCode.equals(req.getAgentCode())
                            && "hi agent".equals(req.getMessage())),
                    any(),
                    org.mockito.ArgumentMatchers.<com.auraboot.framework.agent.port.AgentTurnOverrides>any());
            verify(chatService, never()).executeAuraBotTurn(any(), any(), any());
        });
    }

    @Test
    @Order(8)
    @DisplayName("chat impl throws -> runTurn translates to Failed and terminates sink")
    void chatImplThrows_translatesToFailed() {
        withTestIdentity(() -> {
            when(chatService.executeAuraBotTurn(any(), any(), any()))
                    .thenThrow(new RuntimeException("boom inside executeAuraBotTurn"));

            TurnOutcome outcome = turnService.runTurn(buildTurnRequest("aurabot", "hi"), sink);

            assertThat(outcome).isInstanceOf(TurnOutcome.Failed.class);
            assertThat(((TurnOutcome.Failed) outcome).errorMessage())
                    .contains("boom inside executeAuraBotTurn");
            verify(sink, atLeastOnce()).onError(contains("boom inside executeAuraBotTurn"), any());
        });
    }

    @Test
    @Order(10)
    @DisplayName("GAP-295: TurnContext.channelSessionId resolved on aurabot dispatch")
    void channelSessionId_resolvedOnAurabotDispatch() {
        withTestIdentity(() -> {
            org.mockito.ArgumentCaptor<TurnContext> ctxCaptor =
                    org.mockito.ArgumentCaptor.forClass(TurnContext.class);
            when(chatService.executeAuraBotTurn(ctxCaptor.capture(), any(), any()))
                    .thenReturn(new TurnOutcome.Success("ok", java.util.Map.of()));

            turnService.runTurn(buildTurnRequest("aurabot", "hi gap-295"), sink);

            TurnContext ctx = ctxCaptor.getValue();
            assertThat(ctx.channel())
                    .as("TurnContext should preserve request.channel for downstream policy gates")
                    .isEqualTo("web");
            assertThat(ctx.channelSessionId())
                    .as("ChannelSessionResolver should populate channelSessionId for channel=web")
                    .isNotNull();
        });
    }

    @Test
    @Order(11)
    @DisplayName("GAP-295: TurnContext.channelSessionId resolved on named-agent dispatch")
    void channelSessionId_resolvedOnNamedAgentDispatch() {
        withTestIdentity(() -> {
            Long tenantId = getTestTenant().getId();
            String agentCode = "test_agent";
            when(agentChatPort.agentExists(eq(tenantId), eq(agentCode))).thenReturn(true);
            org.mockito.ArgumentCaptor<TurnContext> ctxCaptor =
                    org.mockito.ArgumentCaptor.forClass(TurnContext.class);
            when(agentChatPort.runAgentTurn(ctxCaptor.capture(), any(), any(),
                    org.mockito.ArgumentMatchers.<com.auraboot.framework.agent.port.AgentTurnOverrides>any()))
                    .thenReturn(new TurnOutcome.Success("ok", java.util.Map.of()));

            turnService.runTurn(buildTurnRequest(agentCode, "hi agent gap-295"), sink);

            TurnContext ctx = ctxCaptor.getValue();
            assertThat(ctx.channel())
                    .as("TurnContext should preserve request.channel for named-agent policy gates")
                    .isEqualTo("web");
            assertThat(ctx.channelSessionId())
                    .as("ChannelSessionResolver should populate channelSessionId for named-agent path")
                    .isNotNull();
        });
    }

    @Test
    @Order(12)
    @DisplayName("TurnContext.profileId resolves from ab_agent_user_profile")
    void profileId_resolvedFromAgentUserProfile() {
        withTestIdentity(() -> {
            Long tenantId = getTestTenant().getId();
            Long userId = getTestUser().getId();
            String profilePid = com.auraboot.framework.common.util.UniqueIdGenerator.generate();
            jdbcTemplate.update("""
                    INSERT INTO ab_agent_user_profile
                      (pid, tenant_id, user_id, created_at, updated_at, deleted_flag)
                    VALUES (?, ?, ?, NOW(), NOW(), FALSE)
                    ON CONFLICT ON CONSTRAINT uq_ab_agent_user_profile_user DO UPDATE SET
                      pid = EXCLUDED.pid,
                      updated_at = NOW(),
                      deleted_flag = FALSE
                    """, profilePid, tenantId, userId);
            org.mockito.ArgumentCaptor<TurnContext> ctxCaptor =
                    org.mockito.ArgumentCaptor.forClass(TurnContext.class);
            when(chatService.executeAuraBotTurn(ctxCaptor.capture(), any(), any()))
                    .thenReturn(new TurnOutcome.Success("ok", java.util.Map.of()));

            turnService.runTurn(buildTurnRequest("aurabot", "hi profile"), sink);

            assertThat(ctxCaptor.getValue().profileId())
                    .as("TurnContext should carry the real ab_agent_user_profile pid")
                    .isEqualTo(profilePid);
        });
    }

    @Test
    @Order(13)
    @DisplayName("named-agent existence lookup failure is surfaced through sink")
    void namedAgentExistenceLookupFailure_surfacesThroughSink() {
        withTestIdentity(() -> {
            Long tenantId = getTestTenant().getId();
            String agentCode = "test_agent";
            when(agentChatPort.agentExists(eq(tenantId), eq(agentCode)))
                    .thenThrow(new IllegalStateException(
                            "Agent definition lookup failed for agentCode=" + agentCode));

            TurnOutcome outcome = turnService.runTurn(buildTurnRequest(agentCode, "hi agent"), sink);

            assertThat(outcome).isInstanceOf(TurnOutcome.Failed.class);
            assertThat(((TurnOutcome.Failed) outcome).errorMessage())
                    .contains("Agent definition lookup failed")
                    .contains(agentCode);
            verify(sink, atLeastOnce()).onError(contains("Agent definition lookup failed"), any());
            verify(agentChatPort, never()).runAgentTurn(any(), any(), any(),
                    org.mockito.ArgumentMatchers.<com.auraboot.framework.agent.port.AgentTurnOverrides>any());
        });
    }
}
