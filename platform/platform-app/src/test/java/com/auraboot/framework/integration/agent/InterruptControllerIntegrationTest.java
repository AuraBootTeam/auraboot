package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.controller.InterruptController;
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
 * PR-38: Tenant-wide interrupt audit endpoint — list + filter.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("InterruptController tenant audit (PR-38)")
class InterruptControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired private InterruptController controller;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;

    @BeforeEach
    void setup() {
        tenantId = 9_650_000L + System.nanoTime() % 100_000;
        MetaContext.setCurrentTenantId(tenantId);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_interrupt_log WHERE tenant_id = ?", tenantId);
        MetaContext.clear();
    }

    private void seedLog(String subPolicy, String excerpt) {
        jdbc.update("INSERT INTO ab_agent_interrupt_log " +
                        "(pid, tenant_id, session_id, active_run_id, new_message_excerpt, " +
                        " sub_policy, classifier_tier, confidence, reason, action_taken, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, 'keyword', 0.9, 'test', 'audit', NOW())",
                UniqueIdGenerator.generate(), tenantId, "sess_" + System.nanoTime(),
                "run_" + System.nanoTime(), excerpt, subPolicy);
    }

    @Test
    @DisplayName("tenant-wide list returns all sub_policies")
    void lists_all_interrupts() {
        seedLog("replace_intent", "cancel that, do this instead");
        seedLog("append_context", "also include yesterday");
        seedLog("insert_subtask", "meanwhile send the email");

        ApiResponse<List<Map<String, Object>>> r = controller.listTenantInterrupts(null, 100);
        assertThat(r.getData()).hasSize(3);
    }

    @Test
    @DisplayName("subPolicy filter narrows to matching rows")
    void filter_by_sub_policy() {
        seedLog("replace_intent", "x");
        seedLog("replace_intent", "y");
        seedLog("append_context", "z");

        ApiResponse<List<Map<String, Object>>> r = controller.listTenantInterrupts("replace_intent", 100);
        assertThat(r.getData()).hasSize(2);
        assertThat(r.getData()).allMatch(m -> "replace_intent".equals(m.get("sub_policy")));
    }

    @Test
    @DisplayName("tenant isolation — other tenants' rows are not returned")
    void tenant_isolation() {
        seedLog("replace_intent", "ours");

        Long otherTenant = tenantId + 1_000_000;
        jdbc.update("INSERT INTO ab_agent_interrupt_log " +
                        "(pid, tenant_id, session_id, new_message_excerpt, " +
                        " sub_policy, classifier_tier, confidence, reason, action_taken, created_at) " +
                        "VALUES (?, ?, 'sess_x', 'theirs', 'replace_intent', 'keyword', 0.9, 't', 'a', NOW())",
                UniqueIdGenerator.generate(), otherTenant);

        ApiResponse<List<Map<String, Object>>> r = controller.listTenantInterrupts(null, 100);
        assertThat(r.getData()).hasSize(1);
        assertThat(r.getData().get(0).get("new_message_excerpt")).isEqualTo("ours");

        jdbc.update("DELETE FROM ab_agent_interrupt_log WHERE tenant_id = ?", otherTenant);
    }
}
