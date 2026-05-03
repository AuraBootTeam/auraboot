package com.auraboot.framework.conversation;

import com.auraboot.framework.agent.port.AgentChatPort;
import com.auraboot.framework.agent.service.AgentRunService;
import com.auraboot.framework.agent.service.RunOutcome;
import com.auraboot.framework.agent.triage.TriageBucket;
import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.aurabot.dto.ChatRequest;
import com.auraboot.framework.aurabot.service.AuraBotChatService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase C.3c integration tests for {@link ConversationTurnServiceImpl#runTurn}
 * dispatching to the ACP runtime when {@code triageBucket = ACP_RUN}.
 *
 * <p>Verifies the bucket-driven cutover (Q-C3.5=β step1) per design §8 row C.3c
 * acceptance: aurabot ACP_RUN turns end-to-end write {@code ab_agent_task} +
 * delegate to {@link AgentRunService#executeTaskSync}; non-ACP_RUN buckets
 * continue to flow through the legacy chat path with no behavior change.
 *
 * <p>Tests:
 * <ol>
 *     <li>ACP_RUN + RunOutcome.Success → TurnOutcome.Success carrying
 *         finalResponse + meta(runPid/tokens/cost); sink.onDone fired exactly
 *         once; chatService.executeAuraBotTurn never called; an
 *         {@code ab_agent_task} row exists with assignee_type='ai',
 *         assignee_id='aurabot'.</li>
 *     <li>ACP_RUN + RunOutcome.Failed → TurnOutcome.Failed; sink.onError
 *         fired with the run's error message.</li>
 *     <li>ACP_RUN + RunOutcome.PendingApproval → interim TurnOutcome.Failed
 *         with "Approval required" message; sink.onError fired (Q-C3.3=α
 *         full wire-up lives in C.3d).</li>
 *     <li>ACP_RUN + RunOutcome.Skipped → TurnOutcome.Failed with the skip
 *         reason; sink.onError fired.</li>
 *     <li>LIGHT_CHAT (precomputed) → still goes through
 *         chatService.executeAuraBotTurn; agentRunService never called
 *         (regression guard for non-ACP_RUN buckets).</li>
 * </ol>
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@Transactional
@Rollback(true)
@DisplayName("ConversationTurnServiceImpl.runTurn — Phase C.3c ACP_RUN bucket dispatch")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConversationTurnServiceImplAcpDispatchTest extends BaseIntegrationTest {

    @Autowired
    private ConversationTurnService turnService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Mocked so the LLM stack stays out of the dispatch test. */
    @MockitoBean
    private AuraBotChatService chatService;

    /** Mocked: this test exercises the chokepoint↔ACP boundary, not StepLoopService. */
    @MockitoBean
    private AgentRunService agentRunService;

    /** Untouched in these tests; mocked to keep the named-agent path inert. */
    @MockitoBean
    private AgentChatPort agentChatPort;

    private ResponseSink sink;

    @BeforeEach
    void setUp() {
        sink = mock(ResponseSink.class);
        when(sink.isClientConnected()).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        // Each test sets identity itself; clear defensively.
        MetaContext.clear();
    }

    /**
     * Construct a TurnRequest with an explicit precomputedBucket so the
     * triage SPI is bypassed. This isolates the dispatch logic from the
     * keyword classifier.
     */
    private TurnRequest buildTurnRequest(String message, TriageBucket bucket) {
        Long tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();
        Long memberId = getTestTenantMember().getId();
        ChatRequest legacy = new ChatRequest();
        legacy.setMessage(message);
        legacy.setSessionId("c3c-test-" + System.currentTimeMillis());
        legacy.setAgentCode("aurabot");
        return new TurnRequest(
                tenantId,
                userId,
                memberId,
                "web",
                "aurabot",
                null,
                null,
                message,
                null,
                null,
                InboundMode.NEW_FROM_REQUEST,
                bucket,
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
    @DisplayName("ACP_RUN + RunOutcome.Success -> TurnOutcome.Success + onDone + ab_agent_task row written")
    void acpRunSuccess_mapsToTurnSuccess() {
        withTestIdentity(() -> {
            Long tenantId = getTestTenant().getId();
            when(agentRunService.executeTaskSync(eq(tenantId), anyString(), eq("aurabot"), any()))
                    .thenReturn(new RunOutcome.Success(
                            "RUN_PID_1", "Created CRM lead for TestCo.", 120, 35, 0.0042d));

            TurnOutcome outcome = turnService.runTurn(
                    buildTurnRequest("create a CRM lead for TestCo", TriageBucket.ACP_RUN), sink);

            assertThat(outcome).isInstanceOf(TurnOutcome.Success.class);
            TurnOutcome.Success success = (TurnOutcome.Success) outcome;
            assertThat(success.finalResponse()).isEqualTo("Created CRM lead for TestCo.");
            assertThat(success.meta())
                    .containsEntry("runPid", "RUN_PID_1")
                    .containsEntry("inputTokens", 120)
                    .containsEntry("outputTokens", 35)
                    .containsEntry("totalCost", 0.0042d);

            verify(sink, times(1)).onDone(eq("Created CRM lead for TestCo."), any());
            verify(chatService, never()).executeAuraBotTurn(any(), any(), any());
            verify(agentRunService, times(1)).executeTaskSync(
                    eq(tenantId), anyString(), eq("aurabot"), any());

            // Verify ab_agent_task row carries the expected dispatch shape.
            List<Map<String, Object>> tasks = jdbcTemplate.queryForList(
                    "SELECT pid, assignee_type, assignee_id, task_status, title FROM ab_agent_task " +
                            "WHERE tenant_id = ? AND deleted_flag = FALSE " +
                            "ORDER BY created_at DESC LIMIT 1",
                    tenantId);
            assertThat(tasks).hasSize(1);
            Map<String, Object> row = tasks.get(0);
            assertThat(row.get("assignee_type")).isEqualTo("ai");
            assertThat(row.get("assignee_id")).isEqualTo("aurabot");
            assertThat(row.get("task_status")).isEqualTo("in_progress");
            assertThat((String) row.get("title")).startsWith("create a CRM lead");
        });
    }

    @Test
    @DisplayName("ACP_RUN + RunOutcome.Failed -> TurnOutcome.Failed + onError")
    void acpRunFailed_mapsToTurnFailed() {
        withTestIdentity(() -> {
            when(agentRunService.executeTaskSync(anyLong(), anyString(), eq("aurabot"), any()))
                    .thenReturn(new RunOutcome.Failed("RUN_PID_2", "LLM provider exploded"));

            TurnOutcome outcome = turnService.runTurn(
                    buildTurnRequest("delete every customer", TriageBucket.ACP_RUN), sink);

            assertThat(outcome).isInstanceOf(TurnOutcome.Failed.class);
            assertThat(((TurnOutcome.Failed) outcome).errorMessage()).contains("LLM provider exploded");
            verify(sink, atLeastOnce()).onError(contains("LLM provider exploded"), any());
            verify(chatService, never()).executeAuraBotTurn(any(), any(), any());
        });
    }

    @Test
    @DisplayName("ACP_RUN + RunOutcome.PendingApproval -> TurnOutcome.PendingConfirmation + onConfirmRequired")
    void acpRunPending_mapsToPendingConfirmation() {
        // Phase C.3d (Q-C3.3=α): the chokepoint surfaces the approvalPid as
        // the resumption token on the confirm_required SSE event so the
        // frontend echoes it back via POST /execute, where resumeTurn picks
        // it up and drives AgentApprovalGateService.approve / reject.
        withTestIdentity(() -> {
            when(agentRunService.executeTaskSync(anyLong(), anyString(), eq("aurabot"), any()))
                    .thenReturn(new RunOutcome.PendingApproval(
                            "RUN_PID_3", "APPROVAL_PID_99", "Step 2 awaits approval"));

            TurnOutcome outcome = turnService.runTurn(
                    buildTurnRequest("approve the deal", TriageBucket.ACP_RUN), sink);

            assertThat(outcome).isInstanceOf(TurnOutcome.PendingConfirmation.class);
            TurnOutcome.PendingConfirmation pc = (TurnOutcome.PendingConfirmation) outcome;
            assertThat(pc.pendingTurnId()).isEqualTo("APPROVAL_PID_99");
            assertThat(pc.pendingToolId()).isEqualTo("APPROVAL_PID_99");
            // confirm_required SSE event fires; pendingTurnId field carries the approvalPid
            verify(sink, times(1)).onConfirmRequired(
                    eq("APPROVAL_PID_99"),
                    eq("agent_approval_gate"),
                    eq("Step 2 awaits approval"),
                    org.mockito.ArgumentMatchers.<Map<String, Object>>any(),
                    eq("APPROVAL_PID_99"));
        });
    }

    @Test
    @DisplayName("ACP_RUN + PendingApproval(approvalPid=null) -> Failed (legacy throw site fallback)")
    void acpRunPendingWithoutApprovalPid_mapsToFailed() {
        withTestIdentity(() -> {
            when(agentRunService.executeTaskSync(anyLong(), anyString(), eq("aurabot"), any()))
                    .thenReturn(new RunOutcome.PendingApproval(
                            "RUN_PID_4", null, "Approval required but no pid"));

            TurnOutcome outcome = turnService.runTurn(
                    buildTurnRequest("approve the deal", TriageBucket.ACP_RUN), sink);

            assertThat(outcome).isInstanceOf(TurnOutcome.Failed.class);
            assertThat(((TurnOutcome.Failed) outcome).errorMessage())
                    .contains("no approval pid").contains("RUN_PID_4");
            verify(sink, atLeastOnce()).onError(contains("no approval pid"), any());
        });
    }

    @Test
    @DisplayName("ACP_RUN + RunOutcome.Skipped -> Failed with skip reason")
    void acpRunSkipped_mapsToFailed() {
        withTestIdentity(() -> {
            when(agentRunService.executeTaskSync(anyLong(), anyString(), eq("aurabot"), any()))
                    .thenReturn(new RunOutcome.Skipped("Agent runtime disabled"));

            TurnOutcome outcome = turnService.runTurn(
                    buildTurnRequest("create a lead", TriageBucket.ACP_RUN), sink);

            assertThat(outcome).isInstanceOf(TurnOutcome.Failed.class);
            assertThat(((TurnOutcome.Failed) outcome).errorMessage()).contains("Agent runtime disabled");
            verify(sink, atLeastOnce()).onError(contains("Agent runtime disabled"), any());
        });
    }

    @Test
    @DisplayName("LIGHT_CHAT (precomputed) -> chatService.executeAuraBotTurn; agentRunService never called")
    void nonAcpBucket_doesNotDispatchToAcp() {
        withTestIdentity(() -> {
            when(chatService.executeAuraBotTurn(any(), any(), any()))
                    .thenReturn(new TurnOutcome.Success("hi back", Map.of()));

            TurnOutcome outcome = turnService.runTurn(
                    buildTurnRequest("hi", TriageBucket.LIGHT_CHAT), sink);

            assertThat(outcome).isInstanceOf(TurnOutcome.Success.class);
            verify(chatService, times(1)).executeAuraBotTurn(any(), any(), any());
            verify(agentRunService, never()).executeTaskSync(anyLong(), anyString(), anyString(), any());
        });
    }

    @Test
    @DisplayName("C.3e: CONTEXTUAL_ANSWER -> ACP runtime; chatService NOT called")
    void contextualAnswerBucket_dispatchesToAcp() {
        // Phase C.3e (Q-C3.5=β step2): explanation-bucket turns reuse the
        // ACP runtime so D1 grounding + skill routing + read-only tool
        // discovery + result_contract rendering live in one place. After
        // C.3e only LIGHT_CHAT (and the null-bucket defensive fallback)
        // continues to flow through the legacy chat path.
        withTestIdentity(() -> {
            Long tenantId = getTestTenant().getId();
            when(agentRunService.executeTaskSync(eq(tenantId), anyString(), eq("aurabot"), any()))
                    .thenReturn(new RunOutcome.Success(
                            "RUN_PID_CTX",
                            "This page lists customer accounts grouped by region.",
                            65, 22, 0.0021d));

            TurnOutcome outcome = turnService.runTurn(
                    buildTurnRequest("what is this page showing", TriageBucket.CONTEXTUAL_ANSWER), sink);

            assertThat(outcome).isInstanceOf(TurnOutcome.Success.class);
            assertThat(((TurnOutcome.Success) outcome).finalResponse())
                    .startsWith("This page lists customer accounts");

            // ACP path engaged
            verify(agentRunService, times(1)).executeTaskSync(
                    eq(tenantId), anyString(), eq("aurabot"), any());
            verify(sink, times(1)).onDone(
                    eq("This page lists customer accounts grouped by region."), any());

            // Legacy chat path NOT engaged
            verify(chatService, never()).executeAuraBotTurn(any(), any(), any());

            // ab_agent_task row written (assignee_type='ai', task carries the
            // contextual question — not action-typed but still task-driven).
            List<Map<String, Object>> tasks = jdbcTemplate.queryForList(
                    "SELECT pid, assignee_type, assignee_id, title FROM ab_agent_task " +
                            "WHERE tenant_id = ? AND deleted_flag = FALSE " +
                            "ORDER BY created_at DESC LIMIT 1",
                    tenantId);
            assertThat(tasks).hasSize(1);
            Map<String, Object> row = tasks.get(0);
            assertThat(row.get("assignee_type")).isEqualTo("ai");
            assertThat(row.get("assignee_id")).isEqualTo("aurabot");
            assertThat((String) row.get("title")).startsWith("what is this page");
        });
    }
}
