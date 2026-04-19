package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.service.UserSoulProfileDeriver;
import com.auraboot.framework.agent.service.UserSoulProfileDeriver.DerivationResult;
import com.auraboot.framework.agent.service.UserSoulProfileDeriver.Outcome;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** PR-75 Phase 1 — end-to-end behaviour of {@link UserSoulProfileDeriver}. */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("User Soul Profile deriver (PR-75)")
class UserSoulProfileDeriverIntegrationTest extends BaseIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserSoulProfileDeriver deriver;

    private Long tenantId;
    private String userId;
    private String tag;

    @BeforeEach
    void setup() {
        tenantId = 9_760_000L + System.nanoTime() % 10_000;
        userId = "uu_" + Long.toString(System.nanoTime() & 0xffff, 36);
        tag = "usd" + Long.toString(System.nanoTime() & 0xfffff, 36) + "_";
        // Default: derivation enabled for direct tests; runScheduled test
        // will flip it back to false via reflection.
        ReflectionTestUtils.setField(deriver, "enabled", true);
        ReflectionTestUtils.setField(deriver, "minMemories", 3);
        ReflectionTestUtils.setField(deriver, "lookBackDays", 90);
        ReflectionTestUtils.setField(deriver, "llmEnabled", false);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_user_soul_profile WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_agent_memory WHERE tenant_id = ?", tenantId);
    }

    private void seedMemory(String pidSuffix, String category, String title,
                            String content, int importance) {
        jdbc.update("INSERT INTO ab_agent_memory "
                        + "(pid, tenant_id, memory_agent_id, memory_type, category, "
                        + " memory_title, memory_content, importance, shareable, scope, scope_key, "
                        + " created_at, updated_at, deleted_flag) "
                        + "VALUES (?, ?, 'default', 'fact', ?, ?, ?, ?, FALSE, 'user', ?, NOW(), NOW(), FALSE)",
                tag + pidSuffix, tenantId, category, title, content, importance, userId);
    }

    @Test
    @DisplayName("skips when fewer than min-memories available")
    void skipsTooLittleSignal() {
        seedMemory("m1", "user", "a", "hi", 7);
        seedMemory("m2", "user", "b", "hello", 7);
        DerivationResult r = deriver.deriveForUser(tenantId, userId);
        assertThat(r.outcome()).isEqualTo(Outcome.SKIPPED_TOO_LITTLE_SIGNAL);
        Long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_user_soul_profile WHERE tenant_id = ? AND user_id = ?",
                Long.class, tenantId, userId);
        assertThat(rows).isZero();
    }

    @Test
    @DisplayName("produces DRAFT from 5 seeded memories with source_memory_pids populated")
    void producesDraft() {
        for (int i = 1; i <= 5; i++) {
            // Phase 4 narrowed deriver to L2 (category IN user/agent) — seed all
            // as 'user' so the 5-memory drafting scenario still exercises the
            // full projection path; prior mix of user+general was arbitrary.
            seedMemory("m" + i, "user",
                    "title_" + i, "concise bullet content " + i, 8);
        }
        DerivationResult r = deriver.deriveForUser(tenantId, userId);
        assertThat(r.outcome()).isEqualTo(Outcome.DRAFTED);
        assertThat(r.profilePid()).isNotBlank();

        var row = jdbc.queryForMap(
                "SELECT status, version, profile_hash, source_memory_pids::text AS pids, "
                        + " derivation_model, derivation_confidence "
                        + "FROM ab_agent_user_soul_profile WHERE pid = ?", r.profilePid());
        assertThat(row.get("status")).isEqualTo("draft");
        assertThat(((Number) row.get("version")).intValue()).isEqualTo(1);
        assertThat((String) row.get("pids")).contains(tag + "m1").contains(tag + "m5");
        assertThat(row.get("derivation_model")).isEqualTo("template:v1");
        assertThat(((Number) row.get("derivation_confidence")).doubleValue()).isBetween(0.0, 1.0);
    }

    @Test
    @DisplayName("idempotent: same inputs + ACTIVE prior with same hash → skipped_no_change")
    void idempotentSkipsWhenUnchanged() {
        for (int i = 1; i <= 5; i++) {
            seedMemory("m" + i, "user", "t" + i, "concise bullet " + i, 8);
        }
        DerivationResult first = deriver.deriveForUser(tenantId, userId);
        assertThat(first.outcome()).isEqualTo(Outcome.DRAFTED);
        String firstHash = jdbc.queryForObject(
                "SELECT profile_hash FROM ab_agent_user_soul_profile WHERE pid = ?",
                String.class, first.profilePid());
        assertThat(firstHash).isNotBlank();

        // Promote DRAFT → ACTIVE so second run sees prior hash.
        jdbc.update("UPDATE ab_agent_user_soul_profile SET status = 'active', activated_at = NOW() "
                + "WHERE pid = ?", first.profilePid());

        DerivationResult second = deriver.deriveForUser(tenantId, userId);
        assertThat(second.outcome()).isEqualTo(Outcome.SKIPPED_NO_CHANGE);
        // ProfileHasher must produce byte-identical output for the same inputs —
        // the second run's computed hash (returned as profileHash on skip) must
        // equal the persisted first-run hash.
        assertThat(second.profileHash()).isEqualTo(firstHash);

        Long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_user_soul_profile WHERE tenant_id = ? AND user_id = ?",
                Long.class, tenantId, userId);
        assertThat(rows).isEqualTo(1L);
    }

    @Test
    @DisplayName("ProfileHasher idempotency: identical inputs produce identical hashes across back-to-back runs")
    void profileHasherIdempotentAcrossRuns() {
        for (int i = 1; i <= 5; i++) {
            seedMemory("m" + i, "user", "t" + i, "concise bullet " + i, 8);
        }
        // Run 1 — produces DRAFT.
        DerivationResult first = deriver.deriveForUser(tenantId, userId);
        assertThat(first.outcome()).isEqualTo(Outcome.DRAFTED);
        String firstHash = jdbc.queryForObject(
                "SELECT profile_hash FROM ab_agent_user_soul_profile WHERE pid = ?",
                String.class, first.profilePid());

        // Promote DRAFT → ACTIVE so run 2 compares against a prior ACTIVE row.
        jdbc.update("UPDATE ab_agent_user_soul_profile SET status = 'active', activated_at = NOW() "
                + "WHERE pid = ?", first.profilePid());

        // Run 2 — same seeded memories, no changes → must skip, hash must match.
        DerivationResult second = deriver.deriveForUser(tenantId, userId);
        assertThat(second.outcome()).isEqualTo(Outcome.SKIPPED_NO_CHANGE);
        assertThat(second.profileHash())
                .as("ProfileHasher must be deterministic for identical inputs")
                .isEqualTo(firstHash);

        // Exactly one persisted row — no duplicate DRAFT inserted.
        Long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_user_soul_profile WHERE tenant_id = ? AND user_id = ?",
                Long.class, tenantId, userId);
        assertThat(rows).isEqualTo(1L);
    }

    @Test
    @DisplayName("tenant isolation: tenant-scope memories in a different tenant are ignored")
    void tenantIsolation() {
        Long otherTenant = tenantId + 1;
        // Seed tenant-scope memories (not user-scope) under otherTenant.
        // AgentMemoryService.loadScopedByImportance filters tenant-scope by
        // tenant_id, so these must NOT surface for our tenant.
        for (int i = 1; i <= 5; i++) {
            jdbc.update("INSERT INTO ab_agent_memory "
                            + "(pid, tenant_id, memory_agent_id, memory_type, category, memory_title, "
                            + " memory_content, importance, shareable, scope, scope_key, "
                            + " created_at, updated_at, deleted_flag) "
                            + "VALUES (?, ?, 'default', 'fact', 'profile', 'x', 'y', 8, TRUE, 'tenant', NULL, NOW(), NOW(), FALSE)",
                    tag + "o" + i, otherTenant);
        }
        try {
            // No memories for our tenant/user → too little signal.
            DerivationResult r = deriver.deriveForUser(tenantId, userId);
            assertThat(r.outcome()).isEqualTo(Outcome.SKIPPED_TOO_LITTLE_SIGNAL);
            Long rows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ab_agent_user_soul_profile WHERE tenant_id = ?",
                    Long.class, tenantId);
            assertThat(rows).isZero();
        } finally {
            jdbc.update("DELETE FROM ab_agent_memory WHERE tenant_id = ?", otherTenant);
        }
    }

    @Test
    @DisplayName("runScheduled is a no-op when derivation.enabled = false")
    void scheduledDisabled() {
        for (int i = 1; i <= 5; i++) {
            seedMemory("m" + i, "user", "t", "x", 8);
        }
        ReflectionTestUtils.setField(deriver, "enabled", false);
        int drafted = deriver.runScheduled();
        assertThat(drafted).isZero();
        Long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_user_soul_profile WHERE tenant_id = ? AND user_id = ?",
                Long.class, tenantId, userId);
        assertThat(rows).isZero();
    }

    @Test
    @DisplayName("runScheduled discovers candidate users and drafts when enabled")
    void scheduledDiscoversUsers() {
        for (int i = 1; i <= 5; i++) {
            seedMemory("m" + i, "user", "t", "x", 8);
        }
        int drafted = deriver.runScheduled();
        assertThat(drafted).isGreaterThanOrEqualTo(1);
        List<String> statuses = jdbc.queryForList(
                "SELECT status FROM ab_agent_user_soul_profile WHERE tenant_id = ? AND user_id = ?",
                String.class, tenantId, userId);
        assertThat(statuses).contains("draft");
    }

    @Test
    @DisplayName("deriver skips forgotten user (tombstone)")
    void deriverSkipsForgottenUser() {
        for (int i = 1; i <= 5; i++) {
            seedMemory("m" + i, "user", "t", "x", 8);
        }
        // Insert a tombstone row — status=ARCHIVED with edited_fields._forgotten=true.
        jdbc.update("INSERT INTO ab_agent_user_soul_profile "
                        + "(pid, tenant_id, user_id, version, status, profile, profile_hash, "
                        + " edited_fields, hidden_at, created_at) "
                        + "VALUES (?, ?, ?, 1, 'archived', '{}'::jsonb, ?, "
                        + " '{\"_forgotten\":true}'::jsonb, NOW(), NOW())",
                tag + "tomb", tenantId, userId, "h:tomb");
        DerivationResult r = deriver.deriveForUser(tenantId, userId);
        assertThat(r.outcome()).isEqualTo(Outcome.SKIPPED_FORGOTTEN);
        Long newDrafts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_user_soul_profile "
                        + "WHERE tenant_id = ? AND user_id = ? AND status = 'draft'",
                Long.class, tenantId, userId);
        assertThat(newDrafts).isZero();
    }

    @Test
    @DisplayName("advisory lock 7306 prevents concurrent runScheduled")
    void advisoryLockSerialisesScheduled() throws InterruptedException {
        for (int i = 1; i <= 5; i++) {
            seedMemory("m" + i, "user", "t", "x", 8);
        }
        // Pre-acquire the lock on this connection thread via a dedicated DataSource
        // connection isn't portable through JdbcTemplate, so instead we run
        // two parallel runScheduled() calls and assert that the total
        // drafted rows is still <= 1 (second caller either skips due to
        // lock OR sees the hash from first caller).
        Long baseline = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_user_soul_profile WHERE tenant_id = ?",
                Long.class, tenantId);
        assertThat(baseline).isZero();

        Thread t1 = new Thread(deriver::runScheduled);
        Thread t2 = new Thread(deriver::runScheduled);
        t1.start(); t2.start();
        t1.join(); t2.join();

        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_user_soul_profile WHERE tenant_id = ? AND user_id = ?",
                Long.class, tenantId, userId);
        // Either one of the threads lost the lock (0 extra) or it ran and
        // saw the newly-written hash (skipped_no_change). Never >1.
        assertThat(total).isEqualTo(1L);
    }
}
