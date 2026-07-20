package com.auraboot.framework.conversation;

import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.identity.ChannelSessionResolver;
import com.auraboot.framework.agent.runtime.ChatMessageTapeStore;
import com.auraboot.framework.agent.runtime.ContextConflictPolicy;
import com.auraboot.framework.agent.runtime.PendingContinuationService;
import com.auraboot.framework.agent.runtime.PendingContextFreshnessDecision;
import com.auraboot.framework.agent.runtime.PendingContextFreshnessValidator;
import com.auraboot.framework.agent.runtime.PendingToolSnapshot;
import com.auraboot.framework.agent.runtime.PendingToolStore;
import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
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

import java.util.List;
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
 *     <li>APPROVED + valid pending      -> PendingContinuationService called;
 *                                         finalizeTurn fires for the returned outcome</li>
 *     <li>DENIED                         -> Interrupted outcome (reason=user_denied);
 *                                         continuation service never called</li>
 *     <li>CANCELLED                      -> Interrupted outcome (reason=user_cancelled);
 *                                         continuation service never called</li>
 *     <li>pendingTurnId not in store     -> Failed; sink.onError fires</li>
 *     <li>Tenant/user mismatch on resume -> Failed; continuation service never called</li>
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
    private PendingContinuationService pendingContinuationService;

    @MockitoBean(extraInterfaces = ChatMessageTapeStore.class)
    private PendingToolStore pendingToolStore;

    @MockitoBean
    private PendingContextFreshnessValidator pendingContextFreshnessValidator;

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

    private PendingToolSnapshot buildPending(String turnId, String toolId,
                                                       Long ownerTenantId, Long ownerUserId) {
        return PendingToolSnapshot.builder()
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
    @DisplayName("APPROVED + valid pending -> PendingContinuationService called; outcome propagated")
    void approved_dispatchesToPendingContinuationService() {
        Long tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();
        String pendingTurnId = "01HW3KAPPROVED";
        PendingToolSnapshot pending = buildPending(pendingTurnId, "tool-a", tenantId, userId);
        when(pendingToolStore.consumePendingForOwner(eq(pendingTurnId), eq(tenantId), eq(userId)))
                .thenReturn(pending);
        TurnOutcome.Success expected = new TurnOutcome.Success("done", Map.of());
        when(pendingContinuationService.resumeApprovedChatTool(any(), same(pending), any()))
                .thenReturn(expected);

        TurnOutcome outcome = turnService.resumeTurn(
                pendingTurnId, ConversationTurnService.ConfirmDecision.APPROVED, sink);

        assertThat(outcome).isSameAs(expected);
        verify(pendingContinuationService, times(1)).resumeApprovedChatTool(any(), same(pending), any());
    }

    @Test
    @DisplayName("F1: resumed TurnContext restores the original triage bucket; stale values degrade to null")
    void approved_restoresTriageBucketFromSnapshot() {
        Long tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();

        PendingToolSnapshot pending = buildPending("01HW3KF1BUCKET", "tool-f1", tenantId, userId);
        pending.setTriageBucket("SYNC_ACTION");
        when(pendingToolStore.consumePendingForOwner(eq("01HW3KF1BUCKET"), eq(tenantId), eq(userId)))
                .thenReturn(pending);
        when(pendingContinuationService.resumeApprovedChatTool(any(), same(pending), any()))
                .thenReturn(new TurnOutcome.Success("done", Map.of()));

        turnService.resumeTurn("01HW3KF1BUCKET", ConversationTurnService.ConfirmDecision.APPROVED, sink);

        // The resumed turn's terminal observation/memory keep SYNC_ACTION
        // semantics only if the rebuilt ctx carries the original bucket.
        verify(pendingContinuationService).resumeApprovedChatTool(
                argThat(ctx -> ctx.triageBucket() == com.auraboot.framework.agent.triage.TriageBucket.SYNC_ACTION),
                same(pending), any());
    }

    @Test
    @DisplayName("F1: stale / unknown snapshot bucket degrades to null, never fails the resume")
    void approved_staleBucketDegradesToNull() {
        // Separate @Test on purpose: each resume's finalize touches the real
        // persistence layer; two resumes in one transactional test poison the
        // tx on the known shared-DB drift and the second dies with 25P02.
        Long tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();
        PendingToolSnapshot stale = buildPending("01HW3KF1STALE", "tool-f1b", tenantId, userId);
        stale.setTriageBucket("NOT_A_BUCKET");
        when(pendingToolStore.consumePendingForOwner(eq("01HW3KF1STALE"), eq(tenantId), eq(userId)))
                .thenReturn(stale);
        when(pendingContinuationService.resumeApprovedChatTool(any(), same(stale), any()))
                .thenReturn(new TurnOutcome.Success("done", Map.of()));

        turnService.resumeTurn("01HW3KF1STALE", ConversationTurnService.ConfirmDecision.APPROVED, sink);

        verify(pendingContinuationService).resumeApprovedChatTool(
                argThat(ctx -> "01HW3KF1STALE".equals(ctx.turnId()) && ctx.triageBucket() == null),
                same(stale), any());
    }

    @Test
    @DisplayName("APPROVED + expired pending -> Failed; continuation service never called")
    void approvedExpiredPendingFailsBeforeContinuation() {
        Long tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();
        String pendingTurnId = "01HW3KEXPIRED";
        PendingToolSnapshot pending = buildPending(pendingTurnId, "tool-expired", tenantId, userId);
        pending.setExpiresAt(System.currentTimeMillis() - 1);
        when(pendingToolStore.consumePendingForOwner(eq(pendingTurnId), eq(tenantId), eq(userId)))
                .thenReturn(pending);

        TurnOutcome outcome = turnService.resumeTurn(
                pendingTurnId, ConversationTurnService.ConfirmDecision.APPROVED, sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Failed.class);
        assertThat(((TurnOutcome.Failed) outcome).errorMessage()).contains("expired");
        verify(sink).onError(contains("expired"), eq(null));
        verify(pendingContinuationService, never()).resumeApprovedChatTool(any(), any(), any());
    }

    @Test
    @DisplayName("APPROVED + args hash mismatch -> Failed; continuation service never called")
    void approvedArgsHashMismatchFailsBeforeContinuation() {
        Long tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();
        String pendingTurnId = "01HW3KARGHASH";
        PendingToolSnapshot pending = buildPending(pendingTurnId, "tool-args", tenantId, userId);
        pending.setArgsHash("stale-args-hash");
        when(pendingToolStore.consumePendingForOwner(eq(pendingTurnId), eq(tenantId), eq(userId)))
                .thenReturn(pending);

        TurnOutcome outcome = turnService.resumeTurn(
                pendingTurnId, ConversationTurnService.ConfirmDecision.APPROVED, sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Failed.class);
        assertThat(((TurnOutcome.Failed) outcome).errorMessage()).contains("args hash mismatch");
        verify(sink).onError(contains("args hash mismatch"), eq(null));
        verify(pendingContinuationService, never()).resumeApprovedChatTool(any(), any(), any());
    }

    @Test
    @DisplayName("APPROVED + tool schema hash mismatch -> Failed; continuation service never called")
    void approvedToolSchemaHashMismatchFailsBeforeContinuation() {
        Long tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();
        String pendingTurnId = "01HW3KSCHEMA";
        PendingToolSnapshot pending = buildPending(pendingTurnId, "tool-schema", tenantId, userId);
        pending.setToolSchemaHash("stale-schema-hash");
        pending.setAgentToolDefinitions(List.of(AgentToolDefinition.builder()
                .name("cmd_test")
                .inputSchema(Map.of("type", "object", "required", List.of("foo")))
                .build()));
        when(pendingToolStore.consumePendingForOwner(eq(pendingTurnId), eq(tenantId), eq(userId)))
                .thenReturn(pending);

        TurnOutcome outcome = turnService.resumeTurn(
                pendingTurnId, ConversationTurnService.ConfirmDecision.APPROVED, sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Failed.class);
        assertThat(((TurnOutcome.Failed) outcome).errorMessage()).contains("tool schema hash mismatch");
        verify(sink).onError(contains("tool schema hash mismatch"), eq(null));
        verify(pendingContinuationService, never()).resumeApprovedChatTool(any(), any(), any());
    }

    @Test
    @DisplayName("APPROVED + preview hash mismatch -> Failed; continuation service never called")
    void approvedPreviewHashMismatchFailsBeforeContinuation() {
        Long tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();
        String pendingTurnId = "01HW3KPREVIEW";
        PendingToolSnapshot pending = buildPending(pendingTurnId, "tool-preview", tenantId, userId);
        pending.setPreview("Preview shown to user");
        pending.setPreviewHash("stale-preview-hash");
        when(pendingToolStore.consumePendingForOwner(eq(pendingTurnId), eq(tenantId), eq(userId)))
                .thenReturn(pending);

        TurnOutcome outcome = turnService.resumeTurn(
                pendingTurnId, ConversationTurnService.ConfirmDecision.APPROVED, sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Failed.class);
        assertThat(((TurnOutcome.Failed) outcome).errorMessage()).contains("preview hash mismatch");
        verify(sink).onError(contains("preview hash mismatch"), eq(null));
        verify(pendingContinuationService, never()).resumeApprovedChatTool(any(), any(), any());
    }

    @Test
    @DisplayName("APPROVED + stale context reject policy -> Failed; continuation service never called")
    void approvedStaleContextRejectsBeforeContinuation() {
        Long tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();
        String pendingTurnId = "01HW3KSTALECTX";
        PendingToolSnapshot pending = buildPending(pendingTurnId, "tool-stale-context", tenantId, userId);
        pending.setContextVersion("customer:C-1:v1");
        pending.setRecordVersion("v1");
        pending.setContextConflictPolicy(ContextConflictPolicy.REJECT_AND_REPLAN.name());
        when(pendingToolStore.consumePendingForOwner(eq(pendingTurnId), eq(tenantId), eq(userId)))
                .thenReturn(pending);
        when(pendingContextFreshnessValidator.validate(same(pending)))
                .thenReturn(PendingContextFreshnessDecision.stale(
                        ContextConflictPolicy.REJECT_AND_REPLAN,
                        "record_version_changed",
                        "Customer C-1 changed after preview"));

        TurnOutcome outcome = turnService.resumeTurn(
                pendingTurnId, ConversationTurnService.ConfirmDecision.APPROVED, sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Failed.class);
        assertThat(((TurnOutcome.Failed) outcome).errorMessage()).contains("context freshness");
        verify(sink).onError(contains("context freshness"), eq(null));
        verify(pendingContinuationService, never()).resumeApprovedChatTool(any(), any(), any());
    }

    @Test
    @DisplayName("DENIED -> Interrupted outcome (user_denied); continuation service never called")
    void denied_returnsInterruptedWithoutChatImpl() {
        Long tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();
        String pendingTurnId = "01HW3KDENIED";
        when(pendingToolStore.consumePendingForOwner(eq(pendingTurnId), eq(tenantId), eq(userId)))
                .thenReturn(buildPending(pendingTurnId, "tool-d", tenantId, userId));

        TurnOutcome outcome = turnService.resumeTurn(
                pendingTurnId, ConversationTurnService.ConfirmDecision.DENIED, sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Interrupted.class);
        TurnOutcome.Interrupted i = (TurnOutcome.Interrupted) outcome;
        assertThat(i.reason()).isEqualTo("user_denied");
        verify(pendingContinuationService, never()).resumeApprovedChatTool(any(), any(), any());
        verify(sink, atLeastOnce()).onDone(any(), any());
    }

    @Test
    @DisplayName("CANCELLED -> Interrupted outcome (user_cancelled); continuation service never called")
    void cancelled_returnsInterruptedWithoutChatImpl() {
        Long tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();
        String pendingTurnId = "01HW3KCANCELLED";
        when(pendingToolStore.consumePendingForOwner(eq(pendingTurnId), eq(tenantId), eq(userId)))
                .thenReturn(buildPending(pendingTurnId, "tool-c", tenantId, userId));

        TurnOutcome outcome = turnService.resumeTurn(
                pendingTurnId, ConversationTurnService.ConfirmDecision.CANCELLED, sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Interrupted.class);
        assertThat(((TurnOutcome.Interrupted) outcome).reason()).isEqualTo("user_cancelled");
        verify(pendingContinuationService, never()).resumeApprovedChatTool(any(), any(), any());
    }

    @Test
    @DisplayName("pendingTurnId not in store -> Failed + sink.onError")
    void pendingMissing_returnsFailed() {
        when(pendingToolStore.consumePendingForOwner(eq("missing"), eq(getTestTenant().getId()), eq(getTestUser().getId())))
                .thenReturn(null);

        TurnOutcome outcome = turnService.resumeTurn(
                "missing", ConversationTurnService.ConfirmDecision.APPROVED, sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Failed.class);
        // Phase C.3d: error message broadened to cover the dual-path lookup
        // (chat-store + ab_agent_approval).
        assertThat(((TurnOutcome.Failed) outcome).errorMessage())
                .contains("No pending tool or approval found");
        verify(sink, atLeastOnce()).onError(contains("No pending"), any());
        verify(pendingContinuationService, never()).resumeApprovedChatTool(any(), any(), any());
    }

    @Test
    @DisplayName("Tenant/user mismatch -> Failed; continuation service never called")
    void identityMismatch_returnsFailed() {
        Long tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();
        String pendingTurnId = "01HW3KMISMATCH";
        when(pendingToolStore.consumePendingForOwner(eq(pendingTurnId), eq(tenantId), eq(userId)))
                .thenReturn(null);

        TurnOutcome outcome = turnService.resumeTurn(
                pendingTurnId, ConversationTurnService.ConfirmDecision.APPROVED, sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Failed.class);
        assertThat(((TurnOutcome.Failed) outcome).errorMessage()).contains("No pending tool or approval found");
        verify(pendingContinuationService, never()).resumeApprovedChatTool(any(), any(), any());
    }

    @Test
    @DisplayName("identity mismatch does not consume pending payload through legacy consumePending")
    void identityMismatch_doesNotConsumePendingPayload() {
        Long tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();
        String pendingTurnId = "01HW3KMISMATCHSAFE";
        when(pendingToolStore.consumePendingForOwner(eq(pendingTurnId), eq(tenantId), eq(userId)))
                .thenReturn(null);

        TurnOutcome outcome = turnService.resumeTurn(
                pendingTurnId, ConversationTurnService.ConfirmDecision.APPROVED, sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Failed.class);
        assertThat(((TurnOutcome.Failed) outcome).errorMessage())
                .contains("No pending tool or approval found");
        verify(pendingContinuationService, never()).resumeApprovedChatTool(any(), any(), any());
    }

    @Test
    @DisplayName("null pendingTurnId -> Failed (defensive)")
    void nullPendingTurnId_returnsFailed() {
        TurnOutcome outcome = turnService.resumeTurn(
                null, ConversationTurnService.ConfirmDecision.APPROVED, sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Failed.class);
        assertThat(((TurnOutcome.Failed) outcome).errorMessage()).contains("pendingTurnId");
        verify(pendingToolStore, never()).consumePendingForOwner(any(), any(), any());
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
        PendingToolSnapshot pending = PendingToolSnapshot.builder()
                .turnId(pendingTurnId)
                .tenantId(tenantId)
                .userId(userId)
                .humanMemberId(getTestTenantMember().getId())
                .conversationId(1L)
                .agentCode("aurabot")
                .channel("web")
                .profileId("profile-resume")
                .channelSessionPid(seeded.pid())
                .toolId("tool-gap295")
                .toolName("cmd_test")
                .input(Map.of())
                .description("gap295")
                .build();
        when(pendingToolStore.consumePendingForOwner(eq(pendingTurnId), eq(tenantId), eq(userId)))
                .thenReturn(pending);

        // 3. Capture the TurnContext that PendingContinuationService sees
        org.mockito.ArgumentCaptor<TurnContext> ctxCaptor =
                org.mockito.ArgumentCaptor.forClass(TurnContext.class);
        when(pendingContinuationService.resumeApprovedChatTool(ctxCaptor.capture(), same(pending), any()))
                .thenReturn(new TurnOutcome.Success("ok", Map.of()));

        // 4. Resume
        turnService.resumeTurn(
                pendingTurnId, ConversationTurnService.ConfirmDecision.APPROVED, sink);

        // 5. Assert ctx.channelSessionId() matches the seeded pid
        TurnContext ctx = ctxCaptor.getValue();
        assertThat(ctx.channel())
                .as("rebuildContext should preserve the pending channel for downstream policy gates")
                .isEqualTo("web");
        assertThat(ctx.profileId())
                .as("rebuildContext should preserve the pending profile id for downstream policy gates")
                .isEqualTo("profile-resume");
        assertThat(ctx.channelSessionId())
                .as("rebuildContext should re-attach channel session via findByPid")
                .isEqualTo(seeded.pid());
    }

    @Test
    @DisplayName("named-agent pending resume preserves taskPid for finalization")
    void resume_preservesTaskPidFromPendingSnapshot() {
        Long tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();
        String pendingTurnId = "01HW3KTASKPID";
        PendingToolSnapshot pending = PendingToolSnapshot.builder()
                .turnId(pendingTurnId)
                .tenantId(tenantId)
                .userId(userId)
                .humanMemberId(getTestTenantMember().getId())
                .conversationId(1L)
                .agentCode("pcba_procurement_comparison_agent")
                .taskPid("task-resume-1")
                .toolId("tool-taskpid")
                .toolName("cmd_test")
                .input(Map.of())
                .description("task pid resume")
                .build();
        when(pendingToolStore.consumePendingForOwner(eq(pendingTurnId), eq(tenantId), eq(userId)))
                .thenReturn(pending);

        org.mockito.ArgumentCaptor<TurnContext> ctxCaptor =
                org.mockito.ArgumentCaptor.forClass(TurnContext.class);
        when(pendingContinuationService.resumeApprovedChatTool(ctxCaptor.capture(), same(pending), any()))
                .thenReturn(new TurnOutcome.Success("ok", Map.of()));

        turnService.resumeTurn(
                pendingTurnId, ConversationTurnService.ConfirmDecision.APPROVED, sink);

        assertThat(ctxCaptor.getValue().taskPid())
                .as("resume finalization must be able to close the original named-agent task")
                .isEqualTo("task-resume-1");
    }

    @Test
    @DisplayName("GAP-295: pending without channelSessionPid (legacy) → ctx.channelSessionId stays null")
    void resume_nullChannelSessionPid_keepsCtxNull() {
        Long tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();
        String pendingTurnId = "01HW3KGAP295LEGACY";
        PendingToolSnapshot pending = buildPending(pendingTurnId, "tool-l", tenantId, userId);
        // channelSessionPid intentionally not set on the pending payload
        assertThat(pending.getChannelSessionPid()).isNull();
        when(pendingToolStore.consumePendingForOwner(eq(pendingTurnId), eq(tenantId), eq(userId)))
                .thenReturn(pending);

        org.mockito.ArgumentCaptor<TurnContext> ctxCaptor =
                org.mockito.ArgumentCaptor.forClass(TurnContext.class);
        when(pendingContinuationService.resumeApprovedChatTool(ctxCaptor.capture(), same(pending), any()))
                .thenReturn(new TurnOutcome.Success("ok", Map.of()));

        turnService.resumeTurn(
                pendingTurnId, ConversationTurnService.ConfirmDecision.APPROVED, sink);

        assertThat(ctxCaptor.getValue().channelSessionId())
                .as("legacy pending without pid → no re-attach")
                .isNull();
    }
}
