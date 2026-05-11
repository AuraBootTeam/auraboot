package com.auraboot.framework.conversation;

import com.auraboot.framework.agent.port.AgentChatPort;
import com.auraboot.framework.agent.service.AgentApprovalGateService;
import com.auraboot.framework.agent.service.AgentRunService;
import com.auraboot.framework.agent.service.RunOutcome;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase C.3d (Q-C3.3=α) integration tests for
 * {@link ConversationTurnServiceImpl#resumeTurn} dispatching to the ACP
 * approval-gate path. Verifies the dual-path resume contract:
 *
 * <ol>
 *     <li>{@code pendingTurnId} found in {@link ChatSessionStore} → goes to
 *         legacy chat-tool resume (covered by {@code ConversationTurnServiceImplResumeTest}).</li>
 *     <li>{@code pendingTurnId} found in {@code ab_agent_approval} (via
 *         {@link AgentApprovalGateService#findApproval}) → drives
 *         approve/reject through the gate and sync-resumes the run via
 *         {@link AgentRunService#executeTaskSync}.</li>
 *     <li>Neither lookup succeeds → Failed.</li>
 * </ol>
 *
 * <p>Tests in this class focus exclusively on path #2.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@Transactional
@Rollback(true)
@DisplayName("ConversationTurnServiceImpl.resumeTurn — Phase C.3d ACP approval path")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConversationTurnServiceImplAcpResumeTest extends BaseIntegrationTest {

    @Autowired
    private ConversationTurnService turnService;

    @MockitoBean
    private AuraBotChatService chatService;

    @MockitoBean
    private ChatSessionStore chatSessionStore;

    @MockitoBean
    private AgentApprovalGateService agentApprovalGateService;

    @MockitoBean
    private AgentRunService agentRunService;

    @MockitoBean
    private AgentChatPort agentChatPort;

    private ResponseSink sink;

    @BeforeEach
    void setUp() {
        sink = mock(ResponseSink.class);
        when(sink.isClientConnected()).thenReturn(true);
        // ChatSessionStore returns null for every lookup so the dispatcher
        // falls through to the approval path (path #2 above).
        when(chatSessionStore.consumePending(anyString())).thenReturn(null);
        when(agentChatPort.executeApprovedPendingTool(anyLong(), anyString()))
                .thenReturn(Map.of("handled", false));
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
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

    private Map<String, Object> pendingApprovalRow(String approvalPid, String runPid, String taskPid) {
        Map<String, Object> approval = new HashMap<>();
        approval.put("pid", approvalPid);
        approval.put("run_id", runPid);
        approval.put("task_id", taskPid);
        approval.put("approval_status", "pending");
        return approval;
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Test
    @DisplayName("APPROVED -> approvalGate.approve(triggerAutoResume=false) + sync executeTaskSync resume -> Success")
    void approved_drivesApproveAndSyncResume() {
        withTestIdentity(() -> {
            Long tenantId = getTestTenant().getId();
            String approvalPid = "APPROVAL_PID_OK";
            String runPid = "RUN_PID_OK";
            String taskPid = "TASK_PID_OK";

            when(agentApprovalGateService.findApproval(eq(tenantId), eq(approvalPid)))
                    .thenReturn(pendingApprovalRow(approvalPid, runPid, taskPid));
            when(agentApprovalGateService.approve(
                    eq(tenantId), eq(approvalPid), anyLong(), eq(false)))
                    .thenReturn(Map.of("pid", approvalPid, "approval_status", "approved"));
            when(agentRunService.executeTaskSync(
                    eq(tenantId), eq(taskPid), eq("aurabot"), eq(runPid)))
                    .thenReturn(new RunOutcome.Success(runPid, "Action completed.", 50, 12, 0.001d));

            TurnOutcome outcome = turnService.resumeTurn(approvalPid, ConversationTurnService.ConfirmDecision.APPROVED, sink);

            assertThat(outcome).isInstanceOf(TurnOutcome.Success.class);
            assertThat(((TurnOutcome.Success) outcome).finalResponse()).isEqualTo("Action completed.");

            // gate.approve called with triggerAutoResume=false (chokepoint drives the resume itself)
            verify(agentApprovalGateService, times(1))
                    .approve(eq(tenantId), eq(approvalPid), anyLong(), eq(false));
            // executeTaskSync called with resumeFromRunPid=runPid
            verify(agentRunService, times(1))
                    .executeTaskSync(eq(tenantId), eq(taskPid), eq("aurabot"), eq(runPid));
            verify(sink, times(1)).onDone(eq("Action completed."), any());
            // legacy chat path NEVER triggered
            verify(chatService, never()).resumeApprovedTurnFromPending(any(), any(), any());
        });
    }

    @Test
    @DisplayName("APPROVED chat approval payload -> approve gate + executeApprovedPendingTool; ACP run resume skipped")
    void approved_chatApprovalPayloadExecutesPendingToolInsteadOfAcpResume() {
        withTestIdentity(() -> {
            Long tenantId = getTestTenant().getId();
            String approvalPid = "APPROVAL_PID_CHAT";
            String runPid = "TURN_PID_CHAT";
            String taskPid = "TASK_PID_CHAT";

            when(agentApprovalGateService.findApproval(eq(tenantId), eq(approvalPid)))
                    .thenReturn(pendingApprovalRow(approvalPid, runPid, taskPid));
            when(agentApprovalGateService.approve(
                    eq(tenantId), eq(approvalPid), anyLong(), eq(false)))
                    .thenReturn(Map.of("pid", approvalPid, "approval_status", "approved"));
            when(agentChatPort.executeApprovedPendingTool(eq(tenantId), eq(approvalPid)))
                    .thenReturn(Map.of(
                            "handled", true,
                            "success", true,
                            "toolName", "platform.create_model",
                            "result", Map.of("success", true, "data", Map.of("pid", "model-1"))));

            TurnOutcome outcome = turnService.resumeTurn(approvalPid, ConversationTurnService.ConfirmDecision.APPROVED, sink);

            assertThat(outcome).isInstanceOf(TurnOutcome.Success.class);
            TurnOutcome.Success success = (TurnOutcome.Success) outcome;
            assertThat(success.finalResponse()).contains("Approved tool executed");
            assertThat(success.meta()).containsEntry("approvalPid", approvalPid);
            assertThat(success.meta()).containsEntry("toolName", "platform.create_model");
            verify(agentApprovalGateService, times(1))
                    .approve(eq(tenantId), eq(approvalPid), anyLong(), eq(false));
            verify(agentChatPort, times(1))
                    .executeApprovedPendingTool(eq(tenantId), eq(approvalPid));
            verify(agentRunService, never())
                    .executeTaskSync(anyLong(), anyString(), anyString(), anyString());
            verify(sink, times(1)).onDone(contains("Approved tool executed"), any());
        });
    }

    @Test
    @DisplayName("DENIED -> approvalGate.reject + Interrupted(user_denied); executeTaskSync NOT called")
    void denied_drivesReject() {
        withTestIdentity(() -> {
            Long tenantId = getTestTenant().getId();
            String approvalPid = "APPROVAL_PID_DENY";

            when(agentApprovalGateService.findApproval(eq(tenantId), eq(approvalPid)))
                    .thenReturn(pendingApprovalRow(approvalPid, "RUN_PID_X", "TASK_PID_X"));

            TurnOutcome outcome = turnService.resumeTurn(approvalPid, ConversationTurnService.ConfirmDecision.DENIED, sink);

            assertThat(outcome).isInstanceOf(TurnOutcome.Interrupted.class);
            assertThat(((TurnOutcome.Interrupted) outcome).reason()).isEqualTo("user_denied");
            verify(agentApprovalGateService, times(1))
                    .reject(eq(tenantId), eq(approvalPid), anyLong(), anyString());
            verify(agentApprovalGateService, never())
                    .approve(anyLong(), anyString(), anyLong(), anyBoolean());
            verify(agentRunService, never())
                    .executeTaskSync(anyLong(), anyString(), anyString(), anyString());
        });
    }

    @Test
    @DisplayName("CANCELLED -> approvalGate.reject + Interrupted(user_cancelled)")
    void cancelled_drivesReject() {
        withTestIdentity(() -> {
            Long tenantId = getTestTenant().getId();
            String approvalPid = "APPROVAL_PID_CANCEL";

            when(agentApprovalGateService.findApproval(eq(tenantId), eq(approvalPid)))
                    .thenReturn(pendingApprovalRow(approvalPid, "RUN_PID_Y", "TASK_PID_Y"));

            TurnOutcome outcome = turnService.resumeTurn(approvalPid, ConversationTurnService.ConfirmDecision.CANCELLED, sink);

            assertThat(outcome).isInstanceOf(TurnOutcome.Interrupted.class);
            assertThat(((TurnOutcome.Interrupted) outcome).reason()).isEqualTo("user_cancelled");
            verify(agentApprovalGateService, times(1))
                    .reject(eq(tenantId), eq(approvalPid), anyLong(), anyString());
        });
    }

    @Test
    @DisplayName("approval row already non-pending -> Failed (no-op)")
    void terminalApproval_returnsFailed() {
        withTestIdentity(() -> {
            Long tenantId = getTestTenant().getId();
            String approvalPid = "APPROVAL_PID_TERMINAL";
            Map<String, Object> approval = pendingApprovalRow(approvalPid, "RUN_PID_T", "TASK_PID_T");
            approval.put("approval_status", "approved");
            when(agentApprovalGateService.findApproval(eq(tenantId), eq(approvalPid)))
                    .thenReturn(approval);

            TurnOutcome outcome = turnService.resumeTurn(approvalPid, ConversationTurnService.ConfirmDecision.APPROVED, sink);

            assertThat(outcome).isInstanceOf(TurnOutcome.Failed.class);
            assertThat(((TurnOutcome.Failed) outcome).errorMessage()).contains("no longer pending");
            verify(agentApprovalGateService, never())
                    .approve(anyLong(), anyString(), anyLong(), anyBoolean());
        });
    }

    @Test
    @DisplayName("token in neither store nor approvals -> Failed with helpful message")
    void unknownToken_returnsFailed() {
        withTestIdentity(() -> {
            Long tenantId = getTestTenant().getId();
            when(agentApprovalGateService.findApproval(eq(tenantId), anyString())).thenReturn(null);

            TurnOutcome outcome = turnService.resumeTurn("UNKNOWN_TOKEN", ConversationTurnService.ConfirmDecision.APPROVED, sink);

            assertThat(outcome).isInstanceOf(TurnOutcome.Failed.class);
            assertThat(((TurnOutcome.Failed) outcome).errorMessage())
                    .contains("No pending tool or approval found")
                    .contains("UNKNOWN_TOKEN");
            verify(sink, atLeastOnce()).onError(anyString(), any());
        });
    }

    private static boolean anyBoolean() {
        return org.mockito.ArgumentMatchers.anyBoolean();
    }
}
