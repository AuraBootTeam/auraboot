package com.auraboot.framework.agent;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.agent.service.AgentApprovalGateService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-stack IT for ApprovalGate timeout <em>enforcement</em> (campaign gap S2/S8): the existing
 * {@code AgentApprovalGateIntegrationTest} only verifies that {@code expires_at} is <em>set</em> on
 * creation; the actual scheduled auto-expire path ({@link AgentApprovalGateService#enforceApprovalTimeouts()})
 * had only a mock unit test. This pins the real behaviour: a pending approval whose {@code expires_at}
 * has passed is, on the next scheduler tick, transitioned to {@code expired} with an auto-expiry reason
 * — the fail-secure property the design doc requires ("超时 auto-expire").
 */
@Slf4j
@DisplayName("ApprovalGate: scheduled enforcement expires a past-deadline pending approval")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AgentApprovalTimeoutEnforcementIT extends BaseIntegrationTest {

    @Autowired private AgentApprovalGateService approvalGateService;
    @Autowired private DynamicDataMapper dynamicDataMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
    private String policyPid;
    private String approvalPid;

    @BeforeAll
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void setUp() {
        super.setupTenantContext();
        MetaContext.setContext(getTestTenant().getId(), getTestUser().getId(), getTestUser().getPid(), getTestUser().getUserName());

        // A wildcard active policy so checkAndRequestApproval creates a pending row (fail-secure
        // refuses without a matching policy).
        Long tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();
        policyPid = UniqueIdGenerator.generate();
        Map<String, Object> policy = new HashMap<>();
        policy.put("pid", policyPid);
        policy.put("tenant_id", tenantId);
        policy.put("policy_name", "timeout-test-policy-" + suffix);
        policy.put("description", "Timeout enforcement IT policy");
        policy.put("trigger_rules", "[{\"type\":\"tool_call\",\"pattern\":\"^(?!read_file$).*\"}]");
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

    @AfterAll
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void tearDown() {
        try {
            if (approvalPid != null) jdbcTemplate.update("DELETE FROM ab_agent_approval WHERE pid = ?", approvalPid);
            if (policyPid != null) jdbcTemplate.update("DELETE FROM ab_approval_policy WHERE pid = ?", policyPid);
        } catch (Exception ignored) {}
    }

    @BeforeEach
    void ctx() {
        MetaContext.setContext(getTestTenant().getId(), getTestUser().getId(), getTestUser().getPid(), getTestUser().getUserName());
    }

    @Test
    @DisplayName("a pending approval past its expires_at is auto-expired by the scheduler")
    void pendingApproval_pastDeadline_isAutoExpired() {
        Long tenantId = getTestTenant().getId();
        String runId = "run-timeout-" + suffix;

        approvalPid = approvalGateService.checkAndRequestApproval(
                tenantId, runId, "task-timeout-" + suffix,
                "file_delete", "Delete /tmp/" + suffix, Map.of("path", "/tmp/" + suffix), true);
        assertThat(approvalPid).as("a pending approval must be created").isNotNull();

        // pending before enforcement
        String before = jdbcTemplate.queryForObject(
                "SELECT approval_status FROM ab_agent_approval WHERE pid = ?", String.class, approvalPid);
        assertThat(before).isEqualTo("pending");

        // push the deadline into the past, then run the scheduled enforcement directly
        jdbcTemplate.update("UPDATE ab_agent_approval SET expires_at = ? WHERE pid = ?",
                Timestamp.from(Instant.now().minusSeconds(3600)), approvalPid);

        approvalGateService.enforceApprovalTimeouts();

        Map<String, Object> after = jdbcTemplate.queryForMap(
                "SELECT approval_status, rejection_reason FROM ab_agent_approval WHERE pid = ?", approvalPid);
        assertThat(after.get("approval_status"))
                .as("a past-deadline pending approval must be auto-expired by the scheduler").isEqualTo("expired");
        assertThat(String.valueOf(after.get("rejection_reason")))
                .as("auto-expiry must record a reason").contains("Auto-expired");

        log.info("[ApprovalGate timeout] PASS — approval {} auto-expired by enforceApprovalTimeouts", approvalPid);
    }
}
