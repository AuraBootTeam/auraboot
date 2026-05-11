package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.controller.AgentRunController;
import com.auraboot.framework.agent.dto.replay.AgentActionItem;
import com.auraboot.framework.agent.dto.replay.AgentConversationMessageItem;
import com.auraboot.framework.agent.dto.replay.AgentConversationTurnReplay;
import com.auraboot.framework.agent.dto.replay.AgentInterruptItem;
import com.auraboot.framework.agent.dto.replay.AgentResultContractItem;
import com.auraboot.framework.agent.dto.replay.AgentRunDetail;
import com.auraboot.framework.agent.dto.replay.AgentRunListItem;
import com.auraboot.framework.agent.dto.replay.AgentRunPage;
import com.auraboot.framework.agent.dto.replay.AgentRuntimeAuditTrail;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.integration.TestIdGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Replay UI MVP — {@link AgentRunController} integration tests.
 *
 * <p>Drives the controller via direct method invocation (the URL-prefix
 * admin guard is covered separately in the security suite). Each test seeds
 * realistic {@code ab_agent_run} / {@code ab_agent_action} /
 * {@code ab_agent_interrupt_log} / {@code ab_agent_bif} rows, then asserts the
 * projection back-out matches expectations field-by-field.
 *
 * <p>Coverage:
 * <ul>
 *   <li>{@code list_paginated} — multi-page case, total + page math correct</li>
 *   <li>{@code list_filterByStatus_returnsOnlyMatching} — status predicate</li>
 *   <li>{@code list_filterByParentRunId_returnsOnlyChildren} — parent filter</li>
 *   <li>{@code list_filterByKeyword_returnsMatchingRowsOnly} — substring match</li>
 *   <li>{@code list_intentSummaryFromBif} — BIF LEFT JOIN populates intent</li>
 *   <li>{@code detail_returnsAllSections} — run + actions + interrupts + bif + children</li>
 *   <li>{@code detail_includesTraceIdFromRunSession} — replay deep link via trace session_id</li>
 *   <li>{@code detail_prefersMetadataTraceIdWhenPresent} — chat-run trace id stored in metadata wins</li>
 *   <li>{@code detail_unknownRunId_returns404} — strict not-found</li>
 *   <li>{@code tenant_isolation_otherTenantRunInvisible} — cross-tenant leak guard</li>
 * </ul>
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("AgentRunController — replay UI MVP")
class AgentRunControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired private AgentRunController controller;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;

    @BeforeEach
    void setup() {
        tenantId = TestIdGenerator.uniqueTenantId();
        MetaContext.setContext(tenantId, testUser.getId(), testUser.getPid(), testUser.getUserName());
    }

    @AfterEach
    void cleanup() {
        if (tenantId != null) {
            jdbc.update("DELETE FROM ab_ai_trace_span WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM ab_ai_trace WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM ab_im_message WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM ab_im_conversation WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM ab_agent_authorization_decision WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM ab_agent_approval WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM ab_agent_action WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM ab_agent_interrupt_log WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM ab_agent_bif WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM ab_agent_run WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM ab_agent_task WHERE tenant_id = ?", tenantId);
        }
    }

    // =========================================================================
    // Seeding helpers
    // =========================================================================

    private String seedRun(String agentCode, String status) {
        return seedRun(agentCode, status, null, null);
    }

    /**
     * Seed an {@code ab_agent_run}. {@code parentPid} + {@code subtaskOrigin}
     * activate the parent_run_id audit pair (whitelisted values:
     * interrupt_subtask / delegate_task / scheduled_split).
     */
    private String seedRun(String agentCode, String status,
                           String parentPid, String subtaskOrigin) {
        String pid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_run " +
                        "(pid, tenant_id, task_id, agent_id, run_status, started_at, " +
                        " completed_at, duration_ms, total_cost, parent_run_id, subtask_origin, " +
                        " created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, NOW() - INTERVAL '5 minutes', " +
                        "        CASE WHEN ? IN ('succeeded','failed','cancelled') THEN NOW() ELSE NULL END, " +
                        "        CASE WHEN ? IN ('succeeded','failed','cancelled') THEN 1234 ELSE NULL END, " +
                        "        0.012345, ?, ?, NOW(), NOW())",
                pid, tenantId, UniqueIdGenerator.generate(), agentCode, status,
                status, status, parentPid, subtaskOrigin);
        return pid;
    }

    private String seedAction(String runPid, String actionCode, String status) {
        String pid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_action " +
                        "(pid, tenant_id, run_id, action_code, action_type, target_model, target_record_id, " +
                        " action_status, executed_at, before_snapshot, after_snapshot, " +
                        " field_changes, risk_level, cost_usd) " +
                        "VALUES (?, ?, ?, ?, 'data_write', 'crm_account', 'REC-PID-001', ?, NOW(), " +
                        " '{\"name\":\"old\"}'::jsonb, '{\"name\":\"new\"}'::jsonb, " +
                        " '[{\"field\":\"name\",\"from\":\"old\",\"to\":\"new\"}]'::jsonb, " +
                        " 'L1', 0.000123)",
                pid, tenantId, runPid, actionCode, status);
        return pid;
    }

    private String seedApproval(String runPid, String toolName) {
        return seedApproval(runPid, "Approve " + toolName, "Tool: " + toolName,
                "{\"toolName\":\"" + toolName + "\",\"targetRecordId\":\"REC-PID-001\"}");
    }

    private String seedApproval(String runPid, String title, String description, String requestData) {
        String pid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_approval " +
                        "(pid, tenant_id, run_id, approval_type, approval_title, approval_description, " +
                        " request_data, approval_status, policy_id, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 'tool_call', ?, ?, ?::text, 'pending', ?, NOW(), NOW())",
                pid, tenantId, runPid, title, description, requestData, UniqueIdGenerator.generate());
        return pid;
    }

    private String seedAuthorizationDecision(String runPid, String toolName, String approvalPid) {
        String pid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_authorization_decision " +
                        "(pid, tenant_id, run_id, decision_kind, tool_ref, skill_code, blast_radius, " +
                        " requested_effects, granted_effects, rejected_effects, policy_id, policy_version, " +
                        " decision_reason, require_approval, approval_id, decision_at) " +
                        "VALUES (?, ?, ?, 'incremental', ?, ?, 'REVERSIBLE', " +
                        " '[\"READ_PLATFORM_DATA\"]'::jsonb, '[\"READ_PLATFORM_DATA\"]'::jsonb, NULL, " +
                        " 'default-policy', 1, 'test grant', TRUE, ?, NOW())",
                pid, tenantId, runPid, toolName, toolName, approvalPid);
        return pid;
    }

    private String seedInterrupt(String runPid, String subPolicy, String actionTaken) {
        String pid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_interrupt_log " +
                        "(pid, tenant_id, session_id, active_run_id, new_message_excerpt, " +
                        " sub_policy, classifier_tier, confidence, reason, action_taken, created_at) " +
                        "VALUES (?, ?, ?, ?, '停一下，先做另一件事', ?, 'keyword', 0.95, " +
                        " 'matched zh stop keyword', ?, NOW())",
                pid, tenantId, "sess_" + System.nanoTime(), runPid, subPolicy, actionTaken);
        return pid;
    }

    private void seedBif(String runPid, String intent, String dispatchedSkill) {
        String pid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_bif " +
                        "(pid, tenant_id, run_id, nl_input, intent, primary_object, " +
                        " risk_level, confidence, dispatched_skill, channel, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, 'crm_account', 'L1', " +
                        " '{\"object\":0.9,\"intent\":0.95}'::jsonb, ?, 'web', NOW())",
                pid, tenantId, runPid, "查询最近活跃客户", intent, dispatchedSkill);
    }

    private String seedTrace(String sessionId) {
        return seedTraceForTenant(tenantId, sessionId);
    }

    private String seedTraceForTenant(Long traceTenantId, String sessionId) {
        String traceId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ab_ai_trace " +
                        "(trace_id, tenant_id, session_id, name, input, output, status, metadata, start_time) " +
                        "VALUES (?, ?, ?, 'chat', 'input', 'output', 'success', '{}'::jsonb, NOW())",
                traceId, traceTenantId, sessionId);
        return traceId;
    }

    private void setRunMetadata(String runPid, String json) {
        jdbc.update("UPDATE ab_agent_run SET metadata = ? WHERE tenant_id = ? AND pid = ?",
                json, tenantId, runPid);
    }

    private String loadTaskId(String runPid) {
        return jdbc.queryForObject(
                "SELECT task_id FROM ab_agent_run WHERE tenant_id = ? AND pid = ?",
                String.class, tenantId, runPid);
    }

    private void seedTask(String taskPid, String inputData, String outputData, String description) {
        jdbc.update("INSERT INTO ab_agent_task " +
                        "(pid, tenant_id, title, description, task_status, task_priority, " +
                        " assignee_type, assignee_id, input_data, output_data, created_at, updated_at) " +
                        "VALUES (?, ?, 'Replay turn task', ?, 'completed', 'normal', " +
                        "        'ai', 'aurabot', ?, ?, NOW(), NOW())",
                taskPid, tenantId, description, inputData, outputData);
    }

    private void seedConversation(Long conversationId) {
        jdbc.update("INSERT INTO ab_im_conversation " +
                        "(id, tenant_id, type, name, max_seq, created_at, updated_at) " +
                        "VALUES (?, ?, 'BOT', 'Replay test conversation', 2, NOW(), NOW())",
                conversationId, tenantId);
    }

    private void seedConversationForTenant(Long targetTenantId, Long conversationId) {
        jdbc.update("INSERT INTO ab_im_conversation " +
                        "(id, tenant_id, type, name, max_seq, created_at, updated_at) " +
                        "VALUES (?, ?, 'BOT', 'Other tenant conversation', 1, NOW(), NOW())",
                conversationId, targetTenantId);
    }

    private void seedMessage(Long messageId, Long conversationId, String senderType,
                             Long senderId, long seq, String messageType, String content,
                             String clientMsgId, String triageBucket) {
        jdbc.update("INSERT INTO ab_im_message " +
                        "(id, conversation_id, tenant_id, sender_id, sender_type, seq, " +
                        " message_type, content, client_msg_id, triage_bucket, " +
                        " triage_confidence, triage_reason_codes, thinking_content, " +
                        " thinking_signature, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0.91, " +
                        "        '[\"agent-runtime-test\"]'::jsonb, " +
                        "        CASE WHEN ? = 'agent' THEN 'thinking trace' ELSE NULL END, " +
                        "        CASE WHEN ? = 'agent' THEN 'sig-test' ELSE NULL END, NOW())",
                messageId, conversationId, tenantId, senderId, senderType, seq,
                messageType, content, clientMsgId, triageBucket, senderType, senderType);
    }

    private void seedMessageForTenant(Long targetTenantId, Long messageId, Long conversationId) {
        jdbc.update("INSERT INTO ab_im_message " +
                        "(id, conversation_id, tenant_id, sender_id, sender_type, seq, " +
                        " message_type, content, client_msg_id, created_at) " +
                        "VALUES (?, ?, ?, 1, 'human', 1, 'text', 'other tenant message', " +
                        "        'other-client-msg', NOW())",
                messageId, conversationId, targetTenantId);
    }

    // =========================================================================
    // List endpoint
    // =========================================================================

    @Test
    @DisplayName("list paginates: total + page slice math correct")
    void list_paginated() {
        for (int i = 0; i < 5; i++) {
            seedRun("aurabot", "succeeded");
        }

        ApiResponse<AgentRunPage> page0 = controller.list(0, 2, null, null, null, null);
        assertThat(page0.isSuccess()).isTrue();
        AgentRunPage p0 = page0.getData();
        assertThat(p0.getTotal()).isEqualTo(5L);
        assertThat(p0.getPage()).isEqualTo(0);
        assertThat(p0.getSize()).isEqualTo(2);
        assertThat(p0.getItems()).hasSize(2);

        ApiResponse<AgentRunPage> page2 = controller.list(2, 2, null, null, null, null);
        AgentRunPage p2 = page2.getData();
        assertThat(p2.getItems()).hasSize(1); // 5 rows / size 2 → page 2 has the last 1
        assertThat(p2.getTotal()).isEqualTo(5L);
    }

    @Test
    @DisplayName("list filters by status — only matching rows returned")
    void list_filterByStatus_returnsOnlyMatching() {
        String okPid = seedRun("aurabot", "succeeded");
        seedRun("aurabot", "failed");
        seedRun("aurabot", "running");

        ApiResponse<AgentRunPage> resp = controller.list(0, 50, "succeeded", null, null, null);
        AgentRunPage p = resp.getData();
        assertThat(p.getTotal()).isEqualTo(1L);
        assertThat(p.getItems()).extracting(AgentRunListItem::getRunId).containsExactly(okPid);
        assertThat(p.getItems().get(0).getRunStatus()).isEqualTo("succeeded");
    }

    @Test
    @DisplayName("list filters by parentRunId — returns only child runs")
    void list_filterByParentRunId_returnsOnlyChildren() {
        String parent = seedRun("aurabot", "running");
        String childA = seedRun("aurabot", "succeeded", parent, "interrupt_subtask");
        String childB = seedRun("aurabot", "running", parent, "delegate_task");
        // unrelated other-parent run
        seedRun("aurabot", "succeeded");

        ApiResponse<AgentRunPage> resp = controller.list(0, 50, null, null, parent, null);
        AgentRunPage p = resp.getData();
        assertThat(p.getTotal()).isEqualTo(2L);
        assertThat(p.getItems())
                .extracting(AgentRunListItem::getRunId)
                .containsExactlyInAnyOrder(childA, childB);
        assertThat(p.getItems())
                .extracting(AgentRunListItem::getParentRunId)
                .containsOnly(parent);
        assertThat(p.getItems())
                .extracting(AgentRunListItem::getSubtaskOrigin)
                .containsExactlyInAnyOrder("interrupt_subtask", "delegate_task");
    }

    @Test
    @DisplayName("list filters by keyword (case-insensitive substring across pid / agent_id / task_id)")
    void list_filterByKeyword_returnsMatchingRowsOnly() {
        String hit = seedRun("aurabot-special", "running");
        seedRun("aurabot", "running");

        ApiResponse<AgentRunPage> resp = controller.list(0, 50, null, null, null, "SPECIAL");
        AgentRunPage p = resp.getData();
        assertThat(p.getTotal()).isEqualTo(1L);
        assertThat(p.getItems()).extracting(AgentRunListItem::getRunId).containsExactly(hit);
    }

    @Test
    @DisplayName("list surfaces intent summary via LEFT JOIN ab_agent_bif")
    void list_intentSummaryFromBif() {
        String pid = seedRun("aurabot", "succeeded");
        seedBif(pid, "QUERY_RECORDS", "crm.account.search");

        ApiResponse<AgentRunPage> resp = controller.list(0, 10, null, null, null, null);
        AgentRunListItem only = resp.getData().getItems().get(0);
        assertThat(only.getRunId()).isEqualTo(pid);
        assertThat(only.getIntentSummary()).isEqualTo("QUERY_RECORDS");
    }

    // =========================================================================
    // Detail endpoint
    // =========================================================================

    @Test
    @DisplayName("detail returns run + actions + interrupts + child runs + bif")
    void detail_returnsAllSections() {
        String parent = seedRun("aurabot", "running");
        String child = seedRun("aurabot", "succeeded", parent, "interrupt_subtask");
        String actionA = seedAction(parent, "crm.account.update", "success");
        String actionB = seedAction(parent, "crm.account.notify", "success");
        String interruptPid = seedInterrupt(parent, "insert_subtask", "subtask_enqueued");
        seedBif(parent, "UPDATE_RECORD", "crm.account.update");

        ApiResponse<AgentRunDetail> resp = controller.detail(parent);
        assertThat(resp.isSuccess()).isTrue();
        AgentRunDetail d = resp.getData();

        // Run header
        assertThat(d.getRun()).isNotNull();
        assertThat(d.getRun().getRunId()).isEqualTo(parent);
        assertThat(d.getRun().getRunStatus()).isEqualTo("running");
        assertThat(d.getRun().getIntentSummary()).isEqualTo("UPDATE_RECORD");

        // Actions
        assertThat(d.getActions()).hasSize(2);
        assertThat(d.getActions())
                .extracting(AgentActionItem::getPid)
                .containsExactlyInAnyOrder(actionA, actionB);
        AgentActionItem first = d.getActions().get(0);
        assertThat(first.getActionCode()).startsWith("crm.account.");
        assertThat(first.getTargetModel()).isEqualTo("crm_account");
        assertThat(first.getTargetRecordId()).isEqualTo("REC-PID-001");
        assertThat(first.getTargetRecordPid()).isEqualTo("REC-PID-001");
        assertThat(first.getRiskLevel()).isEqualTo("L1");
        assertThat(first.getBeforeSnapshot()).contains("\"name\"").contains("old");
        assertThat(first.getAfterSnapshot()).contains("new");
        assertThat(first.getFieldChanges()).contains("\"field\"").contains("\"name\"");

        // Interrupts
        assertThat(d.getInterruptLog()).hasSize(1);
        AgentInterruptItem ic = d.getInterruptLog().get(0);
        assertThat(ic.getPid()).isEqualTo(interruptPid);
        assertThat(ic.getSubPolicy()).isEqualTo("insert_subtask");
        assertThat(ic.getActionTaken()).isEqualTo("subtask_enqueued");

        // Child runs
        assertThat(d.getChildRuns()).hasSize(1);
        assertThat(d.getChildRuns().get(0).getRunId()).isEqualTo(child);
        assertThat(d.getChildRuns().get(0).getSubtaskOrigin()).isEqualTo("interrupt_subtask");

        // BIF
        assertThat(d.getBif()).isNotNull();
        assertThat(d.getBif().getIntent()).isEqualTo("UPDATE_RECORD");
        assertThat(d.getBif().getDispatchedSkill()).isEqualTo("crm.account.update");
        assertThat(d.getBif().getPrimaryObject()).isEqualTo("crm_account");
        assertThat(d.getBif().getChannel()).isEqualTo("web");
    }

    @Test
    @DisplayName("detail includes traceId when trace session_id matches run pid")
    void detail_includesTraceIdFromRunSession() {
        String runPid = seedRun("aurabot", "succeeded");
        String traceId = seedTrace(runPid);

        ApiResponse<AgentRunDetail> resp = controller.detail(runPid);

        assertThat(resp.isSuccess()).isTrue();
        assertThat(resp.getData().getTraceId()).isEqualTo(traceId);
    }

    @Test
    @DisplayName("detail prefers metadata.traceId over session_id fallback when both exist")
    void detail_prefersMetadataTraceIdWhenPresent() {
        String runPid = seedRun("aurabot", "succeeded");
        String fallbackTraceId = seedTrace(runPid);
        String metadataTraceId = seedTrace("chat-session-1");
        setRunMetadata(runPid, "{\"traceId\":\"" + metadataTraceId + "\"}");

        ApiResponse<AgentRunDetail> resp = controller.detail(runPid);

        assertThat(resp.isSuccess()).isTrue();
        assertThat(resp.getData().getTraceId()).isEqualTo(metadataTraceId);
        assertThat(resp.getData().getTraceId()).isNotEqualTo(fallbackTraceId);
    }

    @Test
    @DisplayName("detail leaves conversationTurn null when run has no turn identity")
    void detail_returnsNullConversationTurnWhenNoTurnIdentity() {
        String runPid = seedRun("aurabot", "succeeded");

        ApiResponse<AgentRunDetail> resp = controller.detail(runPid);

        assertThat(resp.isSuccess()).isTrue();
        assertThat(resp.getData().getConversationTurn()).isNull();
        assertThat(resp.getData().getResultContracts()).isEmpty();
    }

    @Test
    @DisplayName("detail does not link trace from another tenant")
    void detail_doesNotLinkCrossTenantTrace() {
        Long otherTenant = TestIdGenerator.uniqueTenantId();
        String runPid = seedRun("aurabot", "succeeded");
        seedTraceForTenant(otherTenant, runPid);

        try {
            ApiResponse<AgentRunDetail> resp = controller.detail(runPid);

            assertThat(resp.isSuccess()).isTrue();
            assertThat(resp.getData().getTraceId()).isNull();
        } finally {
            jdbc.update("DELETE FROM ab_ai_trace_span WHERE tenant_id = ?", otherTenant);
            jdbc.update("DELETE FROM ab_ai_trace WHERE tenant_id = ?", otherTenant);
        }
    }

    @Test
    @DisplayName("detail reconstructs conversation turn and result-contract chain")
    void detail_returnsConversationTurnAndResultContracts() {
        String runPid = seedRun("aurabot", "succeeded");
        String actionPid = seedAction(runPid, "crm.account.search", "success");
        String taskPid = loadTaskId(runPid);
        String turnId = UniqueIdGenerator.generate();
        Long conversationId = TestIdGenerator.uniqueUserId();
        Long inboundMessageId = TestIdGenerator.uniqueUserId();
        Long outboundMessageId = TestIdGenerator.uniqueUserId();

        seedConversation(conversationId);
        seedTask(taskPid,
                "{\"turnId\":\"" + turnId + "\",\"conversationId\":" + conversationId +
                        ",\"inboundMessageId\":" + inboundMessageId +
                        ",\"triageBucket\":\"acp_run\",\"userMessage\":\"统计客户信息\"}",
                "{\"finalResponse\":\"已统计客户信息\"}",
                "统计客户信息");
        seedMessage(inboundMessageId, conversationId, "human", testUser.getId(), 1,
                "text", "统计客户信息", "in-" + turnId, "acp_run");
        seedMessage(outboundMessageId, conversationId, "agent", 1L, 2,
                "ai_response", "已统计客户信息", "out-" + turnId, null);

        ApiResponse<AgentRunDetail> resp = controller.detail(runPid);

        assertThat(resp.isSuccess()).isTrue();
        AgentRunDetail detail = resp.getData();
        AgentConversationTurnReplay turn = detail.getConversationTurn();
        assertThat(turn).isNotNull();
        assertThat(turn.getRunId()).isEqualTo(runPid);
        assertThat(turn.getTaskPid()).isEqualTo(taskPid);
        assertThat(turn.getTurnId()).isEqualTo(turnId);
        assertThat(turn.getConversationId()).isEqualTo(conversationId);
        assertThat(turn.getInboundMessageId()).isEqualTo(inboundMessageId);
        assertThat(turn.getOutboundMessageId()).isEqualTo(outboundMessageId);
        assertThat(turn.getTriageBucket()).isEqualTo("acp_run");
        assertThat(turn.getUserMessage()).isEqualTo("统计客户信息");
        assertThat(turn.getFinalResponse()).isEqualTo("已统计客户信息");
        assertThat(turn.getResultContractIds()).containsExactly("rc-" + actionPid);

        assertThat(turn.getMessages()).hasSize(2);
        assertThat(turn.getMessages())
                .extracting(AgentConversationMessageItem::getMessageId)
                .containsExactly(inboundMessageId, outboundMessageId);
        assertThat(turn.getMessages().get(0).getTriageReasonCodes()).contains("agent-runtime-test");
        assertThat(turn.getMessages().get(1).getThinkingContent()).isEqualTo("thinking trace");

        assertThat(detail.getActions()).hasSize(1);
        assertThat(detail.getActions().get(0).getResultContractId()).isEqualTo("rc-" + actionPid);
        assertThat(detail.getResultContracts()).hasSize(1);
        AgentResultContractItem contract = detail.getResultContracts().get(0);
        assertThat(contract.getContractId()).isEqualTo("rc-" + actionPid);
        assertThat(contract.getActionPid()).isEqualTo(actionPid);
        Map<String, Object> contractBody = contract.getContract();
        assertThat(contractBody)
                .containsEntry("status", "success")
                .containsEntry("skillCode", "crm.account.search")
                .containsEntry("outputType", "action_proposal")
                .containsEntry("actionability", "execute");
        assertThat(contractBody.get("data")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> contractData = (Map<String, Object>) contractBody.get("data");
        assertThat(contractData)
                .containsEntry("actionPid", actionPid)
                .containsEntry("actionCode", "crm.account.search")
                .containsEntry("actionType", "data_write")
                .containsEntry("targetModel", "crm_account");
        assertThat(contractData.get("beforeSnapshot")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> beforeSnapshot = (Map<String, Object>) contractData.get("beforeSnapshot");
        assertThat(beforeSnapshot).containsEntry("name", "old");
        assertThat(contractData.get("fieldChanges")).isInstanceOf(List.class);
        assertThat((List<?>) contractData.get("fieldChanges")).hasSize(1);
    }

    @Test
    @DisplayName("detail does not link cross-tenant conversation messages")
    void detail_doesNotLinkCrossTenantConversationMessages() {
        Long otherTenant = TestIdGenerator.uniqueTenantId();
        String runPid = seedRun("aurabot", "succeeded");
        String taskPid = loadTaskId(runPid);
        String turnId = UniqueIdGenerator.generate();
        Long conversationId = TestIdGenerator.uniqueUserId();
        Long inboundMessageId = TestIdGenerator.uniqueUserId();

        seedTask(taskPid,
                "{\"turnId\":\"" + turnId + "\",\"conversationId\":" + conversationId +
                        ",\"inboundMessageId\":" + inboundMessageId +
                        ",\"triageBucket\":\"acp_run\",\"userMessage\":\"tenant scoped\"}",
                "{}", "tenant scoped");
        try {
            seedConversationForTenant(otherTenant, conversationId);
            seedMessageForTenant(otherTenant, inboundMessageId, conversationId);

            ApiResponse<AgentRunDetail> resp = controller.detail(runPid);

            assertThat(resp.isSuccess()).isTrue();
            assertThat(resp.getData().getConversationTurn()).isNotNull();
            assertThat(resp.getData().getConversationTurn().getMessages()).isEmpty();
            assertThat(resp.getData().getConversationTurn().getInboundMessageId()).isEqualTo(inboundMessageId);
        } finally {
            jdbc.update("DELETE FROM ab_im_message WHERE tenant_id = ?", otherTenant);
            jdbc.update("DELETE FROM ab_im_conversation WHERE tenant_id = ?", otherTenant);
        }
    }

    @Test
    @DisplayName("audit query returns action, authorization, approval, and result contract by run/conversation/tool")
    void audit_returnsRuntimeEvidenceByRunConversationAndTool() {
        String runPid = seedRun("aurabot", "succeeded");
        String actionPid = seedAction(runPid, "crm.account.search", "success");
        String approvalPid = seedApproval(runPid, "crm.account.search");
        String authzPid = seedAuthorizationDecision(runPid, "crm.account.search", approvalPid);
        String taskPid = loadTaskId(runPid);
        Long conversationId = TestIdGenerator.uniqueUserId();
        Long inboundMessageId = TestIdGenerator.uniqueUserId();
        seedTask(taskPid,
                "{\"conversationId\":" + conversationId + ",\"inboundMessageId\":" + inboundMessageId +
                        ",\"userMessage\":\"统计客户信息\"}",
                "{}", "统计客户信息");

        ApiResponse<AgentRuntimeAuditTrail> byRun =
                controller.audit(runPid, null, "crm.account.search");
        ApiResponse<AgentRuntimeAuditTrail> byConversation =
                controller.audit(null, conversationId, "crm.account.search");

        assertThat(byRun.isSuccess()).isTrue();
        AgentRuntimeAuditTrail trail = byRun.getData();
        assertThat(trail.getRunId()).isEqualTo(runPid);
        assertThat(trail.getConversationId()).isNull();
        assertThat(trail.getToolName()).isEqualTo("crm.account.search");
        assertThat(trail.getActions()).hasSize(1);
        assertThat(trail.getActions().get(0).getPid()).isEqualTo(actionPid);
        assertThat(trail.getAuthorizationDecisions()).hasSize(1);
        assertThat(trail.getAuthorizationDecisions().get(0).getPid()).isEqualTo(authzPid);
        assertThat(trail.getAuthorizationDecisions().get(0).isRequireApproval()).isTrue();
        assertThat(trail.getApprovals()).hasSize(1);
        assertThat(trail.getApprovals().get(0).getPid()).isEqualTo(approvalPid);
        assertThat(trail.getApprovals().get(0).getTargetPid()).isEqualTo("REC-PID-001");
        assertThat(trail.getApprovals().get(0).getApprovalStatus()).isEqualTo("pending");
        assertThat(trail.getResultContracts()).hasSize(1);
        assertThat(trail.getResultContracts().get(0).getActionPid()).isEqualTo(actionPid);

        assertThat(byConversation.isSuccess()).isTrue();
        assertThat(byConversation.getData().getRunId()).isEqualTo(runPid);
        assertThat(byConversation.getData().getConversationId()).isEqualTo(conversationId);
        assertThat(byConversation.getData().getActions()).hasSize(1);
    }

    @Test
    @DisplayName("audit query links approval by authorization approval_id when text does not contain tool")
    void audit_linksApprovalByAuthorizationApprovalId() {
        String runPid = seedRun("aurabot", "succeeded");
        seedAction(runPid, "crm.account.search", "success");
        String approvalPid = seedApproval(runPid,
                "Human approval required",
                "Review generated plan before execution",
                "{\"reason\":\"policy gate\"}");
        seedAuthorizationDecision(runPid, "crm.account.search", approvalPid);

        ApiResponse<AgentRuntimeAuditTrail> response =
                controller.audit(runPid, null, "crm.account.search");

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData().getAuthorizationDecisions()).hasSize(1);
        assertThat(response.getData().getApprovals())
                .extracting("pid")
                .containsExactly(approvalPid);
    }

    @Test
    @DisplayName("detail unknown runId -> 404")
    void detail_unknownRunId_returns404() {
        ApiResponse<AgentRunDetail> resp = controller.detail("RUN_does_not_exist");
        assertThat(resp.getCode()).isEqualTo("404");
        assertThat(resp.getMessage()).isEqualTo("agent_run_not_found");
        assertThat(resp.getData()).isNull();
    }

    @Test
    @DisplayName("durationMs: stored value preferred, derived fallback, then 0")
    void list_durationMs_threeBranches() {
        // Branch 1: stored duration_ms = 5000 (bound as BIGINT via Long literal).
        String storedPid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_run " +
                        "(pid, tenant_id, task_id, agent_id, run_status, started_at, " +
                        " completed_at, duration_ms, total_cost, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 'aurabot', 'succeeded', NOW() - INTERVAL '5 minutes', " +
                        "        NOW(), ?, 0.001, NOW(), NOW())",
                storedPid, tenantId, UniqueIdGenerator.generate(), 5000L);

        // Branch 2: duration_ms NULL, but created_at + completed_at present
        // (fixed 7-second delta) — controller derives 7000 from the timestamps.
        String derivedPid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_run " +
                        "(pid, tenant_id, task_id, agent_id, run_status, started_at, " +
                        " completed_at, duration_ms, total_cost, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 'aurabot', 'succeeded', " +
                        "        TIMESTAMP '2026-01-01 00:00:00', " +
                        "        TIMESTAMP '2026-01-01 00:00:07', " +
                        "        NULL, 0.001, " +
                        "        TIMESTAMP '2026-01-01 00:00:00', " +
                        "        TIMESTAMP '2026-01-01 00:00:07')",
                derivedPid, tenantId, UniqueIdGenerator.generate());

        // Branch 3: duration_ms NULL and completed_at NULL — running row, falls
        // through to the 0L default.
        String zeroPid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_run " +
                        "(pid, tenant_id, task_id, agent_id, run_status, started_at, " +
                        " completed_at, duration_ms, total_cost, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 'aurabot', 'running', NOW(), " +
                        "        NULL, NULL, 0.001, NOW(), NOW())",
                zeroPid, tenantId, UniqueIdGenerator.generate());

        ApiResponse<AgentRunPage> resp = controller.list(0, 50, null, null, null, null);
        assertThat(resp.isSuccess()).isTrue();
        AgentRunPage page = resp.getData();
        assertThat(page.getTotal()).isEqualTo(3L);

        AgentRunListItem stored = page.getItems().stream()
                .filter(i -> storedPid.equals(i.getRunId())).findFirst().orElseThrow();
        AgentRunListItem derived = page.getItems().stream()
                .filter(i -> derivedPid.equals(i.getRunId())).findFirst().orElseThrow();
        AgentRunListItem zero = page.getItems().stream()
                .filter(i -> zeroPid.equals(i.getRunId())).findFirst().orElseThrow();

        // Branch 1: stored value is preferred and propagated unchanged.
        assertThat(stored.getDurationMs())
                .as("non-null duration_ms must propagate as Long without ClassCastException")
                .isEqualTo(5000L);

        // Branch 2: derived from completed_at - created_at = 7 seconds.
        assertThat(derived.getDurationMs())
                .as("when duration_ms NULL, fallback derives from timestamps (7s = 7000ms)")
                .isEqualTo(7000L);

        // Branch 3: nothing to derive from — defaults to 0.
        assertThat(zero.getDurationMs())
                .as("when both duration_ms and completed_at NULL, durationMs defaults to 0")
                .isEqualTo(0L);
    }

    @Test
    @DisplayName("tenant isolation — run from another tenant invisible to caller")
    void tenant_isolation_otherTenantRunInvisible() {
        Long otherTenant = TestIdGenerator.uniqueTenantId();
        String otherPid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_run " +
                        "(pid, tenant_id, task_id, agent_id, run_status, started_at, " +
                        " created_at, updated_at) " +
                        "VALUES (?, ?, ?, 'aurabot', 'succeeded', NOW(), NOW(), NOW())",
                otherPid, otherTenant, UniqueIdGenerator.generate());

        try {
            // Caller is on tenantId, querying must see zero rows.
            ApiResponse<AgentRunPage> listResp = controller.list(0, 50, null, null, null, null);
            assertThat(listResp.getData().getTotal()).isEqualTo(0L);
            assertThat(listResp.getData().getItems()).isEmpty();

            // Detail by another tenant's pid must surface as not-found, NOT leak.
            ApiResponse<AgentRunDetail> detailResp = controller.detail(otherPid);
            assertThat(detailResp.getCode()).isEqualTo("404");
            assertThat(detailResp.getData()).isNull();
        } finally {
            jdbc.update("DELETE FROM ab_agent_run WHERE tenant_id = ?", otherTenant);
        }
    }
}
