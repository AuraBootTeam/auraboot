package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.service.PatternExtractor;
import com.auraboot.framework.agent.service.SkillDraftGenerator;
import com.auraboot.framework.agent.service.SkillDraftNamer;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-25: SkillDraftNamer behaviour.
 * Main focus is the safety rails: when no LLM is configured (integration
 * test env), renameDraft must no-op without breaking anything. The code
 * validation contract is also pinned.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("SkillDraftNamer (PR-25)")
class SkillDraftNamerIntegrationTest extends BaseIntegrationTest {

    @Autowired private PatternExtractor extractor;
    @Autowired private SkillDraftGenerator generator;
    @Autowired private SkillDraftNamer namer;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;

    @BeforeEach
    void setup() {
        tenantId = 9_750_000L + System.nanoTime() % 100_000;
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_skill_draft WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_agent_learning_pattern WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_agent_action WHERE tenant_id = ?", tenantId);
    }

    private String seedDraftWithAutoName() {
        String sig = "sig_" + System.nanoTime();
        for (int i = 0; i < 10; i++) {
            jdbc.update("INSERT INTO ab_agent_action " +
                            "(pid, tenant_id, run_id, action_code, action_type, target_model, " +
                            " command_signature, tool_ref, fidelity, action_status, executed_at, created_at) " +
                            "VALUES (?, ?, ?, ?, 'update', 'crm_lead', ?, 'cmd_X', 'full', 'success', NOW(), NOW())",
                    UniqueIdGenerator.generate(), tenantId, UniqueIdGenerator.generate(),
                    "crm_lead.update", sig);
        }
        extractor.extractPatterns();
        String patternPid = jdbc.queryForObject(
                "SELECT pid FROM ab_agent_learning_pattern " +
                        "WHERE tenant_id = ? AND pattern_signature->>'command_signature' = ?",
                String.class, tenantId, sig);
        return generator.generateDraftFor(patternPid);
    }

    // -----------------------------------------------------------------------

    @Test
    @DisplayName("isValidCode accepts domain-prefixed snake_case ≤ 60 chars")
    void valid_code_shape() {
        assertThat(namer.isValidCode("crm.lead_batch_update")).isTrue();
        assertThat(namer.isValidCode("analytics.customer_industry_count")).isTrue();
        assertThat(namer.isValidCode("crm.a.b.c")).isTrue(); // nested dot ok
    }

    @Test
    @DisplayName("isValidCode rejects uppercase / missing prefix / too long")
    void valid_code_rejects_bad() {
        assertThat(namer.isValidCode(null)).isFalse();
        assertThat(namer.isValidCode("")).isFalse();
        assertThat(namer.isValidCode("CRM.lead_update")).isFalse();      // uppercase
        assertThat(namer.isValidCode("just_no_dot")).isFalse();          // missing domain
        assertThat(namer.isValidCode("crm.lead update")).isFalse();      // space
        // > 60 chars
        String longCode = "crm." + "x".repeat(60);
        assertThat(namer.isValidCode(longCode)).isFalse();
    }

    @Test
    @DisplayName("renameDraft on non-existent pid returns false, doesn't throw")
    void rename_unknown_pid_graceful() {
        assertThat(namer.renameDraft(tenantId, "01NOSUCHPID0000")).isFalse();
    }

    @Test
    @DisplayName("without configured LLM provider, renameDraft no-ops leaving auto.* name intact")
    void no_llm_config_is_graceful_noop() {
        String draftPid = seedDraftWithAutoName();
        String codeBefore = jdbc.queryForObject(
                "SELECT draft_skill_code FROM ab_agent_skill_draft WHERE pid = ?",
                String.class, draftPid);
        assertThat(codeBefore).startsWith("auto.");

        // Integration test env has no LLM provider configured for this tenant.
        boolean renamed = namer.renameDraft(tenantId, draftPid);
        assertThat(renamed).isFalse();

        String codeAfter = jdbc.queryForObject(
                "SELECT draft_skill_code FROM ab_agent_skill_draft WHERE pid = ?",
                String.class, draftPid);
        assertThat(codeAfter).as("auto.* name preserved when LLM unavailable").isEqualTo(codeBefore);
    }

    @Test
    @DisplayName("renameAllAutoDrafts returns 0 when no drafts / no LLM configured")
    void batch_rename_returns_zero_without_llm() {
        seedDraftWithAutoName();
        int renamed = namer.renameAllAutoDrafts(tenantId);
        assertThat(renamed).isZero();
    }
}
