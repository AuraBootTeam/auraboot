package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.dto.BusinessIntentFrame;
import com.auraboot.framework.agent.dto.ConfidenceScore;
import com.auraboot.framework.agent.service.ActionRecorder;
import com.auraboot.framework.agent.service.BifContext;
import com.auraboot.framework.agent.service.FidelityGrader;
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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-15: Action Contract v1.1 fields (fidelity / skill_code / tool_ref /
 * command_signature / change_summary) populate via ActionRecorder.
 */
@Commit
@DisplayName("ActionRecorder — v1.1 fidelity fields (PR-15)")
class ActionFidelityIntegrationTest extends BaseIntegrationTest {

    @Autowired private ActionRecorder actionRecorder;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;
    private String nqCode;

    @BeforeEach
    void setup() {
        tenantId = 9_9001L + System.nanoTime() % 1000;
        MetaContext.setContext(tenantId, testUser.getId(), testUser.getPid(), testUser.getUserName());
        nqCode = "nq_fid_" + System.nanoTime();
        jdbc.update("INSERT INTO ab_named_query (pid, tenant_id, code, from_sql, status, current_version) " +
                        "VALUES (?, ?, ?, ?, 'published', 1)",
                "nq-" + nqCode, tenantId, nqCode, "SELECT * FROM mt_crm_lead");
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_action WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_named_query WHERE tenant_id = ?", tenantId);
        BifContext.clear();
    }

    private AgentToolDefinition queryTool() {
        AgentToolDefinition t = new AgentToolDefinition();
        t.setName("nq:customer_list");
        t.setSourceCode(nqCode);
        t.setToolType("dsl_query");
        t.setRiskLevel("L0");
        return t;
    }

    // -----------------------------------------------------------------------

    @Test
    @DisplayName("recordReadAction populates fidelity=full + tool_ref + command_signature")
    void read_action_populates_v11_fields() {
        String runPid = UniqueIdGenerator.generate();

        String actionPid = actionRecorder.recordReadAction(
                tenantId, runPid, nqCode, queryTool(),
                Map.of("keyword", "acme"), 3, null);

        assertThat(actionPid).isNotNull();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT fidelity, tool_ref, command_signature, skill_code " +
                        "FROM ab_agent_action WHERE pid = ?", actionPid);
        // Reads have no before/after diff to reconstruct — fidelity is semantic
        // per skill-substrate-contract §10.1 (M4 fix).
        assertThat(row.get("fidelity")).isEqualTo(FidelityGrader.FIDELITY_SEMANTIC);
        assertThat(row.get("tool_ref")).isEqualTo("nq:customer_list");
        assertThat(row.get("command_signature")).isNotNull();
        assertThat((String) row.get("command_signature")).hasSize(64);
        assertThat(row.get("skill_code")).isNull(); // no BIF context in this test
    }

    @Test
    @DisplayName("recordReadAction inherits skill_code from current BifContext when present")
    void read_action_reads_skill_from_bif() {
        BifContext.setCurrentBif(BusinessIntentFrame.builder()
                .intent("query").object("crm_lead").riskLevel("L0")
                .actionability("read_only")
                .candidateSkills(java.util.List.of("dsl.query", "crm.analysis.pipeline"))
                .candidateSkillsMode("hint")
                .confidence(ConfidenceScore.of(0.9, 0.85))
                .build());

        String actionPid = actionRecorder.recordReadAction(
                tenantId, UniqueIdGenerator.generate(), nqCode, queryTool(),
                Map.of(), 1, null);

        String skillCode = jdbc.queryForObject(
                "SELECT skill_code FROM ab_agent_action WHERE pid = ?", String.class, actionPid);
        assertThat(skillCode).isEqualTo("dsl.query"); // first candidate
    }

    @Test
    @DisplayName("command_signature is stable across calls with same args (dedup key)")
    void command_signature_is_deterministic() {
        String pid1 = actionRecorder.recordReadAction(
                tenantId, UniqueIdGenerator.generate(), nqCode, queryTool(),
                Map.of("k", "v", "n", 2), 0, null);
        String pid2 = actionRecorder.recordReadAction(
                tenantId, UniqueIdGenerator.generate(), nqCode, queryTool(),
                // different insertion order — canonical form is sorted.
                new java.util.LinkedHashMap<>(Map.of("n", 2, "k", "v")), 0, null);

        String sig1 = jdbc.queryForObject(
                "SELECT command_signature FROM ab_agent_action WHERE pid = ?", String.class, pid1);
        String sig2 = jdbc.queryForObject(
                "SELECT command_signature FROM ab_agent_action WHERE pid = ?", String.class, pid2);
        assertThat(sig1).isEqualTo(sig2);
    }

    @Test
    @DisplayName("command_signature differs when args differ (no false dedup)")
    void command_signature_differs_on_arg_change() {
        String pid1 = actionRecorder.recordReadAction(
                tenantId, UniqueIdGenerator.generate(), nqCode, queryTool(),
                Map.of("k", "v1"), 0, null);
        String pid2 = actionRecorder.recordReadAction(
                tenantId, UniqueIdGenerator.generate(), nqCode, queryTool(),
                Map.of("k", "v2"), 0, null);

        String sig1 = jdbc.queryForObject(
                "SELECT command_signature FROM ab_agent_action WHERE pid = ?", String.class, pid1);
        String sig2 = jdbc.queryForObject(
                "SELECT command_signature FROM ab_agent_action WHERE pid = ?", String.class, pid2);
        assertThat(sig1).isNotEqualTo(sig2);
    }
}
