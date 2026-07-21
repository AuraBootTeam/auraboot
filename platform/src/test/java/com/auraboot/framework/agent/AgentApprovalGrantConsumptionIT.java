package com.auraboot.framework.agent;

import com.auraboot.framework.agent.service.AgentApprovalGateService;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F8 regression (execution-architecture review, 2026-07-20): approving a pending
 * request resumes the task as a <b>new run</b>, and the gate's idempotency key is
 * {@code {runId}:{toolCode}} — so the approved row was invisible to the resumed
 * run and the gate minted another pending approval, forever. The user approved,
 * nothing executed, and the audit trail filled with orphans.
 *
 * <p>The fix (a task-scoped, exactly-once grant claim) shipped without any test.
 * This pins the whole loop end to end on a real database, because the defect
 * lives in the interaction between two runs and a row — nothing a mock can show.
 *
 * <p>Every row is namespaced by a per-execution id so a re-run cannot pass by
 * inheriting the previous run's rows (G-test-hermetic-1).
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("F8: an approval grant authorizes the resumed run exactly once")
class AgentApprovalGrantConsumptionIT extends BaseIntegrationTest {

    private static final String TOOL_CODE = "cmd_crm_delete_account";

    @Autowired
    private AgentApprovalGateService approvalGateService;

    @Autowired
    private DynamicDataMapper dynamicDataMapper;

    /** Unique per execution: rows from an earlier run must never satisfy this one. */
    private final String runTag = UniqueIdGenerator.generate();
    private String policyPid;

    @BeforeEach
    void seedPolicy() {
        Long tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();
        policyPid = UniqueIdGenerator.generate();
        Map<String, Object> policy = new HashMap<>();
        policy.put("pid", policyPid);
        policy.put("tenant_id", tenantId);
        policy.put("policy_name", "f8-grant-policy-" + runTag);
        policy.put("description", "Seeded by AgentApprovalGrantConsumptionIT");
        policy.put("trigger_rules", "[{\"type\":\"tool_call\",\"pattern\":\"" + TOOL_CODE + "\"}]");
        policy.put("approver_rules", "[{\"type\":\"USER\",\"userId\":" + userId + "}]");
        policy.put("auto_approve", false);
        policy.put("timeout_hours", 24);
        policy.put("timeout_action", "reject");
        policy.put("policy_status", "active");
        policy.put("deleted_flag", false);
        policy.put("created_at", LocalDateTime.now());
        policy.put("updated_at", LocalDateTime.now());
        dynamicDataMapper.insert("ab_approval_policy", policy);
    }

    @AfterEach
    void cleanup() {
        Long tenantId = getTestTenant().getId();
        dynamicDataMapper.deleteByQuery(
                "DELETE FROM ab_agent_approval WHERE tenant_id = #{params.tenantId} "
                        + "AND task_id LIKE #{params.taskLike}",
                Map.of("tenantId", tenantId, "taskLike", "task-f8-" + runTag + "%"));
        dynamicDataMapper.deleteByQuery(
                "DELETE FROM ab_approval_policy WHERE pid = #{params.pid}",
                Map.of("pid", policyPid));
        dynamicDataMapper.deleteByQuery(
                "DELETE FROM ab_agent_approval_notification_outbox WHERE tenant_id = #{params.tenantId}",
                Map.of("tenantId", tenantId));
    }

    @Test
    @DisplayName("approving once lets the resumed run execute, and only once")
    void approvedGrantAuthorizesResumedRunExactlyOnce() {
        Long tenantId = getTestTenant().getId();
        Long approverId = getTestUser().getId();
        String taskId = "task-f8-" + runTag;

        // 1. First run hits the gate and suspends.
        String pendingPid = approvalGateService.checkAndRequestApproval(
                tenantId, "run-" + runTag + "-a", taskId,
                TOOL_CODE, "Delete account", Map.of("recordPid", "acc-1"), true);
        assertThat(pendingPid)
                .as("the gate must suspend the first run")
                .isNotNull();

        // 2. A human approves it.
        approvalGateService.approve(tenantId, pendingPid, approverId);
        assertThat(approvalGateService.isApproved(pendingPid)).isTrue();

        // 3. Resume opens a NEW run id — this is the exact hop the bug lived in.
        //    null here means "authorized, proceed"; a pid would mean the gate asked
        //    for approval a second time, which is the infinite loop.
        String secondPass = approvalGateService.checkAndRequestApproval(
                tenantId, "run-" + runTag + "-b", taskId,
                TOOL_CODE, "Delete account", Map.of("recordPid", "acc-1"), true);
        assertThat(secondPass)
                .as("an approved grant must authorize the resumed run, not re-suspend it")
                .isNull();

        // 4. The grant is stamped consumed, so it cannot authorize anything again.
        assertThat(consumedAt(pendingPid))
                .as("the claimed grant must be stamped consumed")
                .isNotNull();

        // 5. A third run on the same task+tool is a NEW action and must be gated
        //    again — one approval authorizes one execution, not a standing licence.
        String thirdPass = approvalGateService.checkAndRequestApproval(
                tenantId, "run-" + runTag + "-c", taskId,
                TOOL_CODE, "Delete account", Map.of("recordPid", "acc-1"), true);
        assertThat(thirdPass)
                .as("a consumed grant must not authorize a further execution")
                .isNotNull()
                .isNotEqualTo(pendingPid);
    }

    @Test
    @DisplayName("a pending (unapproved) grant does not authorize a resumed run")
    void pendingGrantDoesNotAuthorize() {
        Long tenantId = getTestTenant().getId();
        String taskId = "task-f8-" + runTag + "-pending";

        String pendingPid = approvalGateService.checkAndRequestApproval(
                tenantId, "run-" + runTag + "-p1", taskId,
                TOOL_CODE, "Delete account", Map.of("recordPid", "acc-9"), true);
        assertThat(pendingPid).isNotNull();

        // Nobody approved. The next run must still be gated — if this ever returns
        // null the claim predicate has stopped checking approval_status.
        String secondPass = approvalGateService.checkAndRequestApproval(
                tenantId, "run-" + runTag + "-p2", taskId,
                TOOL_CODE, "Delete account", Map.of("recordPid", "acc-9"), true);
        assertThat(secondPass)
                .as("an unapproved request must never authorize execution")
                .isNotNull();
        assertThat(consumedAt(pendingPid))
                .as("an unapproved row must not be consumed")
                .isNull();
    }

    @Test
    @DisplayName("a grant for another tool on the same task does not authorize this tool")
    void grantIsScopedToItsTool() {
        Long tenantId = getTestTenant().getId();
        Long approverId = getTestUser().getId();
        String taskId = "task-f8-" + runTag + "-scope";

        String pendingPid = approvalGateService.checkAndRequestApproval(
                tenantId, "run-" + runTag + "-s1", taskId,
                TOOL_CODE, "Delete account", Map.of("recordPid", "acc-7"), true);
        assertThat(pendingPid).isNotNull();
        approvalGateService.approve(tenantId, pendingPid, approverId);

        // Same task, DIFFERENT tool: approving a delete must not silently authorize
        // an export. requiresApproval=true with no matching policy fails secure
        // (null), so assert on the grant instead: it must still be unconsumed.
        approvalGateService.checkAndRequestApproval(
                tenantId, "run-" + runTag + "-s2", taskId,
                "cmd_crm_export_account", "Export account", Map.of("recordPid", "acc-7"), true);
        assertThat(consumedAt(pendingPid))
                .as("another tool's call must not consume this tool's grant")
                .isNull();
    }

    /**
     * Selects pid alongside consumed_at deliberately: a projection of only a NULL
     * column comes back as a null row map, which would read as "no such approval"
     * and make the assertion pass for the wrong reason.
     */
    private Object consumedAt(String approvalPid) {
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(
                "SELECT pid, consumed_at FROM ab_agent_approval WHERE pid = #{params.pid}",
                Map.of("pid", approvalPid));
        assertThat(rows).as("approval row must exist").hasSize(1);
        return rows.get(0).get("consumed_at");
    }
}
