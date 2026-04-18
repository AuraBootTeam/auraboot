package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.dto.BusinessIntentFrame;
import com.auraboot.framework.agent.dto.ConfidenceScore;
import com.auraboot.framework.agent.service.BifRecorder;
import com.auraboot.framework.agent.service.GroundingService;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for ACP D1 Grounding persistence:
 * - BifRecorder writes rows with all JSONB columns intact
 * - GroundingService.ground() produces a BIF that round-trips through the mapper
 * - candidate_skills_mode CHECK constraint is respected
 */
@Commit
@DisplayName("ACP D1 Grounding — BIF persistence")
class BifRecorderIntegrationTest extends BaseIntegrationTest {

    @Autowired private BifRecorder bifRecorder;
    @Autowired private GroundingService groundingService;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;

    @BeforeEach
    void setup() {
        tenantId = 9_9001L + System.nanoTime() % 1000;
        MetaContext.setContext(tenantId, testUser.getId(), testUser.getPid(), testUser.getUserName());
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_bif WHERE tenant_id = ?", tenantId);
    }

    @Test
    @DisplayName("record() persists core BIF fields and returns pid")
    void record_persists_core_fields() {
        BusinessIntentFrame bif = BusinessIntentFrame.builder()
                .intent("query")
                .object("crm_account")
                .primaryObject("crm_account")
                .riskLevel("L0")
                .actionability("read_only")
                .matchType("alias")
                .confidence(ConfidenceScore.of(0.9, 0.85))
                .candidateSkills(List.of("dsl.query"))
                .candidateSkillsMode("hint")
                .filters(List.of(Map.of("field", "status", "op", "=", "value", "active")))
                .explanation(Map.of("intentMatch", "query ← exact"))
                .build();

        String pid = bifRecorder.record(tenantId, "show me active customers", bif, null, "sess-001");

        assertThat(pid).as("record() returns generated pid").isNotNull();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT intent, object, risk_level, actionability, match_type, " +
                        "candidate_skills_mode, nl_input, conversation_id, schema_version " +
                        "FROM ab_agent_bif WHERE pid = ?", pid);

        assertThat(row.get("intent")).isEqualTo("query");
        assertThat(row.get("object")).isEqualTo("crm_account");
        assertThat(row.get("risk_level")).isEqualTo("L0");
        assertThat(row.get("actionability")).isEqualTo("read_only");
        assertThat(row.get("match_type")).isEqualTo("alias");
        assertThat(row.get("candidate_skills_mode")).isEqualTo("hint");
        assertThat(row.get("nl_input")).isEqualTo("show me active customers");
        assertThat(row.get("conversation_id")).isEqualTo("sess-001");
        assertThat(((Number) row.get("schema_version")).intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("record() serializes JSONB columns (confidence, filters, candidate_skills)")
    void record_persists_jsonb_columns() {
        BusinessIntentFrame bif = BusinessIntentFrame.builder()
                .intent("analyze")
                .object("crm_lead")
                .riskLevel("L0")
                .actionability("read_only")
                .confidence(ConfidenceScore.of(0.8, 0.7))
                .candidateSkills(List.of("dsl.query", "crm.analysis.pipeline"))
                .candidateSkillsMode("bounded")
                .filters(List.of(Map.of("field", "crm_lead_status", "op", "=", "value", "new")))
                .build();

        String pid = bifRecorder.record(tenantId, "analyze high-risk leads", bif, null, null);

        String confJson = jdbc.queryForObject(
                "SELECT confidence::text FROM ab_agent_bif WHERE pid = ?", String.class, pid);
        assertThat(confJson).contains("\"overall\"").contains("\"intent\"").contains("\"object\"");

        String skillsJson = jdbc.queryForObject(
                "SELECT candidate_skills::text FROM ab_agent_bif WHERE pid = ?", String.class, pid);
        assertThat(skillsJson).contains("dsl.query").contains("crm.analysis.pipeline");

        String filtersJson = jdbc.queryForObject(
                "SELECT filters::text FROM ab_agent_bif WHERE pid = ?", String.class, pid);
        assertThat(filtersJson).contains("crm_lead_status").contains("new");
    }

    @Test
    @DisplayName("attachRun() backfills run_id / step_index / dispatched_skill")
    void attachRun_backfills_run_fields() {
        BusinessIntentFrame bif = BusinessIntentFrame.builder()
                .intent("query").object("crm_account").riskLevel("L0")
                .confidence(ConfidenceScore.of(0.9, 0.8))
                .candidateSkillsMode("hint")
                .build();
        String pid = bifRecorder.record(tenantId, "test", bif, null, null);

        bifRecorder.attachRun(pid, "run-abc", 2, "dsl.query");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT run_id, step_index, dispatched_skill FROM ab_agent_bif WHERE pid = ?", pid);
        assertThat(row.get("run_id")).isEqualTo("run-abc");
        assertThat(((Number) row.get("step_index")).intValue()).isEqualTo(2);
        assertThat(row.get("dispatched_skill")).isEqualTo("dsl.query");
    }

    @Test
    @DisplayName("GroundingService.ground() output round-trips through BifRecorder")
    void grounding_output_round_trips() {
        // IntentParser's built-in patterns recognize "查询" / "查看" / "show" as query intent;
        // ObjectResolver recognizes "客户" as crm_account via ab_object_alias seed.
        var ctx = GroundingService.GroundingContext.builder()
                .pageModel("crm_account")
                .sessionId("sess-grd-01")
                .build();
        BusinessIntentFrame bif = groundingService.ground(tenantId, "查看客户", ctx);

        assertThat(bif).isNotNull();
        assertThat(bif.getIntent()).isNotNull();
        assertThat(bif.getCandidateSkillsMode()).isIn("hint", "bounded", "fixed");

        String pid = bifRecorder.record(tenantId, "查看客户", bif, null, "sess-grd-01");
        assertThat(pid).as("ground() output must persist cleanly").isNotNull();

        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_bif WHERE tenant_id = ? AND nl_input = ?",
                Integer.class, tenantId, "查看客户");
        assertThat(cnt).isEqualTo(1);
    }
}
