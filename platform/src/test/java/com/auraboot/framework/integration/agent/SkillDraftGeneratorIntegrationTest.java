package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.service.PatternExtractor;
import com.auraboot.framework.agent.service.SkillDraftGenerator;
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
 * PR-18: SkillDraftGenerator converts OBSERVED patterns into
 * ab_agent_skill_draft rows with status=DRAFT_PENDING_REVIEW and
 * flips the pattern to DRAFT_GENERATED with draft_skill_id set.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("SkillDraftGenerator (PR-18)")
class SkillDraftGeneratorIntegrationTest extends BaseIntegrationTest {

    @Autowired private PatternExtractor extractor;
    @Autowired private SkillDraftGenerator generator;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;

    @BeforeEach
    void setup() {
        tenantId = 9_700_000L + System.nanoTime() % 100_000;
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_skill_draft WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_agent_learning_pattern WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_agent_action WHERE tenant_id = ?", tenantId);
    }

    /** Seed successful Actions that will aggregate into a single pattern. */
    private void seed(String sig, String model, String actionType, String fidelity,
                       String toolRef, int n) {
        for (int i = 0; i < n; i++) {
            jdbc.update("INSERT INTO ab_agent_action " +
                            "(pid, tenant_id, run_id, action_code, action_type, target_model, " +
                            " command_signature, tool_ref, fidelity, action_status, " +
                            " executed_at, created_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'success', NOW(), NOW())",
                    UniqueIdGenerator.generate(), tenantId, UniqueIdGenerator.generate(),
                    model + "." + actionType, actionType, model, sig, toolRef, fidelity);
        }
    }

    private String seedPattern(String sig, String model, String actionType, String fidelity,
                                String toolRef, int n) {
        seed(sig, model, actionType, fidelity, toolRef, n);
        extractor.extractPatterns();
        return jdbc.queryForObject(
                "SELECT pid FROM ab_agent_learning_pattern " +
                        "WHERE tenant_id = ? AND pattern_signature->>'command_signature' = ?",
                String.class, tenantId, sig);
    }

    // -----------------------------------------------------------------------

    @Test
    @DisplayName("generateDrafts creates a DRAFT_PENDING_REVIEW row for each OBSERVED pattern")
    void generates_drafts_for_observed_patterns() {
        String sigA = "sig_A_" + tenantId;
        String sigB = "sig_B_" + tenantId;
        seedPattern(sigA, "crm_lead",        "update", "full",     "cmd_update_lead", 10);
        seedPattern(sigB, "crm_opportunity", "create", "semantic", "api.create_opp",  10);

        int created = generator.generateDrafts();
        assertThat(created).isEqualTo(2);

        Integer drafts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_skill_draft " +
                        "WHERE tenant_id = ? AND status = 'DRAFT_PENDING_REVIEW'",
                Integer.class, tenantId);
        assertThat(drafts).isEqualTo(2);

        Integer graduated = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_learning_pattern " +
                        "WHERE tenant_id = ? AND status = 'DRAFT_GENERATED' AND draft_skill_id IS NOT NULL",
                Integer.class, tenantId);
        assertThat(graduated).isEqualTo(2);
    }

    @Test
    @DisplayName("substrate chosen from dominant Action fidelity")
    void substrate_inferred_from_fidelity() {
        String sig = "sig_dsl_" + tenantId;
        seedPattern(sig, "crm_lead", "update", "full", "cmd_X", 10);
        generator.generateDrafts();

        String yaml = jdbc.queryForObject(
                "SELECT contract_yaml FROM ab_agent_skill_draft " +
                        "WHERE tenant_id = ? AND source_pattern_hash IN " +
                        "  (SELECT pattern_hash FROM ab_agent_learning_pattern " +
                        "     WHERE pattern_signature->>'command_signature' = ?)",
                String.class, tenantId, sig);
        assertThat(yaml).contains("substrate: dsl");
        assertThat(yaml).contains("tool_refs:").contains("- cmd_X");
        assertThat(yaml).contains("target_model: crm_lead");
        assertThat(yaml).contains("action_type: update");
    }

    @Test
    @DisplayName("semantic fidelity → substrate 'api'; blackbox → 'code'")
    void substrate_mapping() {
        String sigApi = "sig_api_" + tenantId;
        String sigCode = "sig_code_" + tenantId;
        seedPattern(sigApi,  "crm_lead", "query",  "semantic", "api.x", 10);
        seedPattern(sigCode, "crm_lead", "export", "blackbox", "code.y", 10);
        generator.generateDrafts();

        String yamlApi = jdbc.queryForObject(
                "SELECT contract_yaml FROM ab_agent_skill_draft WHERE tenant_id = ? AND " +
                        "source_pattern_hash IN (SELECT pattern_hash FROM ab_agent_learning_pattern " +
                        "WHERE pattern_signature->>'command_signature' = ?)",
                String.class, tenantId, sigApi);
        assertThat(yamlApi).contains("substrate: api");

        String yamlCode = jdbc.queryForObject(
                "SELECT contract_yaml FROM ab_agent_skill_draft WHERE tenant_id = ? AND " +
                        "source_pattern_hash IN (SELECT pattern_hash FROM ab_agent_learning_pattern " +
                        "WHERE pattern_signature->>'command_signature' = ?)",
                String.class, tenantId, sigCode);
        assertThat(yamlCode).contains("substrate: code");
    }

    @Test
    @DisplayName("draft insertion is idempotent — second run does not create duplicates")
    void idempotent_second_run() {
        String sig = "sig_idem_" + tenantId;
        seedPattern(sig, "crm_lead", "update", "full", "cmd_X", 10);

        generator.generateDrafts();
        int afterFirst = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_skill_draft WHERE tenant_id = ?",
                Integer.class, tenantId);
        generator.generateDrafts();
        int afterSecond = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_skill_draft WHERE tenant_id = ?",
                Integer.class, tenantId);
        assertThat(afterSecond).isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("generateDraftFor(pid) targets a single pattern and returns draft pid")
    void single_pattern_trigger() {
        String sig = "sig_single_" + tenantId;
        String patternPid = seedPattern(sig, "crm_lead", "update", "full", "cmd_X", 10);

        String draftPid = generator.generateDraftFor(patternPid);
        assertThat(draftPid).isNotNull();

        Map<String, Object> draft = jdbc.queryForMap(
                "SELECT draft_skill_code, status, contract_hash, source_pattern_hash " +
                        "FROM ab_agent_skill_draft WHERE pid = ?", draftPid);
        assertThat((String) draft.get("draft_skill_code")).startsWith("auto.crm_lead_update.");
        assertThat(draft.get("status")).isEqualTo("DRAFT_PENDING_REVIEW");
        assertThat((String) draft.get("contract_hash")).hasSize(64);
    }

    @Test
    @DisplayName("derived_from_runs captures up to 5 distinct run_ids from the pattern")
    void derived_from_runs_captured() {
        String sig = "sig_runs_" + tenantId;
        seedPattern(sig, "crm_lead", "update", "full", "cmd_X", 10); // 10 distinct run_ids
        generator.generateDrafts();

        String json = jdbc.queryForObject(
                "SELECT derived_from_runs::text FROM ab_agent_skill_draft " +
                        "WHERE tenant_id = ? LIMIT 1", String.class, tenantId);
        // Should be a JSON array of up to 5 {run_id: ...} objects
        assertThat(json).startsWith("[");
        int commaCount = json.chars().filter(c -> c == ',').count() == 0
                ? 0 : (int) json.chars().filter(c -> c == ',').count();
        assertThat(commaCount).isLessThanOrEqualTo(4); // at most 5 entries → 4 commas
        assertThat(json).contains("run_id");
    }

    @Test
    @DisplayName("already-drafted pattern is skipped (generateDraftFor returns existing draft pid)")
    void already_drafted_skipped() {
        String sig = "sig_skip_" + tenantId;
        String patternPid = seedPattern(sig, "crm_lead", "update", "full", "cmd_X", 10);

        String first = generator.generateDraftFor(patternPid);
        String second = generator.generateDraftFor(patternPid);

        assertThat(second).isEqualTo(first);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_skill_draft WHERE tenant_id = ? AND source_pattern_hash " +
                        "IN (SELECT pattern_hash FROM ab_agent_learning_pattern WHERE pid = ?)",
                Integer.class, tenantId, patternPid);
        assertThat(count).isEqualTo(1);
    }
}
