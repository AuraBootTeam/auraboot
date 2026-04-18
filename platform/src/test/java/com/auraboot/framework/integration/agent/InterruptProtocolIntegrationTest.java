package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.controller.InterruptController;
import com.auraboot.framework.agent.service.InterruptClassifier;
import com.auraboot.framework.agent.service.InterruptDispatcher;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-28: Interrupt Protocol — classifier + dispatcher + controller flow.
 *
 * Classifier tests hit the keyword paths deterministically; dispatcher
 * tests verify side effects (run cancellation, audit log rows).
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("Interrupt Protocol (PR-28)")
class InterruptProtocolIntegrationTest extends BaseIntegrationTest {

    @Autowired private InterruptClassifier classifier;
    @Autowired private InterruptDispatcher dispatcher;
    @Autowired private InterruptController controller;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;
    private String sessionId;

    @BeforeEach
    void setup() {
        tenantId = 9_250_000L + System.nanoTime() % 100_000;
        sessionId = "sess_intr_" + System.nanoTime();
        MetaContext.setContext(tenantId, testUser.getId(), testUser.getPid(), testUser.getUserName());
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_interrupt_log WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_agent_run WHERE tenant_id = ?", tenantId);
    }

    /** Seed a running agent run for this session's "active" state. */
    private String seedRunningRun() {
        String pid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_run (pid, tenant_id, task_id, agent_id, run_status, " +
                        " started_at, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 'aurabot', 'running', NOW(), NOW(), NOW())",
                pid, tenantId, UniqueIdGenerator.generate());
        return pid;
    }

    // =========================================================================
    // Classifier
    // =========================================================================

    @Test
    @DisplayName("'停一下' classifies as replace_intent (zh stop keyword)")
    void classify_zh_stop() {
        InterruptClassifier.Classification c = classifier.classify("停一下", "正在生成报告");
        assertThat(c.getSubPolicy()).isEqualTo(InterruptClassifier.REPLACE_INTENT);
        assertThat(c.getTier()).isEqualTo("keyword");
    }

    @Test
    @DisplayName("'stop' in English classifies as replace_intent")
    void classify_en_stop() {
        InterruptClassifier.Classification c = classifier.classify("stop doing that", "running");
        assertThat(c.getSubPolicy()).isEqualTo(InterruptClassifier.REPLACE_INTENT);
    }

    @Test
    @DisplayName("clarification phrasing → append_context")
    void classify_append() {
        InterruptClassifier.Classification c = classifier.classify(
                "参数应该是最近30天而不是最近7天", "分析客户活跃度");
        assertThat(c.getSubPolicy()).isEqualTo(InterruptClassifier.APPEND_CONTEXT);
    }

    @Test
    @DisplayName("'另外' with no augmentation signal → insert_subtask")
    void classify_insert() {
        InterruptClassifier.Classification c = classifier.classify(
                "另外把张三的邮箱改成xx@test.com", "生成月度报告");
        assertThat(c.getSubPolicy()).isEqualTo(InterruptClassifier.INSERT_SUBTASK);
    }

    @Test
    @DisplayName("empty message → append_context with low confidence")
    void classify_empty_defaults() {
        InterruptClassifier.Classification c = classifier.classify("", "anything");
        assertThat(c.getSubPolicy()).isEqualTo(InterruptClassifier.APPEND_CONTEXT);
        assertThat(c.getConfidence()).isLessThanOrEqualTo(0.50);
    }

    @Test
    @DisplayName("replace-priority — 'stop, also change X' still classifies as replace")
    void classify_replace_wins_over_append() {
        InterruptClassifier.Classification c = classifier.classify("stop, also change X",
                "doing Y");
        assertThat(c.getSubPolicy()).isEqualTo(InterruptClassifier.REPLACE_INTENT);
    }

    // =========================================================================
    // Dispatcher
    // =========================================================================

    @Test
    @DisplayName("replace_intent with active run → run cancelled + log row recorded")
    void dispatch_replace_cancels_run() {
        String runPid = seedRunningRun();
        InterruptClassifier.Classification c = classifier.classify("停一下", "任务进行中");

        InterruptDispatcher.DispatchResult r = dispatcher.dispatch(
                tenantId, sessionId, runPid, "停一下", c);

        assertThat(r.getActionTaken()).isEqualTo("cancelled_run");
        assertThat(r.getActiveRunId()).isEqualTo(runPid);

        String runStatus = jdbc.queryForObject(
                "SELECT run_status FROM ab_agent_run WHERE pid = ?", String.class, runPid);
        assertThat(runStatus).isEqualTo("cancelled");

        Map<String, Object> logRow = jdbc.queryForMap(
                "SELECT sub_policy, action_taken, active_run_id FROM ab_agent_interrupt_log WHERE pid = ?",
                r.getInterruptLogPid());
        assertThat(logRow.get("sub_policy")).isEqualTo("replace_intent");
        assertThat(logRow.get("action_taken")).isEqualTo("cancelled_run");
        assertThat(logRow.get("active_run_id")).isEqualTo(runPid);
    }

    @Test
    @DisplayName("replace_intent without active run → noop action, still logs")
    void dispatch_replace_no_active_is_noop() {
        InterruptClassifier.Classification c = classifier.classify("取消", "idle session");

        InterruptDispatcher.DispatchResult r = dispatcher.dispatch(
                tenantId, sessionId, null, "取消", c);
        assertThat(r.getActionTaken()).isEqualTo("noop");

        String logged = jdbc.queryForObject(
                "SELECT action_taken FROM ab_agent_interrupt_log WHERE pid = ?",
                String.class, r.getInterruptLogPid());
        assertThat(logged).isEqualTo("noop");
    }

    @Test
    @DisplayName("append_context does NOT cancel the active run — only logs")
    void dispatch_append_keeps_run() {
        String runPid = seedRunningRun();
        InterruptClassifier.Classification c = classifier.classify(
                "顺便参数改成最近30天", "some intent");

        InterruptDispatcher.DispatchResult r = dispatcher.dispatch(
                tenantId, sessionId, runPid, "顺便参数改成最近30天", c);

        assertThat(r.getActionTaken()).isEqualTo("context_injected");
        String status = jdbc.queryForObject(
                "SELECT run_status FROM ab_agent_run WHERE pid = ?", String.class, runPid);
        assertThat(status).isEqualTo("running");
    }

    @Test
    @DisplayName("insert_subtask → action_taken=subtask_enqueued, run untouched")
    void dispatch_insert_enqueues_subtask() {
        String runPid = seedRunningRun();
        InterruptClassifier.Classification c = classifier.classify(
                "另外把 alice 的邮箱改了", "报告生成中");

        InterruptDispatcher.DispatchResult r = dispatcher.dispatch(
                tenantId, sessionId, runPid, "另外把 alice 的邮箱改了", c);

        assertThat(r.getActionTaken()).isEqualTo("subtask_enqueued");
        String status = jdbc.queryForObject(
                "SELECT run_status FROM ab_agent_run WHERE pid = ?", String.class, runPid);
        assertThat(status).isEqualTo("running");
    }

    // =========================================================================
    // Controller
    // =========================================================================

    @Test
    @DisplayName("POST /interrupt returns the classification result for the gateway")
    void controller_handle_interrupt() {
        String runPid = seedRunningRun();
        ApiResponse<Map<String, Object>> r = controller.handleInterrupt(sessionId,
                Map.of("new_message", "等等不对,取消",
                        "active_run_id", runPid,
                        "current_intent_summary", "generating"));

        assertThat(r.getData().get("sub_policy")).isEqualTo("replace_intent");
        assertThat(r.getData().get("action_taken")).isEqualTo("cancelled_run");
        assertThat(r.getData().get("active_run_id")).isEqualTo(runPid);
    }

    @Test
    @DisplayName("GET /interrupt-log returns session interrupts newest-first")
    void controller_list_log() {
        String runPid = seedRunningRun();
        controller.handleInterrupt(sessionId,
                Map.of("new_message", "停", "active_run_id", runPid,
                        "current_intent_summary", "x"));
        controller.handleInterrupt(sessionId,
                Map.of("new_message", "顺便补充一下", "current_intent_summary", "y"));

        ApiResponse<List<Map<String, Object>>> r = controller.listInterrupts(sessionId, 50);
        assertThat(r.getData()).hasSize(2);
        // most recent first
        assertThat(r.getData().get(0).get("sub_policy")).isEqualTo("append_context");
        assertThat(r.getData().get(1).get("sub_policy")).isEqualTo("replace_intent");
    }

    @Test
    @DisplayName("interrupt log is tenant-scoped")
    void tenant_isolation_on_log() {
        String runPid = seedRunningRun();
        controller.handleInterrupt(sessionId,
                Map.of("new_message", "停", "active_run_id", runPid,
                        "current_intent_summary", "x"));

        MetaContext.setContext(tenantId + 1_000_000, testUser.getId(), testUser.getPid(), testUser.getUserName());
        ApiResponse<List<Map<String, Object>>> foreign = controller.listInterrupts(sessionId, 50);
        assertThat(foreign.getData()).isEmpty();
    }
}
