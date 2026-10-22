package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.provider.PlatformToolProvider;
import com.auraboot.framework.agent.provider.ProviderExecutionResult;
import com.auraboot.framework.agent.provider.ToolDefinition;
import com.auraboot.framework.agent.provider.ToolDiscoveryContext;
import com.auraboot.framework.agent.service.AgentApprovalGateService;
import com.auraboot.framework.agent.service.StepContext;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A.1 follow-up — ACP P0-6 / P1: {@code platform.delegate_task} LLM tool that
 * lets a running agent fork a child agent run via {@link PlatformToolProvider}.
 *
 * <p>Three observable invariants this IT covers:
 * <ol>
 *   <li>Tool registration: the tool surface the LLM sees is L2 +
 *       {@code requiresApproval=true} so any tenant approval policy targeting
 *       {@code platform.delegate_task} fires the gate at
 *       {@code ToolLoopService.executeToolCall} time. (This IT does not drive
 *       the full loop end-to-end — it directly exercises
 *       {@link AgentApprovalGateService#checkAndRequestApproval} with the
 *       tool's contract values to prove the gate produces a real
 *       {@code ab_agent_approval} row.)</li>
 *   <li>Successful spawn path: with parent run pre-seeded and
 *       {@link StepContext#setRunPid} bound, calling
 *       {@code provider.execute("platform.delegate_task", ...)} writes a real
 *       child {@code ab_agent_run} carrying {@code subtask_origin='delegate_task'}
 *       linked to the parent.</li>
 *   <li>Input validation surfaces structured errors (NOT exceptions): missing
 *       {@code subtaskMessage} or absent {@link StepContext#getRunPid()} both
 *       return {@code success=false} with a recovery message — the tool MUST
 *       NOT silently coerce missing inputs (no fallback, no auto-create).</li>
 * </ol>
 *
 * <p>Reference source files (read-only):
 * <ul>
 *   <li>{@code platform/src/main/java/com/auraboot/framework/agent/provider/PlatformToolProvider.java}
 *       (tool registration + delegateTask method)</li>
 *   <li>{@code platform/src/main/java/com/auraboot/framework/agent/service/SubAgentRunner.java}
 *       (called by delegateTask)</li>
 *   <li>{@code platform/src/main/java/com/auraboot/framework/agent/service/AgentApprovalGateService.java}</li>
 * </ul>
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("A.1 — platform.delegate_task tool registration / spawn / approval gate")
class DelegateTaskToolIntegrationTest extends BaseIntegrationTest {

    @Autowired private PlatformToolProvider platformToolProvider;
    @Autowired private AgentApprovalGateService approvalGate;
    @Autowired private DynamicDataMapper dynamicDataMapper;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;
    private String policyPid;

    @BeforeEach
    void setup() {
        tenantId = 9_820_000L + System.nanoTime() % 100_000;
        MetaContext.setContext(tenantId, testUser.getId(), testUser.getPid(), testUser.getUserName());
    }

    @AfterEach
    void cleanup() {
        // Order: approvals → policies → child/parent runs → tasks. Keep tenant-scoped.
        jdbc.update("DELETE FROM ab_agent_approval WHERE tenant_id = ?", tenantId);
        if (policyPid != null) {
            jdbc.update("DELETE FROM ab_approval_policy WHERE tenant_id = ?", tenantId);
            policyPid = null;
        }
        jdbc.update("DELETE FROM ab_agent_run WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_agent_task WHERE tenant_id = ?", tenantId);
        StepContext.clearRunPid();
        MetaContext.clear();
    }

    /** Seed a running parent run + task pair the delegate_task can attach to. */
    private String seedParentRun() {
        String runPid = UniqueIdGenerator.generate();
        String taskPid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_task (pid, tenant_id, title, task_status, " +
                        " assignee_type, assignee_id, created_at, updated_at, created_by) " +
                        "VALUES (?, ?, 'parent for delegate_task', 'in_progress', 'agent', 'aurabot', " +
                        "        NOW(), NOW(), ?)",
                taskPid, tenantId, testUser.getId());
        jdbc.update("INSERT INTO ab_agent_run (pid, tenant_id, task_id, agent_id, run_status, " +
                        " started_at, created_at, updated_at, created_by) " +
                        "VALUES (?, ?, ?, 'aurabot', 'running', NOW(), NOW(), NOW(), ?)",
                runPid, tenantId, taskPid, testUser.getId());
        return runPid;
    }

    /** Seed an active approval policy that matches platform.delegate_task by glob pattern. */
    private void seedDelegateTaskPolicy() {
        policyPid = UniqueIdGenerator.generate();
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> row = new HashMap<>();
        row.put("pid", policyPid);
        row.put("tenant_id", tenantId);
        row.put("policy_name", "delegate-task-gate");
        row.put("trigger_rules", "[{\"type\":\"tool_call\",\"pattern\":\"platform.delegate_*\"}]");
        row.put("approver_rules", "[{\"type\":\"role\",\"roleCode\":\"APPROVER\"}]");
        row.put("timeout_hours", 12);
        row.put("timeout_action", "reject");
        row.put("policy_status", "active");
        row.put("deleted_flag", false);
        row.put("created_at", now);
        row.put("updated_at", now);
        int inserted = dynamicDataMapper.insert("ab_approval_policy", row);
        assertThat(inserted).as("approval policy inserted").isEqualTo(1);
    }

    // =========================================================================
    // C1 — Tool registration: discover() returns platform.delegate_task with
    // L2 + requiresApproval=true and the right parameter schema.
    // =========================================================================

    @Test
    @DisplayName("C1: discover() exposes platform.delegate_task as L2 + requiresApproval=true")
    void c1_tool_registration_contract() {
        ToolDiscoveryContext ctx = ToolDiscoveryContext.builder()
                .tenantId(tenantId)
                .userId(testUser.getId())
                .agentCode("aurabot")
                .build();

        List<ToolDefinition> tools = platformToolProvider.discover(ctx);
        ToolDefinition delegateTool = tools.stream()
                .filter(t -> "platform.delegate_task".equals(t.getToolCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "platform.delegate_task missing from PlatformToolProvider.discover()"));

        assertThat(delegateTool.getRiskLevel()).isEqualTo("L2");
        assertThat(delegateTool.isRequiresApproval()).isTrue();
        assertThat(delegateTool.getConfirmationPolicy()).isEqualTo("approval_required");
        assertThat(delegateTool.getProviderCode()).isEqualTo("platform");
        assertThat(delegateTool.getSourceCode()).isEqualTo("platform.delegate_task");

        // Parameter schema must declare subtaskMessage as required so the LLM
        // cannot omit it without a structured error from the tool side.
        Map<String, Object> schema = delegateTool.getParameterSchema();
        assertThat(schema).isNotNull();
        assertThat(schema.get("required"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .contains("subtaskMessage");
        assertThat(schema.get("properties"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsKey("subtaskMessage")
                .containsKey("agentCode");
    }

    // =========================================================================
    // C2 — Approval gate produces an approval row when invoked with the
    // delegate_task tool code: the toolRequiresApproval=true flag + matching
    // policy combine to write a real ab_agent_approval row, NOT fail-open.
    // This proves the gate sees platform.delegate_task as approval-gated.
    // =========================================================================

    @Test
    @DisplayName("C2: AgentApprovalGate creates approval row for platform.delegate_task (L2 + matching policy)")
    void c2_approval_gate_creates_row() {
        seedDelegateTaskPolicy();

        // Simulate the call ToolLoopService makes when it sees a tool with
        // requiresApproval=true. The exact arguments mirror the production path
        // (run/task pids, request_data carrying the tool params).
        String runPid = UniqueIdGenerator.generate();
        String taskPid = UniqueIdGenerator.generate();
        Map<String, Object> requestData = Map.of(
                "subtaskMessage", "Look up the supplier list for vendor A",
                "agentCode", "aurabot");

        String approvalPid = approvalGate.checkAndRequestApproval(
                tenantId, runPid, taskPid,
                "platform.delegate_task",
                "Delegate Subtask to Sub-Agent",
                requestData,
                /* toolRequiresApproval */ true);

        assertThat(approvalPid)
                .as("approval gate must create a real approval row when policy matches " +
                        "and tool requires approval")
                .isNotNull();

        Map<String, Object> approval = jdbc.queryForMap(
                "SELECT pid, tenant_id, run_id, task_id, approval_type, approval_status, " +
                        " policy_id, plan_hash FROM ab_agent_approval WHERE pid = ?",
                approvalPid);
        assertThat(approval.get("policy_id"))
                .as("approval row must be linked to the matching policy " +
                        "(fail-secure invariant from ApprovalGateP0FixIntegrationTest)")
                .isEqualTo(policyPid);
        assertThat(approval.get("approval_type")).isEqualTo("tool_call");
        assertThat(approval.get("approval_status")).isEqualTo("pending");
        assertThat(approval.get("run_id")).isEqualTo(runPid);
        assertThat(approval.get("task_id")).isEqualTo(taskPid);
        assertThat(approval.get("plan_hash"))
                .as("plan_hash must be populated for post-approval tampering protection")
                .isNotNull();
    }

    // =========================================================================
    // C3 — Happy path execute(): with parent run pre-seeded and StepContext
    // bound, the tool spawns a real child run with subtask_origin='delegate_task'
    // and returns childRunPid in the data envelope.
    // =========================================================================

    @Test
    @DisplayName("C3: execute() spawns a real child run with subtask_origin='delegate_task'")
    void c3_execute_spawns_child_run() {
        String parentRunPid = seedParentRun();
        StepContext.setRunPid(parentRunPid);

        Map<String, Object> params = Map.of(
                "subtaskMessage", "Update vendor metadata for ACME");

        ProviderExecutionResult result = platformToolProvider.execute(
                tenantId, "platform.delegate_task", params);

        assertThat(result.isSuccess()).as("delegate_task execute must report success").isTrue();
        assertThat(result.getData()).isNotNull();
        assertThat(result.getData())
                .containsEntry("origin", "delegate_task")
                .containsEntry("success", true);
        String childRunPid = (String) result.getData().get("childRunPid");
        String childTaskPid = (String) result.getData().get("childTaskPid");
        assertThat(childRunPid).as("childRunPid surfaced in tool data").isNotBlank();
        assertThat(childTaskPid).as("childTaskPid surfaced in tool data").isNotBlank();

        // Verify the row really lives in DB with the right shape.
        Map<String, Object> child = jdbc.queryForMap(
                "SELECT pid, parent_run_id, subtask_origin, run_status, agent_id, tenant_id, task_id " +
                        "FROM ab_agent_run WHERE pid = ?", childRunPid);
        assertThat(child.get("parent_run_id")).isEqualTo(parentRunPid);
        assertThat(child.get("subtask_origin")).isEqualTo("delegate_task");
        assertThat(child.get("run_status")).isEqualTo("running");
        assertThat(child.get("agent_id")).isEqualTo("aurabot");
        assertThat(((Number) child.get("tenant_id")).longValue()).isEqualTo(tenantId);
        assertThat(child.get("task_id")).isEqualTo(childTaskPid);
    }

    // =========================================================================
    // C4 — Missing subtaskMessage: structured error (success=false), NO child
    // run / task rows created.
    // =========================================================================

    @Test
    @DisplayName("C4: missing subtaskMessage → structured error, no child run created")
    void c4_missing_subtask_message_returns_error() {
        String parentRunPid = seedParentRun();
        StepContext.setRunPid(parentRunPid);

        Integer runsBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_run WHERE tenant_id = ?",
                Integer.class, tenantId);
        Integer tasksBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_task WHERE tenant_id = ?",
                Integer.class, tenantId);

        ProviderExecutionResult result = platformToolProvider.execute(
                tenantId, "platform.delegate_task", Map.of(/* no subtaskMessage */));

        assertThat(result.isSuccess()).as("execute must report failure on missing input").isFalse();
        assertThat(result.getData()).isNotNull();
        assertThat(result.getData().get("success")).isEqualTo(false);
        assertThat((String) result.getData().get("error"))
                .containsIgnoringCase("subtaskMessage");

        Integer runsAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_run WHERE tenant_id = ?",
                Integer.class, tenantId);
        Integer tasksAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_task WHERE tenant_id = ?",
                Integer.class, tenantId);
        assertThat(runsAfter).as("no child run created on validation failure").isEqualTo(runsBefore);
        assertThat(tasksAfter).as("no child task created on validation failure").isEqualTo(tasksBefore);
    }

    // =========================================================================
    // C5 — No parent run on the thread (StepContext unset): structured error
    // explaining the tool can only run inside an active run.
    // =========================================================================

    @Test
    @DisplayName("C5: no StepContext.runPid → structured error 'inside an active run', no spawn")
    void c5_no_parent_run_returns_error() {
        // Intentionally do NOT call StepContext.setRunPid — mimics an ad-hoc
        // tool invocation outside the loop.
        StepContext.clearRunPid();

        Integer runsBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_run WHERE tenant_id = ?",
                Integer.class, tenantId);

        ProviderExecutionResult result = platformToolProvider.execute(
                tenantId, "platform.delegate_task",
                Map.of("subtaskMessage", "side task"));

        assertThat(result.isSuccess()).isFalse();
        assertThat((String) result.getData().get("error"))
                .containsIgnoringCase("active run");

        Integer runsAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_run WHERE tenant_id = ?",
                Integer.class, tenantId);
        assertThat(runsAfter).isEqualTo(runsBefore);
    }
}
