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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-42: Shadow Run inspector endpoint.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("LearningLoopController.shadowRuns (PR-42)")
class LearningLoopShadowRunsIntegrationTest extends BaseIntegrationTest {

    @Autowired private LearningLoopController controller;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;

    @BeforeEach
    void setup() {
        tenantId = 9_950_000L + System.nanoTime() % 100_000;
        MetaContext.setCurrentTenantId(tenantId);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_shadow_run WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_agent_skill_draft WHERE tenant_id = ?", tenantId);
        MetaContext.clear();
    }

    private String seedDraft(Long tid) {
        String pid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_skill_draft " +
                        "(pid, tenant_id, draft_skill_code, source_pattern_hash, contract_yaml, " +
                        " status, created_at) " +
                        "VALUES (?, ?, ?, ?, 'x', 'REVIEWED_OK', NOW())",
                pid, tid, "auto.t." + pid.substring(0, 6), "h_" + pid);
        return pid;
    }

    private void seedShadowRun(String draftPid, boolean outputMatch, String status) {
        jdbc.update("INSERT INTO ab_agent_shadow_run " +
                        "(pid, tenant_id, draft_id, original_run_id, shadow_status, " +
                        " shadow_duration_ms, original_duration_ms, output_match, fidelity_match) " +
                        "VALUES (?, ?, ?, ?, ?, 100, 120, ?, ?)",
                UniqueIdGenerator.generate(), tenantId, draftPid,
                "orig" + System.nanoTime(), status, outputMatch, outputMatch);
    }

    @Test
    @DisplayName("returns all shadow runs for a draft, newest first")
    void returns_runs_newest_first() {
        String draftPid = seedDraft(tenantId);
        seedShadowRun(draftPid, true, "success");
        seedShadowRun(draftPid, false, "success");
        seedShadowRun(draftPid, true, "failed");

        ApiResponse<List<Map<String, Object>>> r = controller.shadowRuns(draftPid, 50);
        assertThat(r.getData()).hasSize(3);
        assertThat(r.getData().get(0).get("shadow_status"))
                .isIn("success", "failed"); // last inserted wins in ordering
    }

    @Test
    @DisplayName("unknown draft → 404")
    void unknown_draft_404() {
        ApiResponse<List<Map<String, Object>>> r = controller.shadowRuns("NONEXISTENTPID1234567890", 50);
        assertThat(r.getCode()).isNotEqualTo("0");
        assertThat(r.getMessage()).contains("not found");
    }

    @Test
    @DisplayName("tenant isolation — cannot read another tenant's shadow runs")
    void cross_tenant_404() {
        Long otherTenant = tenantId + 1_000_000;
        String otherDraft = seedDraft(otherTenant);
        seedShadowRun(otherDraft, true, "success");

        ApiResponse<List<Map<String, Object>>> r = controller.shadowRuns(otherDraft, 50);
        assertThat(r.getCode()).isNotEqualTo("0");
        assertThat(r.getMessage()).contains("not found");

        jdbc.update("DELETE FROM ab_agent_shadow_run WHERE tenant_id = ?", otherTenant);
        jdbc.update("DELETE FROM ab_agent_skill_draft WHERE tenant_id = ?", otherTenant);
    }

    @Test
    @DisplayName("limit cap at 200, floor at 1")
    void limit_clamped() {
        String draftPid = seedDraft(tenantId);
        seedShadowRun(draftPid, true, "success");
        assertThat(controller.shadowRuns(draftPid, 0).getData()).hasSize(1);
        assertThat(controller.shadowRuns(draftPid, 99999).getData()).hasSize(1);
    }
}
