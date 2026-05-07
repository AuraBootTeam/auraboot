package com.auraboot.framework.conversation;

import com.auraboot.framework.agent.identity.ChannelSessionResolver;
import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.aurabot.service.AuraBotChatService;
import com.auraboot.framework.aurabot.service.ChatSessionStore;
import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.*;

/**
 * Phase B.6 dispatch tests for {@link ConversationTurnServiceImpl#resumeTurn}.
 * Covers the 5 design v3.3 §3.10 step 5 branches plus the pending-missing
 * and identity-mismatch edge cases:
 *
 * <ol>
 *     <li>APPROVED + valid pending      -> chatService.resumeApprovedTurnFromPending called;
 *                                         finalizeTurn fires for the returned outcome</li>
 *     <li>DENIED                         -> Interrupted outcome (reason=user_denied);
 *                                         chatService never called</li>
 *     <li>CANCELLED                      -> Interrupted outcome (reason=user_cancelled);
 *                                         chatService never called</li>
 *     <li>pendingTurnId not in store     -> Failed; sink.onError fires</li>
 *     <li>Tenant/user mismatch on resume -> Failed; chatService never called</li>
 *     <li>null pendingTurnId             -> Failed (defensive)</li>
 * </ol>
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@Transactional
@Rollback(true)
@DisplayName("ConversationTurnServiceImpl.resumeTurn — Phase B.6 dispatch")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConversationTurnServiceImplResumeTest extends BaseIntegrationTest {

    @Autowired
    private ConversationTurnService turnService;

    @MockitoBean
    private AuraBotChatService chatService;

    @MockitoBean
    private ChatSessionStore chatSessionStore;

    @Autowired
    private ChannelSessionResolver channelSessionResolver;

    private ResponseSink sink;

    @BeforeEach
    void setUpSink() {
        sink = mock(ResponseSink.class);
        when(sink.isClientConnected()).thenReturn(true);

        Long tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();
        String userPid = getTestUser().getPid();
        String username = getTestUser().getUserName();
        Long memberId = getTestTenantMember().getId();
        MetaContext.setContext(tenantId, userId, userPid, username);
        MetaContext.setMemberId(memberId);
    }

    @AfterEach
    void clearMeta() {
        MetaContext.clear();
    }

    private ChatSessionStore.PendingTool buildPending(String turnId, String toolId,
                                                       Long ownerTenantId, Long ownerUserId) {
        return ChatSessionStore.PendingTool.builder()
                .turnId(turnId)
                .tenantId(ownerTenantId)
                .userId(ownerUserId)
                .humanMemberId(getTestTenantMember().getId())
                .conversationId(1L)
                .agentCode("aurabot")
                .toolId(toolId)
                .toolName("cmd_test")
                .input(Map.of("foo", "bar"))
                .description("test pending")
                .build();
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Test
    @DisplayName("APPROVED + valid pending -> chatService.resumeApprovedTurnFromPending called; outcome propagated")
    void approved_dispatchesToChatService() {
        Long tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();
        String pendingTurnId = "01HW3KAPPROVED";
        ChatSessionStore.PendingTool pending = buildPending(pendingTurnId, "tool-a", tenantId, userId);
        when(chatSessionStore.consumePending(eq(pendingTurnId))).thenReturn(pending);
        TurnOutcome.Success expected = new TurnOutcome.Success("done", Map.of());
        when(chatService.resumeApprovedTurnFromPending(any(), same(pending), same(sink)))
                .thenReturn(expected);

        TurnOutcome outcome = turnService.resumeTurn(
                pendingTurnId, ConversationTurnService.ConfirmDecision.APPROVED, sink);

        assertThat(outcome).isSameAs(expected);
        verify(chatService, times(1)).resumeApprovedTurnFromPending(any(), same(pending), same(sink));
    }

    @Test
    @DisplayName("DENIED -> Interrupted outcome (user_denied); chatService never called")
    void denied_returnsInterruptedWithoutChatImpl() {
        Long tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();
        String pendingTurnId = "01HW3KDENIED";
        when(chatSessionStore.consumePending(eq(pendingTurnId)))
                .thenReturn(buildPending(pendingTurnId, "tool-d", tenantId, userId));

        TurnOutcome outcome = turnService.resumeTurn(
                pendingTurnId, ConversationTurnService.ConfirmDecision.DENIED, sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Interrupted.class);
        TurnOutcome.Interrupted i = (TurnOutcome.Interrupted) outcome;
        assertThat(i.reason()).isEqualTo("user_denied");
        verify(chatService, never()).resumeApprovedTurnFromPending(any(), any(), any());
        verify(sink, atLeastOnce()).onDone(any(), any());
    }

    @Test
    @DisplayName("CANCELLED -> Interrupted outcome (user_cancelled); chatService never called")
    void cancelled_returnsInterruptedWithoutChatImpl() {
        Long tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();
        String pendingTurnId = "01HW3KCANCELLED";
        when(chatSessionStore.consumePending(eq(pendingTurnId)))
                .thenReturn(buildPending(pendingTurnId, "tool-c", tenantId, userId));

        TurnOutcome outcome = turnService.resumeTurn(
                pendingTurnId, ConversationTurnService.ConfirmDecision.CANCELLED, sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Interrupted.class);
        assertThat(((TurnOutcome.Interrupted) outcome).reason()).isEqualTo("user_cancelled");
        verify(chatService, never()).resumeApprovedTurnFromPending(any(), any(), any());
    }

    @Test
    @DisplayName("pendingTurnId not in store -> Failed + sink.onError")
    void pendingMissing_returnsFailed() {
        when(chatSessionStore.consumePending(eq("missing"))).thenReturn(null);

        TurnOutcome outcome = turnService.resumeTurn(
                "missing", ConversationTurnService.ConfirmDecision.APPROVED, sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Failed.class);
        // Phase C.3d: error message broadened to cover the dual-path lookup
        // (chat-store + ab_agent_approval).
        assertThat(((TurnOutcome.Failed) outcome).errorMessage())
                .contains("No pending tool or approval found");
        verify(sink, atLeastOnce()).onError(contains("No pending"), any());
        verify(chatService, never()).resumeApprovedTurnFromPending(any(), any(), any());
    }

    @Test
    @DisplayName("Tenant/user mismatch -> Failed; chatService never called")
    void identityMismatch_returnsFailed() {
        Long ownerTenantId = getTestTenant().getId() + 999L; // different tenant
        Long ownerUserId = getTestUser().getId() + 999L;
        String pendingTurnId = "01HW3KMISMATCH";
        when(chatSessionStore.consumePending(eq(pendingTurnId)))
                .thenReturn(buildPending(pendingTurnId, "tool-m", ownerTenantId, ownerUserId));

        TurnOutcome outcome = turnService.resumeTurn(
                pendingTurnId, ConversationTurnService.ConfirmDecision.APPROVED, sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Failed.class);
        assertThat(((TurnOutcome.Failed) outcome).errorMessage()).containsAnyOf("tenant mismatch", "user mismatch");
        verify(chatService, never()).resumeApprovedTurnFromPending(any(), any(), any());
    }

    @Test
    @DisplayName("null pendingTurnId -> Failed (defensive)")
    void nullPendingTurnId_returnsFailed() {
        TurnOutcome outcome = turnService.resumeTurn(
                null, ConversationTurnService.ConfirmDecision.APPROVED, sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Failed.class);
        assertThat(((TurnOutcome.Failed) outcome).errorMessage()).contains("pendingTurnId");
        verify(chatSessionStore, never()).consumePending(any());
    }

    // =========================================================================
    // GAP-295 resume path — channelSessionId re-attach
    // =========================================================================

    @Test
    @DisplayName("GAP-295: pending.channelSessionPid → ctx.channelSessionId on resume APPROVED")
    void resume_reAttachesChannelSessionFromPendingPid() {
        Long tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();

        // 1. Seed a real ab_agent_channel_session_state row via the resolver
        ChannelSessionResolver.ChannelSession seeded = channelSessionResolver.resolve(
                new ChannelSessionResolver.ResolveRequest(
                        tenantId,
                        "web",
                        String.valueOf(userId),
                        /*profileId=*/ null,
                        userId,
                        /*createIfAbsent=*/ true));
        assertThat(seeded).isNotNull();
        assertThat(seeded.pid()).isNotNull();

        // 2. Build a PendingTool carrying that pid
        String pendingTurnId = "01HW3KGAP295";
        ChatSessionStore.PendingTool pending = ChatSessionStore.PendingTool.builder()
                .turnId(pendingTurnId)
                .tenantId(tenantId)
                .userId(userId)
                .humanMemberId(getTestTenantMember().getId())
                .conversationId(1L)
                .agentCode("aurabot")
                .channelSessionPid(seeded.pid())
                .toolId("tool-gap295")
                .toolName("cmd_test")
                .input(Map.of())
                .description("gap295")
                .build();
        when(chatSessionStore.consumePending(eq(pendingTurnId))).thenReturn(pending);

        // 3. Capture the TurnContext that resumeApprovedTurnFromPending sees
        org.mockito.ArgumentCaptor<TurnContext> ctxCaptor =
                org.mockito.ArgumentCaptor.forClass(TurnContext.class);
        when(chatService.resumeApprovedTurnFromPending(ctxCaptor.capture(), same(pending), same(sink)))
                .thenReturn(new TurnOutcome.Success("ok", Map.of()));

        // 4. Resume
        turnService.resumeTurn(
                pendingTurnId, ConversationTurnService.ConfirmDecision.APPROVED, sink);

        // 5. Assert ctx.channelSessionId() matches the seeded pid
        TurnContext ctx = ctxCaptor.getValue();
        assertThat(ctx.channelSessionId())
                .as("rebuildContext should re-attach channel session via findByPid")
                .isEqualTo(seeded.pid());
    }

    @Test
    @DisplayName("GAP-295: pending without channelSessionPid (legacy) → ctx.channelSessionId stays null")
    void resume_nullChannelSessionPid_keepsCtxNull() {
        Long tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();
        String pendingTurnId = "01HW3KGAP295LEGACY";
        ChatSessionStore.PendingTool pending = buildPending(pendingTurnId, "tool-l", tenantId, userId);
        // channelSessionPid intentionally not set on the pending payload
        assertThat(pending.getChannelSessionPid()).isNull();
        when(chatSessionStore.consumePending(eq(pendingTurnId))).thenReturn(pending);

        org.mockito.ArgumentCaptor<TurnContext> ctxCaptor =
                org.mockito.ArgumentCaptor.forClass(TurnContext.class);
        when(chatService.resumeApprovedTurnFromPending(ctxCaptor.capture(), same(pending), same(sink)))
                .thenReturn(new TurnOutcome.Success("ok", Map.of()));

        turnService.resumeTurn(
                pendingTurnId, ConversationTurnService.ConfirmDecision.APPROVED, sink);

        assertThat(ctxCaptor.getValue().channelSessionId())
                .as("legacy pending without pid → no re-attach")
                .isNull();
    }
}
