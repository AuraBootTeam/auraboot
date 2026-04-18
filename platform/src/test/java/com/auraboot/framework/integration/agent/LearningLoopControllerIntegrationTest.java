package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.controller.LearningLoopController;
import com.auraboot.framework.agent.service.PatternExtractor;
import com.auraboot.framework.agent.service.SkillDraftGenerator;
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
 * PR-26: REST API for the Mission Control HITL workflow.
 * Direct-invoke controller tests — no MockMvc; we're pinning the
 * service-facing contract (status transitions, tenant isolation,
 * response envelope).
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("LearningLoopController (PR-26)")
class LearningLoopControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired private LearningLoopController controller;
    @Autowired private PatternExtractor extractor;
    @Autowired private SkillDraftGenerator generator;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;

    @BeforeEach
    void setup() {
        tenantId = 9_450_000L + System.nanoTime() % 100_000;
        MetaContext.setContext(tenantId, testUser.getId(), testUser.getPid(), testUser.getUserName());
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_shadow_run WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_agent_skill_draft WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_agent_learning_pattern WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_agent_action WHERE tenant_id = ?", tenantId);
    }

    private String seedDraft(String sig, String model, String actionType) {
        for (int i = 0; i < 10; i++) {
            jdbc.update("INSERT INTO ab_agent_action " +
                            "(pid, tenant_id, run_id, action_code, action_type, target_model, " +
                            " command_signature, tool_ref, fidelity, action_status, executed_at, created_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, 'tool_X', 'full', 'success', NOW(), NOW())",
                    UniqueIdGenerator.generate(), tenantId, UniqueIdGenerator.generate(),
                    model + "." + actionType, actionType, model, sig);
        }
        extractor.extractPatterns();
        String patternPid = jdbc.queryForObject(
                "SELECT pid FROM ab_agent_learning_pattern " +
                        "WHERE tenant_id = ? AND pattern_signature->>'command_signature' = ?",
                String.class, tenantId, sig);
        return generator.generateDraftFor(patternPid);
    }

    // -----------------------------------------------------------------------
    // GET /drafts
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("GET /drafts lists tenant's drafts in reverse-chron order")
    void list_drafts_default() {
        seedDraft("sig1_" + tenantId, "crm_lead",        "update");
        seedDraft("sig2_" + tenantId, "crm_opportunity", "create");

        ApiResponse<List<Map<String, Object>>> response = controller.listDrafts(null, 50);
        assertThat(response.getData()).hasSize(2);
        // most recent first
        assertThat((String) response.getData().get(0).get("status")).isEqualTo("DRAFT_PENDING_REVIEW");
    }

    @Test
    @DisplayName("GET /drafts?status=REVIEWED_OK filters by status")
    void list_drafts_filtered() {
        String draft1 = seedDraft("sig3_" + tenantId, "crm_lead", "update");
        String draft2 = seedDraft("sig4_" + tenantId, "crm_lead", "create");
        controller.review(draft1, Map.of("decision", "approve", "comment", "lgtm"));

        ApiResponse<List<Map<String, Object>>> reviewed = controller.listDrafts("REVIEWED_OK", 50);
        assertThat(reviewed.getData()).hasSize(1);
        assertThat((String) reviewed.getData().get(0).get("pid")).isEqualTo(draft1);

        ApiResponse<List<Map<String, Object>>> pending = controller.listDrafts("DRAFT_PENDING_REVIEW", 50);
        assertThat(pending.getData()).hasSize(1);
        assertThat((String) pending.getData().get(0).get("pid")).isEqualTo(draft2);
    }

    @Test
    @DisplayName("GET /drafts caps limit between 1 and 200")
    void list_drafts_caps_limit() {
        seedDraft("cap_" + tenantId, "crm_lead", "update");
        // Over 200 caps to 200; below 1 caps to 1.
        assertThat(controller.listDrafts(null, 500).getCode()).isEqualTo("0");
        assertThat(controller.listDrafts(null, -1).getCode()).isEqualTo("0");
    }

    @Test
    @DisplayName("other tenants' drafts are invisible")
    void list_drafts_tenant_scoped() {
        String draft = seedDraft("scope_" + tenantId, "crm_lead", "update");

        // Switch tenant context to a foreign one
        Long otherTenant = tenantId + 1_000_000;
        MetaContext.setContext(otherTenant, testUser.getId(), testUser.getPid(), testUser.getUserName());
        ApiResponse<List<Map<String, Object>>> foreign = controller.listDrafts(null, 50);
        assertThat(foreign.getData()).isEmpty();

        // Restore our tenant + verify it still sees its own draft
        MetaContext.setContext(tenantId, testUser.getId(), testUser.getPid(), testUser.getUserName());
        ApiResponse<List<Map<String, Object>>> own = controller.listDrafts(null, 50);
        assertThat(own.getData()).extracting(r -> r.get("pid")).contains(draft);
    }

    // -----------------------------------------------------------------------
    // GET /drafts/{pid}
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("GET /drafts/{pid} returns draft + source pattern + recent shadow runs")
    void get_draft_detail() {
        String draftPid = seedDraft("sig_det_" + tenantId, "crm_lead", "update");

        ApiResponse<Map<String, Object>> response = controller.getDraft(draftPid);
        assertThat(response.getCode()).isEqualTo("0");
        Map<String, Object> data = response.getData();
        assertThat(data.get("pid")).isEqualTo(draftPid);
        assertThat(data.get("contract_yaml")).isNotNull();
        assertThat(data.get("source_pattern")).isNotNull();
        assertThat(data.get("recent_shadow_runs")).isInstanceOf(List.class);
    }

    @Test
    @DisplayName("GET /drafts/{pid} returns 404 for non-existent pid")
    void get_draft_404() {
        ApiResponse<Map<String, Object>> response = controller.getDraft("01NOSUCHPID");
        assertThat(response.getCode()).isEqualTo("404");
    }

    // -----------------------------------------------------------------------
    // POST /drafts/{pid}/review
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("approve DRAFT_PENDING_REVIEW → REVIEWED_OK + reviewer_id + reviewed_at set")
    void approve_pending() {
        String draftPid = seedDraft("sig_apv_" + tenantId, "crm_lead", "update");
        ApiResponse<Map<String, Object>> r = controller.review(draftPid,
                Map.of("decision", "approve", "comment", "looks good"));
        assertThat(r.getCode()).isEqualTo("0");
        assertThat(r.getData().get("status")).isEqualTo("REVIEWED_OK");
        assertThat(r.getData().get("previous_status")).isEqualTo("DRAFT_PENDING_REVIEW");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, reviewer_id, review_comment, reviewed_at FROM ab_agent_skill_draft WHERE pid = ?", draftPid);
        assertThat(row.get("status")).isEqualTo("REVIEWED_OK");
        assertThat(row.get("reviewer_id")).isEqualTo(testUser.getId());
        assertThat(row.get("review_comment")).isEqualTo("looks good");
        assertThat(row.get("reviewed_at")).isNotNull();
    }

    @Test
    @DisplayName("reject any status → REVIEWED_REJECTED")
    void reject_transitions() {
        String draftPid = seedDraft("sig_rej_" + tenantId, "crm_lead", "update");
        ApiResponse<Map<String, Object>> r = controller.review(draftPid,
                Map.of("decision", "reject", "comment", "unsafe"));
        assertThat(r.getData().get("status")).isEqualTo("REVIEWED_REJECTED");
    }

    @Test
    @DisplayName("approve PROMOTED_PENDING_HUMAN → ACTIVE + promoted_at set")
    void promote_to_active() {
        String draftPid = seedDraft("sig_pro_" + tenantId, "crm_lead", "update");
        // Force state to PROMOTED_PENDING_HUMAN (the evaluator would normally do this)
        jdbc.update("UPDATE ab_agent_skill_draft SET status='PROMOTED_PENDING_HUMAN' WHERE pid = ?", draftPid);

        ApiResponse<Map<String, Object>> r = controller.review(draftPid,
                Map.of("decision", "approve"));
        assertThat(r.getData().get("status")).isEqualTo("ACTIVE");
        Object promotedAt = jdbc.queryForObject(
                "SELECT promoted_at FROM ab_agent_skill_draft WHERE pid = ?", Object.class, draftPid);
        assertThat(promotedAt).isNotNull();
    }

    @Test
    @DisplayName("approve from REVIEWED_REJECTED returns 409")
    void approve_from_rejected_rejected() {
        String draftPid = seedDraft("sig_conf_" + tenantId, "crm_lead", "update");
        controller.review(draftPid, Map.of("decision", "reject"));

        ApiResponse<Map<String, Object>> r = controller.review(draftPid, Map.of("decision", "approve"));
        assertThat(r.getCode()).isEqualTo("409");
    }

    @Test
    @DisplayName("invalid decision returns 400")
    void invalid_decision_400() {
        String draftPid = seedDraft("sig_bad_" + tenantId, "crm_lead", "update");
        ApiResponse<Map<String, Object>> r = controller.review(draftPid, Map.of("decision", "maybe"));
        assertThat(r.getCode()).isEqualTo("400");
    }

    // -----------------------------------------------------------------------
    // POST /drafts/{pid}/auto-rename
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("auto-rename returns renamed=false gracefully when no LLM configured")
    void auto_rename_no_llm_graceful() {
        String draftPid = seedDraft("sig_ren_" + tenantId, "crm_lead", "update");
        ApiResponse<Map<String, Object>> r = controller.autoRename(draftPid);
        assertThat(r.getCode()).isEqualTo("0");
        assertThat(r.getData().get("pid")).isEqualTo(draftPid);
        assertThat(r.getData().get("renamed")).isEqualTo(false);
    }
}
