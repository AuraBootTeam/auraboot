package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.controller.LearningLoopController;
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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-41: Learning Loop tenant-wide stats endpoint for Mission Control.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("Learning Loop /drafts/stats (PR-41)")
class LearningLoopStatsIntegrationTest extends BaseIntegrationTest {

    @Autowired private LearningLoopController controller;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;

    @BeforeEach
    void setup() {
        tenantId = 9_850_000L + System.nanoTime() % 100_000;
        MetaContext.setCurrentTenantId(tenantId);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_skill_draft WHERE tenant_id = ?", tenantId);
        MetaContext.clear();
    }

    private void seedDraft(String status) {
        String pid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_skill_draft " +
                        "(pid, tenant_id, draft_skill_code, source_pattern_hash, contract_yaml, " +
                        " status, created_at) " +
                        "VALUES (?, ?, ?, ?, 'x', ?, NOW())",
                pid, tenantId, "auto.t." + pid.substring(0, 6), "h_" + pid, status);
    }

    @Test
    @DisplayName("no drafts → every status is 0, total is 0")
    void empty_tenant() {
        ApiResponse<Map<String, Object>> r = controller.stats();
        Map<String, Object> data = r.getData();
        assertThat(data.get("total")).isEqualTo(0L);
        @SuppressWarnings("unchecked")
        Map<String, Long> byStatus = (Map<String, Long>) data.get("by_status");
        assertThat(byStatus).containsKeys(
                "DRAFT_PENDING_REVIEW", "REVIEWED_OK", "SHADOW_RUNNING",
                "PROMOTED_PENDING_HUMAN", "ACTIVE", "REVIEWED_REJECTED", "DISCARDED");
        assertThat(byStatus.values()).allMatch(v -> v == 0L);
    }

    @Test
    @DisplayName("mixed statuses are counted correctly; unknown status still bubbled")
    void mixed_tenant() {
        seedDraft("DRAFT_PENDING_REVIEW");
        seedDraft("DRAFT_PENDING_REVIEW");
        seedDraft("REVIEWED_OK");
        seedDraft("ACTIVE");
        seedDraft("ACTIVE");
        seedDraft("ACTIVE");

        Map<String, Object> data = controller.stats().getData();
        assertThat(data.get("total")).isEqualTo(6L);
        @SuppressWarnings("unchecked")
        Map<String, Long> byStatus = (Map<String, Long>) data.get("by_status");
        assertThat(byStatus.get("DRAFT_PENDING_REVIEW")).isEqualTo(2L);
        assertThat(byStatus.get("REVIEWED_OK")).isEqualTo(1L);
        assertThat(byStatus.get("ACTIVE")).isEqualTo(3L);
        assertThat(byStatus.get("SHADOW_RUNNING")).isEqualTo(0L);
    }

    @Test
    @DisplayName("tenant isolation — other tenants' drafts are not counted")
    void tenant_isolation() {
        seedDraft("ACTIVE");

        Long otherTenant = tenantId + 1_000_000;
        String otherPid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_skill_draft " +
                        "(pid, tenant_id, draft_skill_code, source_pattern_hash, contract_yaml, " +
                        " status, created_at) " +
                        "VALUES (?, ?, ?, ?, 'x', 'ACTIVE', NOW())",
                otherPid, otherTenant, "auto.t.other", "h_other");

        Map<String, Object> data = controller.stats().getData();
        assertThat(data.get("total")).isEqualTo(1L);

        jdbc.update("DELETE FROM ab_agent_skill_draft WHERE tenant_id = ?", otherTenant);
    }
}
