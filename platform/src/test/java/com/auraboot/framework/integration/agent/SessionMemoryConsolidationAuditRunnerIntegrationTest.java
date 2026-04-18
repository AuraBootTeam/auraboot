package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.service.SessionMemoryConsolidationAuditRunner;
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

@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("SessionMemoryConsolidationAuditRunner (PR-66)")
class SessionMemoryConsolidationAuditRunnerIntegrationTest extends BaseIntegrationTest {

    @Autowired private SessionMemoryConsolidationAuditRunner runner;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;
    private String agentCode;

    @BeforeEach
    void setup() {
        tenantId = 9_794_000L + System.nanoTime() % 10_000;
        agentCode = "audit-test-" + System.nanoTime();
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_memory_promotion WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_agent_memory WHERE tenant_id = ?", tenantId);
    }

    private String seedSessionMemory(int importance) {
        String pid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_memory "
                        + "(pid, tenant_id, memory_agent_id, memory_type, category, "
                        + " memory_title, memory_content, importance, shareable, "
                        + " created_at, updated_at, deleted_flag) "
                        + "VALUES (?, ?, ?, 'fact', 'session', 't', 'c', ?, FALSE, "
                        + "NOW(), NOW(), FALSE)",
                pid, tenantId, agentCode, importance);
        return pid;
    }

    @Test
    @DisplayName("after consolidation, audit rows appear with status=ACTIVE and reason_code=session_upgrade")
    void emits_auditRows() {
        String p1 = seedSessionMemory(8);
        String p2 = seedSessionMemory(9);
        seedSessionMemory(3); // below threshold — should not be audited

        int audited = runner.consolidateWithAudit(tenantId, agentCode, 7);
        assertThat(audited).isEqualTo(2);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT source_memory_pid, status, reason_code, confidence_score "
                        + "FROM ab_agent_memory_promotion WHERE tenant_id = ? AND reason_code = 'session_upgrade'",
                tenantId);
        assertThat(rows).hasSize(2);
        for (Map<String, Object> row : rows) {
            assertThat(row.get("status")).isEqualTo("ACTIVE");
            assertThat(row.get("reason_code")).isEqualTo("session_upgrade");
            assertThat(((Number) row.get("confidence_score")).doubleValue()).isEqualTo(1.00d);
        }
        List<String> pids = rows.stream().map(r -> (String) r.get("source_memory_pid")).toList();
        assertThat(pids).containsExactlyInAnyOrder(p1, p2);
    }

    @Test
    @DisplayName("already-audited memories do not get duplicate audit rows on re-run")
    void idempotent_noDoubleAudit() {
        seedSessionMemory(8);
        seedSessionMemory(9);
        int first = runner.consolidateWithAudit(tenantId, agentCode, 7);
        int second = runner.consolidateWithAudit(tenantId, agentCode, 7);
        assertThat(first).isEqualTo(2);
        assertThat(second).isZero();

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_memory_promotion WHERE tenant_id = ? AND reason_code = 'session_upgrade'",
                Integer.class, tenantId);
        assertThat(count).isEqualTo(2);
    }

    @Test
    @DisplayName("no session memories meet threshold → 0 audit rows, no extractor churn")
    void nothingToPromote() {
        seedSessionMemory(2);
        int audited = runner.consolidateWithAudit(tenantId, agentCode, 7);
        assertThat(audited).isZero();
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_memory_promotion WHERE tenant_id = ?",
                Integer.class, tenantId);
        assertThat(count).isZero();
    }
}
