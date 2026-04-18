package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.service.AgentApprovalGateService;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the 3 P0 Approval Gate fixes:
 *   (a) policy_id linkage — approvals must carry policy_id; fail-secure when missing
 *   (b) approver fail-secure — three fail-open branches now deny instead of allow
 *   (c) plan_hash integrity — tampering with request_data after creation blocks approve()
 *   (d) bonus: schedule-triggered runs respect agent-level policies, not just tool-level
 */
// @Commit on each test method: @Transactional + @Rollback(true) from the parent
// would isolate the parent's test data from MyBatis in some environments (we observed
// that DynamicDataMapper.insert + selectByQuery on the same bean do not share
// visibility within a rollback-only tx). We commit explicitly and clean up in @AfterEach.
@Commit
class ApprovalGateP0FixIntegrationTest extends BaseIntegrationTest {

    @Autowired private AgentApprovalGateService gate;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private DynamicDataMapper dynamicDataMapper;

    private Long tenantId;
    private String policyPid;

    @BeforeEach
    void setup() {
        tenantId = 9_9001L + System.nanoTime() % 1000;
        // TenantLineInterceptor auto-injects tenant_id = MetaContext.tenantId into mapper
        // queries. BaseIntegrationTest set MetaContext to testTenant.getId(), which differs
        // from our random tenantId — re-bind MetaContext so the production code under test
        // reads our test-scoped tenant rows.
        MetaContext.setContext(tenantId, testUser.getId(), testUser.getPid(), testUser.getUserName());
        policyPid = UniqueIdGenerator.generate();
        insertPolicy(policyPid, "test-policy",
                "[{\"type\":\"tool_call\",\"pattern\":\"cmd_*\"}]",
                "[{\"type\":\"role\",\"roleCode\":\"APPROVER\"}]");
    }

    @AfterEach
    void cleanupByTenant() {
        // Tests use a unique random tenantId so DELETE WHERE tenant_id = ? is sufficient
        // and does not touch the shared test fixture (testTenant).
        jdbc.update("DELETE FROM ab_agent_approval WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_approval_policy WHERE tenant_id = ?", tenantId);
    }

    /**
     * Insert a policy using the same mapper the service reads from, ensuring visibility
     * within the test transaction. JdbcTemplate and MyBatis may not share session state
     * in all configurations; we go through DynamicDataMapper to be safe.
     */
    private void insertPolicy(String pid, String name, String triggerRules, String approverRules) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> row = new HashMap<>();
        row.put("pid", pid);
        row.put("tenant_id", tenantId);
        row.put("policy_name", name);
        row.put("trigger_rules", triggerRules);
        row.put("approver_rules", approverRules);
        row.put("timeout_hours", 12);
        row.put("timeout_action", "reject");
        row.put("policy_status", "active");
        row.put("deleted_flag", false);
        row.put("created_at", now);
        row.put("updated_at", now);
        int inserted = dynamicDataMapper.insert("ab_approval_policy", row);
        if (inserted != 1) {
            throw new IllegalStateException("insertPolicy failed: inserted=" + inserted);
        }
    }

    private String createApproval(boolean toolRequiresApproval, Map<String, Object> requestData,
                                   String toolCode) throws Exception {
        String runPid = UniqueIdGenerator.generate();
        return gate.checkAndRequestApproval(tenantId, runPid, null,
                toolCode, "test tool", requestData, toolRequiresApproval);
    }

    @Nested
    @DisplayName("P0a: policy_id linkage — checkAndRequestApproval must set policy_id")
    class PolicyLinkage {

        @Test
        @DisplayName("matched policy is persisted as policy_id")
        void matched_policy_persisted() throws Exception {
            String pid = createApproval(true, Map.of("arg", 1), "cmd_test");
            assertThat(pid).as("createApproval returned non-null (policy matched)").isNotNull();

            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT policy_id, plan_hash, plan_snapshot FROM ab_agent_approval WHERE pid = ?",
                    pid);
            assertThat(row.get("policy_id")).isEqualTo(policyPid);
            assertThat(row.get("plan_hash")).isNotNull();
            assertThat(row.get("plan_snapshot")).isNotNull();
        }

        @Test
        @DisplayName("toolRequiresApproval=true with no matching policy: fail-secure, return null")
        void no_matching_policy_fails_secure() throws Exception {
            // Tool name that matches nothing in our test policy's trigger_rules
            String pid = createApproval(true, Map.of("arg", 1), "unmatched_tool_xyz");
            assertThat(pid)
                    .as("P0 fix: refuse to create approval when policy missing; caller will fail the run")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("P0b: isAuthorizedApprover — fail-secure for all three former fail-open branches")
    class ApproverFailSecure {

        private String insertApproval(String overridePolicyId) {
            String pid = UniqueIdGenerator.generate();
            LocalDateTime now = LocalDateTime.now();
            Map<String, Object> row = new HashMap<>();
            row.put("pid", pid);
            row.put("tenant_id", tenantId);
            row.put("approval_type", "tool_call");
            row.put("approval_title", "test");
            row.put("approval_status", "pending");
            row.put("policy_id", overridePolicyId);
            row.put("plan_hash", "dummy-hash");
            row.put("request_data", "{}");
            row.put("created_at", now);
            row.put("updated_at", now);
            dynamicDataMapper.insert("ab_agent_approval", row);
            return pid;
        }

        @Test
        @DisplayName("approval with policy_id = NULL → DENY (was fail-open)")
        void null_policy_id_denies() {
            String pid = insertApproval(null);
            assertThat(gate.isAuthorizedApprover(tenantId, pid, 42L)).isFalse();
        }

        @Test
        @DisplayName("approval pointing to deleted/missing policy → DENY (was fail-open)")
        void missing_policy_denies() {
            String pid = insertApproval("non-existent-policy-pid");
            assertThat(gate.isAuthorizedApprover(tenantId, pid, 42L)).isFalse();
        }

        @Test
        @DisplayName("policy exists but approver_rules is empty array → DENY (was fail-open)")
        void empty_approver_rules_denies() {
            String emptyPolicyPid = UniqueIdGenerator.generate();
            // approver_rules column is NOT NULL at runtime; empty JSON array validates
            // the branch "parsed rules list is empty → deny".
            insertPolicy(emptyPolicyPid, "empty-rules-policy",
                    "[{\"type\":\"tool_call\",\"pattern\":\"*\"}]",
                    "[]");

            String pid = insertApproval(emptyPolicyPid);
            assertThat(gate.isAuthorizedApprover(tenantId, pid, 42L)).isFalse();
        }
    }

    @Nested
    @DisplayName("P0c: plan_hash integrity — tampered request_data blocks approve()")
    class PlanIntegrity {

        @Test
        @DisplayName("unmodified approval: validatePlanIntegrity returns true")
        void clean_integrity_passes() throws Exception {
            String pid = createApproval(true, Map.of("arg", 1), "cmd_test");
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT * FROM ab_agent_approval WHERE pid = ?", pid);
            assertThat(gate.validatePlanIntegrity(row)).isTrue();
        }

        @Test
        @DisplayName("tampered request_data: validatePlanIntegrity returns false")
        void tampered_integrity_fails() throws Exception {
            String pid = createApproval(true, Map.of("arg", 1), "cmd_test");

            // Simulate direct DB tampering of request_data after approval creation
            jdbc.update("UPDATE ab_agent_approval SET request_data = ? WHERE pid = ?",
                    "{\"arg\":\"MALICIOUS\"}", pid);

            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT * FROM ab_agent_approval WHERE pid = ?", pid);
            assertThat(gate.validatePlanIntegrity(row)).isFalse();
        }

        @Test
        @DisplayName("approve() on tampered approval throws and marks row rejected")
        void approve_rejects_tampered() throws Exception {
            String pid = createApproval(true, Map.of("arg", 1), "cmd_test");
            jdbc.update("UPDATE ab_agent_approval SET request_data = ? WHERE pid = ?",
                    "{\"arg\":\"MALICIOUS\"}", pid);

            assertThatThrownBy(() -> gate.approve(tenantId, pid, 42L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("plan_hash mismatch");

            String status = jdbc.queryForObject(
                    "SELECT approval_status FROM ab_agent_approval WHERE pid = ?",
                    String.class, pid);
            assertThat(status).isEqualTo("rejected");

            String reason = jdbc.queryForObject(
                    "SELECT rejection_reason FROM ab_agent_approval WHERE pid = ?",
                    String.class, pid);
            assertThat(reason).isEqualTo("plan_integrity_violation");
        }
    }

    @Nested
    @DisplayName("P0d: agent-level policy gate for scheduled runs")
    class AgentLevelPolicyGate {

        @Test
        @DisplayName("agentHasMatchingPolicy returns true when agent_code pattern matches")
        void agent_level_policy_matches() {
            String agentPolicyPid = UniqueIdGenerator.generate();
            insertPolicy(agentPolicyPid, "agent-policy",
                    "[{\"type\":\"agent_code\",\"pattern\":\"crm_*\"}]",
                    "[{\"type\":\"role\",\"roleCode\":\"APPROVER\"}]");

            assertThat(gate.agentHasMatchingPolicy(tenantId, "crm_sales_agent")).isTrue();
            assertThat(gate.agentHasMatchingPolicy(tenantId, "finance_agent")).isFalse();
        }

        @Test
        @DisplayName("no agent-level policy configured → returns false")
        void no_agent_level_policy() {
            // Only the tool_call policy from setup() exists
            assertThat(gate.agentHasMatchingPolicy(tenantId, "any_agent")).isFalse();
        }
    }
}
