package com.auraboot.framework.agent;

import com.auraboot.framework.agent.service.AgentApprovalGateService;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for AgentApprovalGateService.
 * Covers approval creation, status transitions (PENDING → APPROVED / REJECTED),
 * and edge cases (non-existent approval, double-state-transition).
 * Uses real database, no mocking. Data persists (no rollback).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class AgentApprovalGateIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AgentApprovalGateService approvalGateService;

    @Autowired
    private DynamicDataMapper dynamicDataMapper;

    private String approvalPid1;
    private String approvalPid2;
    private final String testRunId = String.valueOf(System.currentTimeMillis());

    // ========== Test 1: checkAndRequestApproval - tool requires approval ==========

    @Test
    @Order(1)
    void checkAndRequestApproval_toolRequiresApproval_createsApprovalRecord() {
        Long tenantId = getTestTenant().getId();
        String runId = "run-" + testRunId + "-001";
        String taskId = "task-001";

        String result = approvalGateService.checkAndRequestApproval(
                tenantId, runId, taskId,
                "file_delete", "Delete file /tmp/test",
                Map.of("path", "/tmp/test"),
                true);

        assertNotNull(result, "Should return an approvalPid when toolRequiresApproval=true");
        approvalPid1 = result;
    }

    // ========== Test 2: checkAndRequestApproval - tool does not require approval ==========

    @Test
    @Order(2)
    void checkAndRequestApproval_toolNoApproval_returnsNull() {
        Long tenantId = getTestTenant().getId();
        String runId = "run-" + testRunId + "-no-approval";
        String taskId = "task-no-approval";

        String result = approvalGateService.checkAndRequestApproval(
                tenantId, runId, taskId,
                "read_file", "Read file /tmp/safe",
                Map.of(),
                false);

        assertNull(result, "Should return null when toolRequiresApproval=false and no policy matches");
    }

    // ========== Test 3: isApproved - PENDING approval returns false ==========

    @Test
    @Order(3)
    void isApproved_pendingApproval_returnsFalse() {
        assertNotNull(approvalPid1, "approvalPid1 must be set by test 1");

        boolean approved = approvalGateService.isApproved(approvalPid1);

        assertFalse(approved, "A PENDING approval should not be considered approved");
    }

    // ========== Test 4: approve - updates status to APPROVED ==========

    @Test
    @Order(4)
    void approve_validApproval_updatesStatusToApproved() {
        Long tenantId = getTestTenant().getId();

        // Create a fresh approval so it is still PENDING
        approvalPid2 = approvalGateService.checkAndRequestApproval(
                tenantId,
                "run-" + testRunId + "-002",
                "task-002",
                "code_execute", "Execute code",
                Map.of("code", "print('hello')"),
                true);
        assertNotNull(approvalPid2, "Fresh approval pid should be created");

        Map<String, Object> result = approvalGateService.approve(
                tenantId, approvalPid2, getTestUser().getId());

        assertNotNull(result, "approve() should return the updated approval record");
        assertTrue(approvalGateService.isApproved(approvalPid2),
                "isApproved() should return true after approval");
    }

    // ========== Test 5: approve - returned map contains expected fields ==========

    @Test
    @Order(5)
    void approve_returnsApproverDataAndPayload() {
        Long tenantId = getTestTenant().getId();

        // Create another fresh approval
        String pid3 = approvalGateService.checkAndRequestApproval(
                tenantId,
                "run-" + testRunId + "-003",
                "task-003",
                "api_call", "Call external API",
                Map.of("url", "https://example.com"),
                true);
        assertNotNull(pid3, "Fresh approval pid3 should be created");

        Map<String, Object> result = approvalGateService.approve(
                tenantId, pid3, getTestUser().getId());

        assertNotNull(result, "approve() should not return null for a valid PENDING approval");
        assertEquals("approved", result.get("approval_status"),
                "Returned map must reflect the new approval_status");
    }

    // ========== Test 6: reject - updates status to REJECTED ==========

    @Test
    @Order(6)
    void reject_withReason_updatesStatusToRejected() {
        Long tenantId = getTestTenant().getId();

        String pid4 = approvalGateService.checkAndRequestApproval(
                tenantId,
                "run-" + testRunId + "-004",
                "task-004",
                "db_drop", "Drop database",
                Map.of(),
                true);
        assertNotNull(pid4, "Fresh approval pid4 should be created");

        Map<String, Object> result = approvalGateService.reject(
                tenantId, pid4, getTestUser().getId(), "Too risky operation");

        assertNotNull(result, "reject() should return the updated approval record");
        assertEquals("rejected", result.get("approval_status"),
                "Returned map must reflect the REJECTED status");
        assertFalse(approvalGateService.isApproved(pid4),
                "isApproved() should return false for a REJECTED approval");
    }

    // ========== Test 7: approve - non-existent approval returns null ==========

    @Test
    @Order(7)
    void approve_nonExistentApproval_returnsNull() {
        Long tenantId = getTestTenant().getId();
        String nonExistentPid = "nonexistent-pid-" + testRunId;

        Map<String, Object> result = approvalGateService.approve(
                tenantId, nonExistentPid, getTestUser().getId());

        assertNull(result, "approve() should return null when approval is not found");
    }

    // ========== Test 8: reject - already-approved approval returns null ==========

    @Test
    @Order(8)
    void reject_alreadyApproved_returnsNull() {
        Long tenantId = getTestTenant().getId();
        assertNotNull(approvalPid2,
                "approvalPid2 must have been approved in test 4");

        // approvalPid2 was approved in test 4; loadPendingApproval only finds PENDING records
        Map<String, Object> result = approvalGateService.reject(
                tenantId, approvalPid2, getTestUser().getId(), "Late reject");

        assertNull(result,
                "reject() should return null when approval is not in PENDING state (already APPROVED)");
    }

    // ========== Test 9: expires_at is set on approval creation (TIMEOUT FIX) ==========

    @Test
    @Order(9)
    void checkAndRequestApproval_expiresAtIsSetAndNonNull() {
        Long tenantId = getTestTenant().getId();
        String runId = "run-" + testRunId + "-expiry";
        String taskId = "task-expiry";

        LocalDateTime before = LocalDateTime.now();

        String pid = approvalGateService.checkAndRequestApproval(
                tenantId, runId, taskId,
                "dangerous_tool", "Dangerous operation",
                Map.of(),
                true);

        assertNotNull(pid, "Approval PID must be returned");

        // Load the raw DB record to check expires_at
        String sql = "SELECT expires_at, auto_action FROM ab_agent_approval WHERE pid = #{params.pid}";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQueryWithoutTenant(
                sql, Map.of("pid", pid));

        assertThat(rows).isNotEmpty();
        Map<String, Object> row = rows.get(0);

        // expires_at must NOT be null
        Object expiresAtRaw = row.get("expires_at");
        assertThat(expiresAtRaw)
                .as("expires_at must not be NULL — timeout enforcement depends on it")
                .isNotNull();

        // expires_at must be approximately now + 24 hours (default timeout)
        // MyBatis returns timestamp-with-timezone columns as java.sql.Timestamp
        LocalDateTime expiresAt = ((java.sql.Timestamp) expiresAtRaw).toLocalDateTime();
        LocalDateTime expectedMin = before.plusHours(23).plusMinutes(59);
        LocalDateTime expectedMax = LocalDateTime.now().plusHours(24).plusMinutes(1);
        assertThat(expiresAt)
                .as("expires_at should be approximately now + 24h (default timeout)")
                .isAfterOrEqualTo(expectedMin)
                .isBeforeOrEqualTo(expectedMax);

        // auto_action must default to REJECT
        assertThat(row.get("auto_action"))
                .as("auto_action must default to REJECT")
                .isEqualTo("reject");
    }

    // ========== Test 10: isAuthorizedApprover - no policy → any tenant user is authorized ==========

    @Test
    @Order(10)
    void isAuthorizedApprover_noPolicyLinked_anyUserAuthorized() {
        Long tenantId = getTestTenant().getId();

        String approvalPid = approvalGateService.checkAndRequestApproval(
                tenantId,
                "run-" + testRunId + "-perm-010",
                "task-perm-010",
                "file_read", "Read config file",
                Map.of("path", "/etc/config"),
                true);
        assertNotNull(approvalPid, "Approval PID must be created");

        boolean authorized = approvalGateService.isAuthorizedApprover(
                tenantId, approvalPid, getTestUser().getId());
        assertThat(authorized)
                .as("When no policy is linked, any tenant user should be authorized")
                .isTrue();
    }

    // ========== Test 11: isAuthorizedApprover - policy with USER rule matches correct user ==========

    @Test
    @Order(11)
    void isAuthorizedApprover_userRuleMatchesCurrentUser_authorized() {
        Long tenantId = getTestTenant().getId();
        Long authorizedUserId = getTestUser().getId();

        String policyPid = createApprovalPolicy(tenantId,
                "[{\"type\":\"USER\",\"userId\":" + authorizedUserId + "}]");
        String approvalPid = createApprovalWithPolicy(tenantId, policyPid,
                "run-" + testRunId + "-perm-011");

        boolean authorized = approvalGateService.isAuthorizedApprover(
                tenantId, approvalPid, authorizedUserId);
        assertThat(authorized)
                .as("USER rule matching the current userId must authorize the user")
                .isTrue();
    }

    // ========== Test 12: isAuthorizedApprover - policy with USER rule rejects different user ==========

    @Test
    @Order(12)
    void isAuthorizedApprover_userRuleForDifferentUser_unauthorized() {
        Long tenantId = getTestTenant().getId();
        Long authorizedUserId = getTestUser().getId();
        Long differentUserId = authorizedUserId + 99999L;

        String policyPid = createApprovalPolicy(tenantId,
                "[{\"type\":\"USER\",\"userId\":" + authorizedUserId + "}]");
        String approvalPid = createApprovalWithPolicy(tenantId, policyPid,
                "run-" + testRunId + "-perm-012");

        boolean authorized = approvalGateService.isAuthorizedApprover(
                tenantId, approvalPid, differentUserId);
        assertThat(authorized)
                .as("USER rule for a different userId must NOT authorize the requesting user")
                .isFalse();
    }

    // ========== Test 13: isAuthorizedApprover - policy with ROLE rule matches user's role ==========

    @Test
    @Order(13)
    void isAuthorizedApprover_roleRuleMatchesUserRole_authorized() {
        Long tenantId = getTestTenant().getId();
        String assignedRoleCode = getTestRole().getCode();

        String policyPid = createApprovalPolicy(tenantId,
                "[{\"type\":\"ROLE\",\"roleCode\":\"" + assignedRoleCode + "\"}]");
        String approvalPid = createApprovalWithPolicy(tenantId, policyPid,
                "run-" + testRunId + "-perm-013");

        boolean authorized = approvalGateService.isAuthorizedApprover(
                tenantId, approvalPid, getTestUser().getId());
        assertThat(authorized)
                .as("ROLE rule matching a role assigned to the user must authorize the user")
                .isTrue();
    }

    // ========== Test 14: isAuthorizedApprover - policy with ROLE rule rejects user without role ==========

    @Test
    @Order(14)
    void isAuthorizedApprover_roleRuleForUnassignedRole_unauthorized() {
        Long tenantId = getTestTenant().getId();

        String policyPid = createApprovalPolicy(tenantId,
                "[{\"type\":\"ROLE\",\"roleCode\":\"SUPER_ADMIN_ROLE_NOBODY_HAS\"}]");
        String approvalPid = createApprovalWithPolicy(tenantId, policyPid,
                "run-" + testRunId + "-perm-014");

        boolean authorized = approvalGateService.isAuthorizedApprover(
                tenantId, approvalPid, getTestUser().getId());
        assertThat(authorized)
                .as("ROLE rule for a role the user does not have must NOT authorize the user")
                .isFalse();
    }

    // ========== Test 15: isAuthorizedApprover - wrong tenant is rejected (tenant isolation) ==========

    @Test
    @Order(15)
    void isAuthorizedApprover_wrongTenant_unauthorized() {
        Long tenantId = getTestTenant().getId();

        String approvalPid = approvalGateService.checkAndRequestApproval(
                tenantId,
                "run-" + testRunId + "-perm-015",
                "task-perm-015",
                "file_write", "Write file",
                Map.of(),
                true);
        assertNotNull(approvalPid, "Approval PID must be created");

        Long wrongTenantId = tenantId + 99999L;
        boolean authorized = approvalGateService.isAuthorizedApprover(
                wrongTenantId, approvalPid, getTestUser().getId());
        assertThat(authorized)
                .as("Accessing an approval from a different tenant must be rejected (tenant isolation)")
                .isFalse();
    }

    // ========== Test 16: isAuthorizedApprover - empty approver_rules array allows any user ==========

    @Test
    @Order(16)
    void isAuthorizedApprover_emptyApproverRules_anyUserAuthorized() {
        Long tenantId = getTestTenant().getId();

        String policyPid = createApprovalPolicy(tenantId, "[]");
        String approvalPid = createApprovalWithPolicy(tenantId, policyPid,
                "run-" + testRunId + "-perm-016");

        boolean authorized = approvalGateService.isAuthorizedApprover(
                tenantId, approvalPid, getTestUser().getId());
        assertThat(authorized)
                .as("Empty approver_rules array should allow any authenticated user")
                .isTrue();
    }

    // ========== Test 17: idempotency - same runId+toolCode returns existing PID ==========

    @Test
    @Order(17)
    void checkAndRequestApproval_sameIdempotencyKey_returnsExistingPid() {
        Long tenantId = getTestTenant().getId();
        String runId = "run-idem-" + testRunId;
        String toolCode = "idempotent_tool";

        // First call creates the approval
        String firstPid = approvalGateService.checkAndRequestApproval(
                tenantId, runId, "task-idem-001",
                toolCode, "Idempotency test tool",
                Map.of("param", "value"),
                true);
        assertNotNull(firstPid, "First call must return a non-null approval PID");

        // Second call with the same runId+toolCode must return the same PID — no duplicate
        String secondPid = approvalGateService.checkAndRequestApproval(
                tenantId, runId, "task-idem-002",  // different taskId, same run+tool
                toolCode, "Idempotency test tool (retry)",
                Map.of("param", "other"),
                true);
        assertThat(secondPid)
                .as("Idempotent retry must return the same PID as the first call")
                .isEqualTo(firstPid);

        // Verify only one record exists in the database for this idempotency key
        String sql = "SELECT pid FROM ab_agent_approval " +
                "WHERE idempotency_key = #{params.key} AND tenant_id = #{params.tenantId}";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(
                sql, Map.of("key", runId + ":" + toolCode, "tenantId", tenantId));
        assertThat(rows)
                .as("Exactly one approval record must exist for the idempotency key")
                .hasSize(1);
    }

    // ========== Test 18: idempotency - idempotency_key is persisted in the database ==========

    @Test
    @Order(18)
    void checkAndRequestApproval_idempotencyKeyIsPersistedInDb() {
        Long tenantId = getTestTenant().getId();
        String runId = "run-key-persist-" + testRunId;
        String toolCode = "persist_key_tool";

        String pid = approvalGateService.checkAndRequestApproval(
                tenantId, runId, "task-key-persist",
                toolCode, "Persist key test",
                Map.of(),
                true);
        assertNotNull(pid, "Approval PID must be created");

        String expectedKey = runId + ":" + toolCode;
        String sql = "SELECT idempotency_key FROM ab_agent_approval WHERE pid = #{params.pid}";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQueryWithoutTenant(
                sql, Map.of("pid", pid));

        assertThat(rows).isNotEmpty();
        assertThat(rows.get(0).get("idempotency_key"))
                .as("idempotency_key must be persisted as {runId}:{toolCode}")
                .isEqualTo(expectedKey);
    }

    // ========== Test 19: double-execution guard - approve already-approved record throws ==========

    @Test
    @Order(19)
    void approve_alreadyApproved_throwsIllegalStateException() {
        Long tenantId = getTestTenant().getId();

        // Create and approve an approval
        String pid = approvalGateService.checkAndRequestApproval(
                tenantId,
                "run-double-exec-" + testRunId,
                "task-double-exec",
                "double_exec_tool", "Double execution test",
                Map.of(),
                true);
        assertNotNull(pid, "Approval PID must be created");

        // First approval — should succeed
        Map<String, Object> firstResult = approvalGateService.approve(tenantId, pid, getTestUser().getId());
        assertNotNull(firstResult, "First approve() must succeed");

        // Second approval — must throw IllegalStateException to block double-execution
        assertThrows(IllegalStateException.class,
                () -> approvalGateService.approve(tenantId, pid, getTestUser().getId()),
                "approve() on an already-APPROVED record must throw IllegalStateException");
    }

    // ========== Test 20: idempotency key - different tenants do not collide ==========

    @Test
    @Order(20)
    void checkAndRequestApproval_differentTenants_noCollision() {
        Long tenantId = getTestTenant().getId();
        Long otherTenantId = tenantId + 77777L;  // synthetic — does not need to exist in ab_tenant
        String runId = "run-cross-tenant-" + testRunId;
        String toolCode = "cross_tenant_tool";

        // Create approval under real tenant
        String realPid = approvalGateService.checkAndRequestApproval(
                tenantId, runId, "task-ct-001",
                toolCode, "Cross-tenant test",
                Map.of(),
                true);
        assertNotNull(realPid, "Approval PID must be created for real tenant");

        // A second call with a different tenantId but same runId+toolCode must NOT return realPid
        // (the synthetic tenant has no record, so a new one is created — or null if tenant check fails)
        // What matters: no collision with realPid
        try {
            String otherPid = approvalGateService.checkAndRequestApproval(
                    otherTenantId, runId, "task-ct-002",
                    toolCode, "Cross-tenant test",
                    Map.of(),
                    true);
            // If a PID is returned it must be a different record
            if (otherPid != null) {
                assertThat(otherPid)
                        .as("Different tenants must not share the same approval PID")
                        .isNotEqualTo(realPid);
            }
        } catch (Exception ignored) {
            // FK violation on synthetic tenant is also acceptable — confirms isolation
        }
    }

    // ========== helpers ==========

    /**
     * Create an approval policy with the given approver_rules JSON and return its PID.
     */
    private String createApprovalPolicy(Long tenantId, String approverRulesJson) {
        String policyPid = UniqueIdGenerator.generate();
        Map<String, Object> policy = new HashMap<>();
        policy.put("pid", policyPid);
        policy.put("tenant_id", tenantId);
        policy.put("policy_name", "test-policy-" + policyPid);
        policy.put("approver_rules", approverRulesJson);
        policy.put("policy_status", "active");
        policy.put("deleted_flag", false);
        policy.put("created_at", Instant.now());
        policy.put("updated_at", Instant.now());
        dynamicDataMapper.insert("ab_approval_policy", policy);
        return policyPid;
    }

    /**
     * Create an approval record directly linked to the given policy PID and return its PID.
     */
    private String createApprovalWithPolicy(Long tenantId, String policyPid, String runId) {
        String approvalPid = UniqueIdGenerator.generate();
        Map<String, Object> approval = new HashMap<>();
        approval.put("pid", approvalPid);
        approval.put("tenant_id", tenantId);
        approval.put("run_id", runId);
        approval.put("task_id", "task-" + approvalPid);
        approval.put("approval_type", "tool_call");
        approval.put("approval_title", "Security test approval");
        approval.put("approval_status", "pending");
        approval.put("policy_id", policyPid);
        approval.put("created_at", Instant.now());
        approval.put("updated_at", Instant.now());
        dynamicDataMapper.insert("ab_agent_approval", approval);
        return approvalPid;
    }
}
