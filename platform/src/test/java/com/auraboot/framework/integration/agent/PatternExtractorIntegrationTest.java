package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.service.PatternExtractor;
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
 * PR-17: PatternExtractor aggregates ab_agent_action rows into
 * ab_agent_learning_pattern rows. Tests pin the aggregation shape,
 * the quality filter (minInvocations / minSuccessRate), idempotent
 * re-run semantics, and the canonical pattern_hash contract.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("PatternExtractor (PR-17)")
class PatternExtractorIntegrationTest extends BaseIntegrationTest {

    @Autowired private PatternExtractor extractor;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;
    private String tag;

    @BeforeEach
    void setup() {
        tenantId = 9_500_000L + System.nanoTime() % 100_000;
        tag = "pex_" + Long.toString(System.nanoTime() & 0xffffff, 36) + "_";
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_action WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_agent_learning_pattern WHERE tenant_id = ?", tenantId);
    }

    /**
     * Seed {@code n} Action rows sharing the same (command_signature, model,
     * action_type) tuple. {@code successCount} of them have action_status=success,
     * the rest have 'failed' — so success_rate = successCount / n.
     */
    private void seedActions(String commandSig, String model, String actionType,
                              int total, int successCount) {
        for (int i = 0; i < total; i++) {
            boolean ok = i < successCount;
            jdbc.update("INSERT INTO ab_agent_action " +
                            "(pid, tenant_id, run_id, action_code, action_type, target_model, " +
                            " command_signature, action_status, executed_at, created_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())",
                    UniqueIdGenerator.generate(),
                    tenantId, UniqueIdGenerator.generate(),
                    model + "." + actionType, actionType, model,
                    commandSig,
                    ok ? "success" : "failed");
        }
    }

    // -----------------------------------------------------------------------

    @Test
    @DisplayName("qualifying pattern is upserted with invocation_count and success_rate")
    void qualifying_pattern_is_upserted() {
        String sig = tag + "sig_q";
        seedActions(sig, "crm_lead", "update", 10, 10);   // 10 invocations, 100% success

        int upserted = extractor.extractPatterns();
        assertThat(upserted).isGreaterThanOrEqualTo(1);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT invocation_count, success_rate, status " +
                        "FROM ab_agent_learning_pattern " +
                        "WHERE tenant_id = ? AND pattern_signature->>'command_signature' = ?",
                tenantId, sig);
        assertThat(((Number) row.get("invocation_count")).longValue()).isEqualTo(10L);
        assertThat(((Number) row.get("success_rate")).doubleValue()).isEqualTo(1.0);
        assertThat(row.get("status")).isEqualTo("OBSERVED");
    }

    @Test
    @DisplayName("below-threshold invocation_count skips insert entirely (no row written)")
    void below_min_invocations_filtered() {
        String sig = tag + "sig_low";
        seedActions(sig, "crm_lead", "update", 2, 2); // 2 < default 5

        extractor.extractPatterns();

        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_learning_pattern " +
                        "WHERE tenant_id = ? AND pattern_signature->>'command_signature' = ?",
                Integer.class, tenantId, sig);
        assertThat(cnt).isZero();
    }

    @Test
    @DisplayName("low success_rate is filtered (pattern observed but not inserted)")
    void low_success_rate_filtered() {
        String sig = tag + "sig_flaky";
        seedActions(sig, "crm_lead", "update", 10, 5); // 50% success < 0.80

        extractor.extractPatterns();

        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_learning_pattern " +
                        "WHERE tenant_id = ? AND pattern_signature->>'command_signature' = ?",
                Integer.class, tenantId, sig);
        assertThat(cnt).isZero();
    }

    @Test
    @DisplayName("re-running the extractor updates an existing row (idempotent)")
    void re_run_is_idempotent() {
        String sig = tag + "sig_idem";
        seedActions(sig, "crm_lead", "update", 10, 10);
        extractor.extractPatterns();

        String pidBefore = jdbc.queryForObject(
                "SELECT pid FROM ab_agent_learning_pattern " +
                        "WHERE tenant_id = ? AND pattern_signature->>'command_signature' = ?",
                String.class, tenantId, sig);

        // Add 5 more successful invocations, re-run.
        seedActions(sig, "crm_lead", "update", 5, 5);
        extractor.extractPatterns();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT pid, invocation_count FROM ab_agent_learning_pattern " +
                        "WHERE tenant_id = ? AND pattern_signature->>'command_signature' = ?",
                tenantId, sig);
        assertThat(row.get("pid")).as("same pid — row updated not re-inserted").isEqualTo(pidBefore);
        assertThat(((Number) row.get("invocation_count")).longValue()).isEqualTo(15L);
    }

    @Test
    @DisplayName("different (model, action_type) tuples produce distinct pattern rows")
    void distinct_patterns_per_tuple() {
        String sig = tag + "sig_same";
        seedActions(sig, "crm_lead",       "update", 10, 10);
        seedActions(sig, "crm_opportunity","update", 10, 10);
        seedActions(sig, "crm_lead",       "create", 10, 10);

        extractor.extractPatterns();

        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_learning_pattern " +
                        "WHERE tenant_id = ? AND pattern_signature->>'command_signature' = ?",
                Integer.class, tenantId, sig);
        assertThat(cnt).as("3 tuples → 3 distinct patterns").isEqualTo(3);
    }

    @Test
    @DisplayName("patternHash is deterministic + differs across tuples")
    void pattern_hash_contract() {
        PatternExtractor.PatternCandidate a = PatternExtractor.PatternCandidate.builder()
                .tenantId(1L).commandSignature("sig1").targetModel("crm_lead").actionType("update")
                .invocationCount(10).successRate(1.0).build();
        PatternExtractor.PatternCandidate aCopy = PatternExtractor.PatternCandidate.builder()
                .tenantId(1L).commandSignature("sig1").targetModel("crm_lead").actionType("update")
                .invocationCount(99).successRate(0.5).build();  // stats differ
        PatternExtractor.PatternCandidate b = PatternExtractor.PatternCandidate.builder()
                .tenantId(1L).commandSignature("sig1").targetModel("crm_lead").actionType("create")
                .invocationCount(10).successRate(1.0).build();

        String ha = extractor.patternHash(a);
        String haCopy = extractor.patternHash(aCopy);
        String hb = extractor.patternHash(b);

        assertThat(ha).as("stats don't enter the hash — only decision surface does").isEqualTo(haCopy);
        assertThat(ha).as("different action_type → different hash").isNotEqualTo(hb);
        assertThat(ha).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("actions with null command_signature are ignored (can't pattern-match blackbox)")
    void null_command_signature_ignored() {
        jdbc.update("INSERT INTO ab_agent_action " +
                        "(pid, tenant_id, run_id, action_code, action_type, target_model, " +
                        " action_status, executed_at, created_at) " +
                        "VALUES (?, ?, ?, 'x', 'query', 'crm_lead', 'success', NOW(), NOW())",
                tag + "nocs", tenantId, UniqueIdGenerator.generate());

        int upserted = extractor.extractPatterns();
        // Nothing for this tenant — row had no signature.
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_learning_pattern WHERE tenant_id = ?",
                Integer.class, tenantId);
        assertThat(cnt).isZero();
    }
}
