package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.dto.BusinessIntentFrame;
import com.auraboot.framework.agent.dto.ConfidenceScore;
import com.auraboot.framework.agent.service.BifContext;
import com.auraboot.framework.agent.service.ToolLoopService;
import com.auraboot.framework.agent.trace.TraceContext;
import com.auraboot.framework.application.tenant.MetaContext;
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

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: when BIF.riskLevel ≥ L3 and the tool is a write,
 * ToolLoopService must route through Approval Gate even when the
 * tool definition doesn't set requiresApproval.
 *
 * Covers the D1 Grounding → Approval Gate closed loop (spec §5.1).
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("ACP — BIF risk auto-escalates to Approval Gate")
class BifRiskApprovalIntegrationTest extends BaseIntegrationTest {

    @Autowired private ToolLoopService toolLoopService;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;
    private String policyPid;

    @BeforeEach
    void setup() {
        tenantId = 9_9001L + System.nanoTime() % 1000;
        MetaContext.setContext(tenantId, testUser.getId(), testUser.getPid(), testUser.getUserName());
        // Seed a policy that matches any cmd_* tool so the gate accepts the request.
        policyPid = UniqueIdGenerator.generate();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO ab_approval_policy " +
                        "(pid, tenant_id, policy_name, trigger_rules, approver_rules, " +
                        " timeout_hours, timeout_action, policy_status, deleted_flag, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?::jsonb, ?::jsonb, 12, 'reject', 'active', false, ?, ?)",
                policyPid, tenantId, "bif-risk-test-policy",
                "[{\"type\":\"tool_call\",\"pattern\":\"cmd_*\"}]",
                "[{\"type\":\"role\",\"roleCode\":\"APPROVER\"}]",
                now, now);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_approval WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_approval_policy WHERE tenant_id = ?", tenantId);
        BifContext.clear();
    }

    private AgentToolDefinition buildCmdTool(String code, boolean requiresApproval) {
        AgentToolDefinition t = new AgentToolDefinition();
        t.setName("cmd_" + code);
        t.setSourceCode(code);
        t.setDescription("test cmd");
        t.setToolType("dsl_command");
        t.setRequiresApproval(requiresApproval);
        t.setRiskLevel("L1");
        return t;
    }

    private void setBif(String riskLevel) {
        BusinessIntentFrame bif = BusinessIntentFrame.builder()
                .intent("delete")
                .object("crm_account")
                .riskLevel(riskLevel)
                .actionability("execute")
                .confidence(ConfidenceScore.of(0.9, 0.9))
                .candidateSkillsMode("fixed")
                .build();
        BifContext.setCurrentBif(bif);
    }

    @Test
    @DisplayName("BIF riskLevel=L3 + write tool + requiresApproval=false → escalated to approval")
    void l3_risk_escalates_write_tool() {
        String runPid = UniqueIdGenerator.generate();
        AgentToolDefinition tool = buildCmdTool("fake_delete", false);

        setBif("L3");

        String result = toolLoopService.executeToolCall(tenantId, runPid, null, "aurabot",
                tool.getName(), Map.of("arg", 1), List.of(tool), null);

        assertThat(result)
                .as("L3 risk must force approval path (tool never actually executed)")
                .contains("requires human approval");

        Integer approvalCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_approval WHERE tenant_id = ? AND run_id = ?",
                Integer.class, tenantId, runPid);
        assertThat(approvalCount).as("an approval row must be created").isEqualTo(1);
    }

    @Test
    @DisplayName("BIF riskLevel=L0 + write tool + requiresApproval=false → no escalation")
    void low_risk_does_not_escalate() {
        String runPid = UniqueIdGenerator.generate();
        AgentToolDefinition tool = buildCmdTool("fake_update", false);

        setBif("L0");

        String result = toolLoopService.executeToolCall(tenantId, runPid, null, "aurabot",
                tool.getName(), Map.of("arg", 1), List.of(tool), null);

        // With no BIF escalation the call falls through to real execution, which fails
        // (no such command) — the important invariant is that NO approval row got created.
        assertThat(result).as("result is whatever the real executor returned").isNotNull();

        Integer approvalCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_approval WHERE tenant_id = ? AND run_id = ?",
                Integer.class, tenantId, runPid);
        assertThat(approvalCount).as("low risk must not create approval").isEqualTo(0);
    }

    @Test
    @DisplayName("BIF riskLevel=L4 + read tool → no escalation (read is always safe)")
    void high_risk_read_not_escalated() {
        String runPid = UniqueIdGenerator.generate();
        AgentToolDefinition tool = new AgentToolDefinition();
        tool.setName("nq_customer_list");
        tool.setSourceCode("customer_list");
        tool.setDescription("list customers");
        tool.setToolType("dsl_query");
        tool.setRequiresApproval(false);
        tool.setRiskLevel("L0");

        setBif("L4");

        String result = toolLoopService.executeToolCall(tenantId, runPid, null, "aurabot",
                tool.getName(), Map.of(), List.of(tool), null);

        assertThat(result)
                .as("read tool at L4 should not hit approval gate")
                .doesNotContain("requires human approval");

        Integer approvalCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_approval WHERE tenant_id = ? AND run_id = ?",
                Integer.class, tenantId, runPid);
        assertThat(approvalCount).isEqualTo(0);
    }

    @Test
    @DisplayName("no BIF in context → behavior falls back to per-tool requiresApproval flag")
    void no_bif_falls_back_to_tool_flag() {
        String runPid = UniqueIdGenerator.generate();
        AgentToolDefinition tool = buildCmdTool("fake_update", false);
        BifContext.clear();

        String result = toolLoopService.executeToolCall(tenantId, runPid, null, "aurabot",
                tool.getName(), Map.of("arg", 1), List.of(tool), null);
        assertThat(result).isNotNull();

        Integer approvalCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_approval WHERE tenant_id = ? AND run_id = ?",
                Integer.class, tenantId, runPid);
        assertThat(approvalCount)
                .as("without BIF, requiresApproval=false means no approval created")
                .isEqualTo(0);
    }
}
