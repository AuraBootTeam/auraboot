package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.service.ShadowRunScheduler;
import com.auraboot.framework.agent.service.ShadowToolInvoker;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-37: ShadowRunScheduler — periodic driver for Shadow Mode execution.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("ShadowRunScheduler (PR-37)")
@Import(ShadowRunSchedulerIntegrationTest.FixedInvokerTestConfig.class)
class ShadowRunSchedulerIntegrationTest extends BaseIntegrationTest {

    /** Deterministic stub invoker for {@code mcp_fixed} — exercises the canonical-hash match path. */
    @TestConfiguration
    static class FixedInvokerTestConfig {
        @Bean
        ShadowToolInvoker mcpFixedInvoker() {
            return new ShadowToolInvoker() {
                @Override public boolean supports(String toolRef) { return "mcp_fixed".equals(toolRef); }
                @Override public Map<String, Object> invokeShadow(Long tenantId, String toolRef, Map<String, Object> args) {
                    // Intentionally build with reversed key order — the canonical hasher
                    // must normalize this so the test below can pre-compute the match hash.
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("total", 3L);
                    out.put("query_code", "stub");
                    return out;
                }
            };
        }
    }

    @Autowired private ShadowRunScheduler scheduler;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    private Long tenantId;

    @BeforeEach
    void setup() {
        tenantId = 9_550_000L + System.nanoTime() % 100_000;
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_shadow_run WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_agent_skill_draft WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_agent_action WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_agent_dry_run_support WHERE tenant_id = ?", tenantId);
        // Safety: release advisory lock in case a test aborted before finally{}.
        jdbc.execute("SELECT pg_advisory_unlock_all()");
    }

    private String seedDraft(String status, String yaml, String derivedJson) {
        String pid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_skill_draft " +
                        "(pid, tenant_id, draft_skill_code, source_pattern_hash, contract_yaml, " +
                        " derived_from_runs, status, created_at, reviewed_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, NOW(), NOW())",
                pid, tenantId, "auto.t." + pid.substring(0, 6), "h_" + pid, yaml,
                derivedJson, status);
        return pid;
    }

    private void seedAction(String runId) {
        jdbc.update("INSERT INTO ab_agent_action " +
                        "(pid, tenant_id, run_id, action_code, action_type, target_model, " +
                        " action_status, executed_at, updated_at) " +
                        "VALUES (?, ?, ?, 'crm.lead.list', 'query', 'crm_lead', " +
                        " 'success', NOW() - INTERVAL '100 milliseconds', NOW())",
                UniqueIdGenerator.generate(), tenantId, runId);
    }

    private void seedActionWithSnapshot(String runId, String snapshotJson) {
        jdbc.update("INSERT INTO ab_agent_action " +
                        "(pid, tenant_id, run_id, action_code, action_type, target_model, " +
                        " action_status, after_snapshot, executed_at, updated_at) " +
                        "VALUES (?, ?, ?, 'crm.lead.list', 'query', 'crm_lead', " +
                        " 'success', ?::jsonb, NOW() - INTERVAL '100 milliseconds', NOW())",
                UniqueIdGenerator.generate(), tenantId, runId, snapshotJson);
    }

    @Test
    @DisplayName("eligible REVIEWED_OK draft → records shadow runs and flips to SHADOW_RUNNING")
    void eligible_draft_runs_and_transitions() {
        String run1 = "RUN1" + System.nanoTime();
        String run2 = "RUN2" + System.nanoTime();
        seedAction(run1);
        seedAction(run2);

        String yaml = "substrate: dsl\naction_type: query\ntool_refs:\n  - nq_leads\n";
        String derived = "[{\"run_id\":\"" + run1 + "\"},{\"run_id\":\"" + run2 + "\"}]";
        String draftPid = seedDraft("REVIEWED_OK", yaml, derived);

        int executed = scheduler.runOnce();
        assertThat(executed).isGreaterThanOrEqualTo(1);

        String status = jdbc.queryForObject(
                "SELECT status FROM ab_agent_skill_draft WHERE pid = ?", String.class, draftPid);
        assertThat(status).isEqualTo("SHADOW_RUNNING");

        Integer shadowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_shadow_run WHERE draft_id = ?",
                Integer.class, draftPid);
        assertThat(shadowCount).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("idempotent — same pair (draft, original_run) not shadowed twice")
    void idempotent_on_rerun() {
        String run1 = "RUNA" + System.nanoTime();
        seedAction(run1);
        String yaml = "substrate: dsl\naction_type: query\ntool_refs:\n  - nq_leads\n";
        String draftPid = seedDraft("REVIEWED_OK", yaml,
                "[{\"run_id\":\"" + run1 + "\"}]");

        int firstPass = scheduler.runOnce();
        int secondPass = scheduler.runOnce();
        assertThat(firstPass).isGreaterThanOrEqualTo(1);
        assertThat(secondPass).isZero();

        Integer shadowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_shadow_run WHERE draft_id = ?",
                Integer.class, draftPid);
        assertThat(shadowCount).isEqualTo(1);
    }

    @Test
    @DisplayName("DRAFT_PENDING_REVIEW drafts are not picked up")
    void pending_review_ignored() {
        String run1 = "RUNP" + System.nanoTime();
        seedAction(run1);
        String yaml = "substrate: dsl\naction_type: query\ntool_refs:\n  - nq_leads\n";
        String draftPid = seedDraft("DRAFT_PENDING_REVIEW", yaml,
                "[{\"run_id\":\"" + run1 + "\"}]");

        int executed = scheduler.runOnce();

        Integer shadowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_shadow_run WHERE draft_id = ?",
                Integer.class, draftPid);
        assertThat(shadowCount).isZero();
        // executed tally may include other tenants' drafts; only our draft-scope is asserted.
        assertThat(executed).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("PR-54: advisory lock held on another session → runOnce returns 0 and writes nothing")
    void advisory_lock_prevents_reentry() throws Exception {
        String run1 = "RUNL" + System.nanoTime();
        seedAction(run1);
        String yaml = "substrate: dsl\naction_type: query\ntool_refs:\n  - nq_leads\n";
        String draftPid = seedDraft("REVIEWED_OK", yaml,
                "[{\"run_id\":\"" + run1 + "\"}]");

        // Hold the advisory lock on a *separate* connection — otherwise pg_try_advisory_lock
        // on the same session would succeed (advisory locks are session-scoped).
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(true);
            try (PreparedStatement ps = blocker.prepareStatement("SELECT pg_advisory_lock(?)")) {
                ps.setLong(1, 7301L);
                ps.execute();
            }

            int executed = scheduler.runOnce();
            assertThat(executed).isZero();

            Integer shadowCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ab_agent_shadow_run WHERE draft_id = ?",
                    Integer.class, draftPid);
            assertThat(shadowCount).isZero();

            try (PreparedStatement ps = blocker.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                ps.setLong(1, 7301L);
                ps.execute();
            }
        }

        // After release, a second tick must proceed normally.
        int executedAfter = scheduler.runOnce();
        assertThat(executedAfter).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("PR-54: output_match=true when original after_snapshot canonical-hash equals shadow result hash")
    void hash_match_when_original_equals_shadow() {
        // dry-run registry: let mcp_fixed pass eligibility as FULL
        jdbc.update("INSERT INTO ab_agent_dry_run_support " +
                        "(pid, tenant_id, tool_ref_pattern, support_level, created_at, updated_at) " +
                        "VALUES (?, ?, 'mcp_*', 'FULL', NOW(), NOW())",
                UniqueIdGenerator.generate(), tenantId);

        // Our FixedInvokerTestConfig returns {query_code:"stub", total:3}.
        // ShadowExecutor wraps each tool_ref result as {tool_ref, result}; the
        // complete payload that gets hashed is a list of one such map. We seed
        // the original after_snapshot to the same JSON — any key ordering works
        // because the hasher is canonical.
        String snapshot = "[{\"tool_ref\":\"mcp_fixed\",\"result\":{\"total\":3,\"query_code\":\"stub\"}}]";

        String runId = "RUNM" + System.nanoTime();
        seedActionWithSnapshot(runId, snapshot);

        String yaml = "substrate: dsl\naction_type: query\ntool_refs:\n  - mcp_fixed\n";
        String draftPid = seedDraft("REVIEWED_OK", yaml,
                "[{\"run_id\":\"" + runId + "\"}]");

        int executed = scheduler.runOnce();
        assertThat(executed).isGreaterThanOrEqualTo(1);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT output_match, shadow_output_hash, original_output_hash " +
                        "FROM ab_agent_shadow_run WHERE draft_id = ?", draftPid);
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("shadow_output_hash")).isNotNull();
        assertThat(row.get("original_output_hash")).isEqualTo(row.get("shadow_output_hash"));
        assertThat(row.get("output_match")).isEqualTo(Boolean.TRUE);
    }

    @Test
    @DisplayName("PR-59: concurrent runOnce() calls → only one instance records runs (no duplicates)")
    void concurrent_instances() throws Exception {
        String run1 = "RUNC" + System.nanoTime();
        seedAction(run1);
        String yaml = "substrate: dsl\naction_type: query\ntool_refs:\n  - nq_leads\n";
        String draftPid = seedDraft("REVIEWED_OK", yaml,
                "[{\"run_id\":\"" + run1 + "\"}]");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> task = () -> scheduler.runOnce();
            Future<Integer> f1 = pool.submit(task);
            Future<Integer> f2 = pool.submit(task);
            int a = f1.get(30, TimeUnit.SECONDS);
            int b = f2.get(30, TimeUnit.SECONDS);

            // One tick records >=1 shadow run, the other is shut out by the
            // advisory lock and returns 0. Sum of the two must still match the
            // single-instance outcome.
            assertThat(a + b).as("combined executions").isGreaterThanOrEqualTo(1);
            assertThat(Math.min(a, b)).as("losing tick must be 0").isZero();

            Integer shadowCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ab_agent_shadow_run WHERE draft_id = ?",
                    Integer.class, draftPid);
            // Only one (draft, run_id) row is created — never doubled.
            assertThat(shadowCount).isEqualTo(1);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("PR-59: lock held across all internal jdbc calls — nested runOnce sees lock taken")
    void lock_held_across_jdbc_calls() throws Exception {
        String runId = "RUNN" + System.nanoTime();
        seedAction(runId);
        String yaml = "substrate: dsl\naction_type: query\ntool_refs:\n  - nq_leads\n";
        String draftPid = seedDraft("REVIEWED_OK", yaml,
                "[{\"run_id\":\"" + runId + "\"}]");

        // Hold the advisory lock on a dedicated connection with an explicit
        // pg_advisory_lock so the scheduler's pg_try_advisory_lock sees it
        // taken on every physical connection it might borrow. If the fix
        // regresses (lock unlock leaks), a subsequent runOnce would find the
        // lock mysteriously free because the unlock landed on a different
        // connection than the acquire. The session-scoped lock held here
        // guarantees any pooled connection the scheduler uses sees occupied.
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(true);
            try (PreparedStatement ps = blocker.prepareStatement("SELECT pg_advisory_lock(?)")) {
                ps.setLong(1, 7301L);
                ps.execute();
            }

            // Run multiple ticks while the external session holds the lock.
            // Each tick must consistently return 0 — proving that the
            // scheduler's own unlock never leaked into a pooled connection
            // that would steal the lock from the blocker.
            for (int i = 0; i < 3; i++) {
                int executed = scheduler.runOnce();
                assertThat(executed).as("attempt " + i).isZero();
            }

            Integer shadowCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ab_agent_shadow_run WHERE draft_id = ?",
                    Integer.class, draftPid);
            assertThat(shadowCount).isZero();

            try (PreparedStatement ps = blocker.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                ps.setLong(1, 7301L);
                ps.execute();
            }
        }
    }

    @Test
    @DisplayName("ineligible draft (write substrate, no dry-run support) produces no shadow rows")
    void ineligible_write_no_runs() {
        String run1 = "RUNW" + System.nanoTime();
        seedAction(run1);
        // api_* is NONE by platform default; draft is ineligible.
        String yaml = "substrate: api\naction_type: update\ntool_refs:\n  - api_stripe_charge\n";
        String draftPid = seedDraft("REVIEWED_OK", yaml,
                "[{\"run_id\":\"" + run1 + "\"}]");

        scheduler.runOnce();

        Integer shadowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_shadow_run WHERE draft_id = ?",
                Integer.class, draftPid);
        assertThat(shadowCount).isZero();
        // Status must remain REVIEWED_OK — no shadow flipped it to SHADOW_RUNNING.
        String status = jdbc.queryForObject(
                "SELECT status FROM ab_agent_skill_draft WHERE pid = ?", String.class, draftPid);
        assertThat(status).isEqualTo("REVIEWED_OK");
    }
}
