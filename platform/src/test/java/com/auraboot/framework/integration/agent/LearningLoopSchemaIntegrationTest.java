package com.auraboot.framework.integration.agent;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-16: Learning Loop 3-table schema invariants
 * (learning-loop.md §3.3 / §4.7 / §6.2).
 *
 * This is a schema-only PR — no Java services yet. These tests pin the
 * invariants that downstream PR-17/18/19 will depend on:
 *   - pattern_hash unique across ab_agent_learning_pattern rows
 *   - JSONB columns round-trip untouched
 *   - defaults set correct initial status for the lifecycle state machine
 *   - (draft, run) rows wire to draft_skill_id / draft_id correctly
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("Learning Loop schema (PR-16)")
class LearningLoopSchemaIntegrationTest extends BaseIntegrationTest {

    @Autowired private JdbcTemplate jdbc;

    /** Short random tag that fits inside VARCHAR(26) even after appending a 10-char suffix. */
    private String tag;

    @BeforeEach
    void setup() {
        // 6-char base36 + "_" = 7 chars prefix → leaves 19 chars for suffixes.
        tag = "ll" + Long.toString(System.nanoTime() & 0xffffff, 36) + "_";
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_shadow_run WHERE pid LIKE ?", tag + "%");
        jdbc.update("DELETE FROM ab_agent_skill_draft WHERE pid LIKE ?", tag + "%");
        jdbc.update("DELETE FROM ab_agent_learning_pattern WHERE pid LIKE ?", tag + "%");
    }

    private String pid(String suffix) {
        String v = tag + suffix;
        if (v.length() > 26) {
            throw new IllegalStateException("test pid > 26 chars: " + v);
        }
        return v;
    }

    // -----------------------------------------------------------------------
    // ab_agent_learning_pattern
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("learning_pattern insert defaults status='OBSERVED' and sets timestamps")
    void pattern_defaults() {
        String patternPid = pid("p1");
        jdbc.update("INSERT INTO ab_agent_learning_pattern " +
                        "(pid, tenant_id, pattern_hash, pattern_signature) " +
                        "VALUES (?, ?, ?, ?::jsonb)",
                patternPid, 1L, "hash_" + patternPid, "{\"cmd\":\"crm_lead.create\"}");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, invocation_count, first_seen_at, last_observed_at " +
                        "FROM ab_agent_learning_pattern WHERE pid = ?", patternPid);
        assertThat(row.get("status")).isEqualTo("OBSERVED");
        assertThat(((Number) row.get("invocation_count")).intValue()).isEqualTo(0);
        assertThat(row.get("first_seen_at")).isNotNull();
        assertThat(row.get("last_observed_at")).isNotNull();
    }

    @Test
    @DisplayName("learning_pattern.pattern_hash has a unique constraint (idempotent upsert key)")
    void pattern_hash_unique() {
        String hash = "hash_dupe_" + tag;
        jdbc.update("INSERT INTO ab_agent_learning_pattern (pid, pattern_hash, pattern_signature) " +
                        "VALUES (?, ?, '{}')", pid("a"), hash);

        assertThatThrownBy(() ->
                jdbc.update("INSERT INTO ab_agent_learning_pattern (pid, pattern_hash, pattern_signature) " +
                        "VALUES (?, ?, '{}')", pid("b"), hash))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("learning_pattern pattern_signature JSONB round-trips complex structure")
    void pattern_signature_jsonb() {
        String patternPid = pid("p_sig");
        String signatureJson = "{" +
                "\"cmd\":\"crm_lead.update\"," +
                "\"target_model\":\"crm_lead\"," +
                "\"action_type\":\"update\"," +
                "\"fields\":[\"status\",\"owner_id\"]," +
                "\"nested\":{\"k\":1}" +
                "}";

        jdbc.update("INSERT INTO ab_agent_learning_pattern " +
                        "(pid, pattern_hash, pattern_signature) " +
                        "VALUES (?, ?, ?::jsonb)",
                patternPid, "hash_" + patternPid, signatureJson);

        String stored = jdbc.queryForObject(
                "SELECT pattern_signature::text FROM ab_agent_learning_pattern WHERE pid = ?",
                String.class, patternPid);
        // PostgreSQL normalises whitespace but preserves structure/keys.
        assertThat(stored).contains("\"cmd\": \"crm_lead.update\"")
                .contains("\"target_model\": \"crm_lead\"")
                .contains("\"fields\": [\"status\", \"owner_id\"]")
                .contains("\"nested\": {\"k\": 1}");
    }

    // -----------------------------------------------------------------------
    // ab_agent_skill_draft
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("skill_draft insert defaults status='DRAFT_PENDING_REVIEW'")
    void skill_draft_defaults() {
        String draftPid = pid("d1");
        jdbc.update("INSERT INTO ab_agent_skill_draft " +
                        "(pid, tenant_id, draft_skill_code, contract_yaml, source_pattern_hash) " +
                        "VALUES (?, ?, ?, ?, ?)",
                draftPid, 1L, "dsl.crm_lead_weekly_report", "name: weekly_report\n", "hash_source_pattern");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, created_at, reviewed_at, shadow_started_at, promoted_at " +
                        "FROM ab_agent_skill_draft WHERE pid = ?", draftPid);
        assertThat(row.get("status")).isEqualTo("DRAFT_PENDING_REVIEW");
        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("reviewed_at")).isNull();
        assertThat(row.get("shadow_started_at")).isNull();
        assertThat(row.get("promoted_at")).isNull();
    }

    @Test
    @DisplayName("skill_draft.source_pattern_hash is NOT NULL (draft must derive from a pattern)")
    void skill_draft_requires_source() {
        String draftPid = pid("d_no_src");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO ab_agent_skill_draft (pid, contract_yaml) VALUES (?, ?)",
                draftPid, "yaml"))
                .hasMessageContaining("source_pattern_hash");
    }

    @Test
    @DisplayName("skill_draft.derived_from_runs JSONB round-trips an array of run refs")
    void skill_draft_derived_from_runs() {
        String draftPid = pid("d_runs");
        String runsJson = "[{\"run_id\":\"01RUN1\"},{\"run_id\":\"01RUN2\"},{\"run_id\":\"01RUN3\"}]";
        jdbc.update("INSERT INTO ab_agent_skill_draft " +
                        "(pid, contract_yaml, source_pattern_hash, derived_from_runs) " +
                        "VALUES (?, ?, ?, ?::jsonb)",
                draftPid, "yaml", "hash_1", runsJson);

        String stored = jdbc.queryForObject(
                "SELECT derived_from_runs::text FROM ab_agent_skill_draft WHERE pid = ?",
                String.class, draftPid);
        assertThat(stored).contains("01RUN1", "01RUN2", "01RUN3");
    }

    // -----------------------------------------------------------------------
    // ab_agent_shadow_run
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("shadow_run requires tenant_id + draft_id + original_run_id (NOT NULL)")
    void shadow_run_required_fks() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO ab_agent_shadow_run (pid) VALUES (?)", pid("sr_bad")))
                .hasMessageContaining("tenant_id");
    }

    @Test
    @DisplayName("shadow_run output_diff JSONB round-trips")
    void shadow_run_output_diff() {
        String draftPid = pid("d_for_shadow");
        jdbc.update("INSERT INTO ab_agent_skill_draft (pid, contract_yaml, source_pattern_hash) VALUES (?, ?, ?)",
                draftPid, "y", "h");

        String shadowPid = pid("sr_diff");
        String diffJson = "{\"missing_fields\":[\"owner_id\"],\"mismatch_count\":2}";
        jdbc.update("INSERT INTO ab_agent_shadow_run " +
                        "(pid, tenant_id, draft_id, original_run_id, output_match, output_diff, fidelity_match) " +
                        "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)",
                shadowPid, 1L, draftPid, "01ORIGRUN", false, diffJson, true);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT output_match, fidelity_match, output_diff::text AS diff " +
                        "FROM ab_agent_shadow_run WHERE pid = ?", shadowPid);
        assertThat(row.get("output_match")).isEqualTo(false);
        assertThat(row.get("fidelity_match")).isEqualTo(true);
        assertThat((String) row.get("diff")).contains("missing_fields").contains("owner_id");
    }

    @Test
    @DisplayName("shadow_run idx_shadow_run_draft_created orders by created_at DESC for the draft")
    void shadow_run_draft_order() {
        String draftPid = pid("d_order");
        jdbc.update("INSERT INTO ab_agent_skill_draft (pid, contract_yaml, source_pattern_hash) VALUES (?, ?, ?)",
                draftPid, "y", "h");

        for (int i = 0; i < 3; i++) {
            String srPid = pid("sr_order_" + i);
            jdbc.update("INSERT INTO ab_agent_shadow_run " +
                            "(pid, tenant_id, draft_id, original_run_id, shadow_status) " +
                            "VALUES (?, 1, ?, ?, 'success')",
                    srPid, draftPid, "01ORIGRUN_" + i);
        }

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT pid FROM ab_agent_shadow_run WHERE draft_id = ? ORDER BY created_at DESC",
                draftPid);
        assertThat(rows).hasSize(3);
        // Most recently inserted should be first.
        assertThat(rows.get(0).get("pid")).asString().contains("sr_order_2");
    }

    // -----------------------------------------------------------------------
    // Cross-table lifecycle wiring
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("pattern → draft → shadow lifecycle wires via pid references")
    void lifecycle_references() {
        String patternPid = pid("p_lc");
        String patternHash = "hash_" + patternPid;
        jdbc.update("INSERT INTO ab_agent_learning_pattern (pid, pattern_hash, pattern_signature, status) " +
                        "VALUES (?, ?, '{}', 'DRAFT_GENERATED')", patternPid, patternHash);

        String draftPid = pid("d_lc");
        jdbc.update("INSERT INTO ab_agent_skill_draft " +
                        "(pid, contract_yaml, source_pattern_hash, status) " +
                        "VALUES (?, ?, ?, 'SHADOW_RUNNING')",
                draftPid, "yaml", patternHash);
        jdbc.update("UPDATE ab_agent_learning_pattern SET draft_skill_id = ?, status = 'SHADOW' WHERE pid = ?",
                draftPid, patternPid);

        String shadowPid = pid("sr_lc");
        jdbc.update("INSERT INTO ab_agent_shadow_run " +
                        "(pid, tenant_id, draft_id, original_run_id, shadow_status, output_match) " +
                        "VALUES (?, 1, ?, '01RUN_ORIG', 'success', true)",
                shadowPid, draftPid);

        // The classic join: for a given pattern, find all shadow runs of its draft.
        List<Map<String, Object>> joined = jdbc.queryForList(
                "SELECT sr.pid AS shadow_pid, sr.output_match, d.status AS draft_status " +
                        "FROM ab_agent_learning_pattern p " +
                        "JOIN ab_agent_skill_draft d ON d.pid = p.draft_skill_id " +
                        "JOIN ab_agent_shadow_run sr ON sr.draft_id = d.pid " +
                        "WHERE p.pid = ?", patternPid);
        assertThat(joined).hasSize(1);
        assertThat(joined.get(0).get("draft_status")).isEqualTo("SHADOW_RUNNING");
        assertThat(joined.get(0).get("output_match")).isEqualTo(true);
    }
}
