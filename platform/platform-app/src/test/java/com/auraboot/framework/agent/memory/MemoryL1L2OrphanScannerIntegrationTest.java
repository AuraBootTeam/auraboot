package com.auraboot.framework.agent.memory;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-84 / Phase 3 integration tests for {@link MemoryL1L2OrphanScanner}.
 *
 * <p>Uses {@code @Commit @Transactional(Propagation.NEVER)} rather than the
 * rolled-back harness because the scanner acquires a PostgreSQL advisory
 * lock inside its own {@code TransactionTemplate} — nesting that in a
 * rolled-back outer transaction would hold the lock until the outer commit
 * and serialise every test on {@link MemoryL1L2OrphanScanner#LOCK_KEY}.
 *
 * <p>Covers the brief's three cases:
 * <ol>
 *   <li>Normal catch-up: aged L1 row with {@code promoted_at IS NULL} and
 *       {@code importance >= gate} is promoted to L2 via the shared scoring
 *       pipeline, with a {@code L1_PROMOTED} audit row written.</li>
 *   <li>Skips already-promoted: rows whose {@code promoted_at} is not null
 *       (even if {@code category='session'} due to hypothetical demotion
 *       re-session) are excluded from the candidate set.</li>
 *   <li>Respects 1h age window: rows younger than 1h stay L1 untouched.</li>
 * </ol>
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("MemoryL1L2OrphanScanner (PR-84)")
class MemoryL1L2OrphanScannerIntegrationTest extends BaseIntegrationTest {

    private static final String TEST_PREFIX = "MOS_";

    @Autowired
    private MemoryL1L2OrphanScanner scanner;

    @Autowired
    private JdbcTemplate jdbc;

    private Long tenantId;
    private String agentCode;
    private String userId;

    @BeforeEach
    void setup() {
        tenantId = getTestTenant().getId();
        agentCode = TEST_PREFIX + "agent_" + UniqueIdGenerator.generate();
        userId = String.valueOf(getTestUser().getId());
    }

    @AfterEach
    void cleanup() {
        // Restrict cleanup to the rows this class created by prefix to avoid
        // clobbering sibling tests that share the tenant fixture.
        jdbc.update(
                "DELETE FROM ab_agent_memory_tier_event "
                        + " WHERE memory_pid IN ("
                        + "   SELECT pid FROM ab_agent_memory "
                        + "    WHERE tenant_id = ? AND memory_agent_id LIKE ?)",
                tenantId, TEST_PREFIX + "agent_%");
        jdbc.update(
                "DELETE FROM ab_agent_memory "
                        + " WHERE tenant_id = ? AND memory_agent_id LIKE ?",
                tenantId, TEST_PREFIX + "agent_%");
    }

    // ------------------------------------------------------------------
    // 1) Normal catch-up — aged orphan L1 gets promoted.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("normal catch-up: aged orphan L1 promoted via shared pipeline")
    void runOnce_promotesAgedOrphanL1() {
        String runId = UniqueIdGenerator.generate();
        // Age = 2h > 1h threshold, importance = 9 > gate, promoted_at = null.
        String pid = insertL1Aged(runId,
                TEST_PREFIX + "orphan-" + UniqueIdGenerator.generate()
                        + " user insists on vim keybindings everywhere",
                /*importance*/ 9, /*accessCount*/ 3, /*ageHours*/ 2);

        MemoryL1L2OrphanScanner.ScanSummary summary = scanner.runOnce();

        assertThat(summary.candidates()).isGreaterThanOrEqualTo(1);
        assertThat(summary.promoted()).isGreaterThanOrEqualTo(1);

        // The target row flipped to L2 and carries promotion stamps.
        String category = jdbc.queryForObject(
                "SELECT category FROM ab_agent_memory WHERE pid = ?",
                String.class, pid);
        assertThat(category).isEqualTo("user");

        Integer auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_memory_tier_event "
                        + " WHERE memory_pid = ? AND event_type = 'L1_PROMOTED'",
                Integer.class, pid);
        assertThat(auditCount).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 2) Skips already-promoted rows.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("skips rows with promoted_at set (already handled by event path)")
    void runOnce_skipsAlreadyPromoted() {
        String runId = UniqueIdGenerator.generate();
        String pid = insertL1Aged(runId,
                TEST_PREFIX + "already-" + UniqueIdGenerator.generate()
                        + " some historical preference",
                9, 0, 3);
        // Simulate a row the event listener already handled: promoted_at set,
        // category flipped to 'user'. Scanner must not re-touch it.
        jdbc.update(
                "UPDATE ab_agent_memory SET category='user', promoted_at = NOW() - INTERVAL '1 hour' "
                        + " WHERE pid = ?", pid);

        MemoryL1L2OrphanScanner.ScanSummary summary = scanner.runOnce();

        // No new audit row should be written for this pid (since it's not a
        // candidate anymore).
        Integer auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_memory_tier_event WHERE memory_pid = ?",
                Integer.class, pid);
        assertThat(auditCount).isZero();
        assertThat(summary).isNotNull();
    }

    // ------------------------------------------------------------------
    // 3) Respects 1h age window.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("respects 1h age window — fresh orphan L1 stays untouched")
    void runOnce_respectsAgeWindow() {
        String runId = UniqueIdGenerator.generate();
        // Age 5 minutes — well under the 1h floor.
        String pid = insertL1Aged(runId,
                TEST_PREFIX + "fresh-" + UniqueIdGenerator.generate()
                        + " brand new preference from this second",
                9, 0, 0);
        // Overwrite created_at to 5 minutes ago.
        jdbc.update("UPDATE ab_agent_memory SET created_at = NOW() - INTERVAL '5 minutes' "
                + " WHERE pid = ?", pid);

        scanner.runOnce();

        String category = jdbc.queryForObject(
                "SELECT category FROM ab_agent_memory WHERE pid = ?",
                String.class, pid);
        assertThat(category).isEqualTo("session");

        Integer auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_memory_tier_event WHERE memory_pid = ?",
                Integer.class, pid);
        assertThat(auditCount).isZero();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private String insertL1Aged(String runId, String content, int importance,
                                int accessCount, int ageHours) {
        String pid = UniqueIdGenerator.generate();
        jdbc.update(
                "INSERT INTO ab_agent_memory "
                        + "(pid, tenant_id, memory_agent_id, memory_type, category, "
                        + " memory_title, memory_content, importance, source_run_id, "
                        + " access_count, shareable, scope, scope_key, demotion_count, "
                        + " created_at, updated_at, deleted_flag) "
                        + "VALUES (?, ?, ?, 'fact', 'session', ?, ?, ?, ?, ?, FALSE, "
                        + " 'user', ?, 0, NOW() - (? || ' hours')::interval, NOW(), FALSE)",
                pid, tenantId, agentCode, TEST_PREFIX + "title", content,
                importance, runId, accessCount, userId, String.valueOf(ageHours));
        return pid;
    }
}
