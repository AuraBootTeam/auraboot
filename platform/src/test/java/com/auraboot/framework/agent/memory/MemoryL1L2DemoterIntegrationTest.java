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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-84 / Phase 3 integration tests for {@link MemoryL1L2Demoter}.
 *
 * <p>Covers the brief's three cases:
 * <ol>
 *   <li>Demotes L2 rows that are stale (last_accessed &gt; 90d ago or null)
 *       AND low-importance (&lt; demote_threshold).</li>
 *   <li>Skips pinned ({@code shareable=TRUE}) / high-importance rows even
 *       when they are stale.</li>
 *   <li>Writes an {@code L2_DEMOTED} audit row and increments
 *       {@code demotion_count}.</li>
 * </ol>
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("MemoryL1L2Demoter (PR-84)")
class MemoryL1L2DemoterIntegrationTest extends BaseIntegrationTest {

    private static final String TEST_PREFIX = "MD_";

    @Autowired
    private MemoryL1L2Demoter demoter;

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
    // 1) Demotes stale + low-importance L2 rows.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("demotes stale low-importance L2 to session with audit row")
    void runOnce_demotesStaleLowImportance() {
        // Stale (last_accessed 120d ago) + low importance (2) + not pinned.
        String pid = insertL2(
                TEST_PREFIX + "stale-" + UniqueIdGenerator.generate()
                        + " rarely used preference from long ago",
                /*importance*/ 2, /*shareable*/ false, /*lastAccessedDaysAgo*/ 120);

        MemoryL1L2Demoter.DemoteSummary summary = demoter.runOnce();

        assertThat(summary.scanned()).isGreaterThanOrEqualTo(1);
        assertThat(summary.demoted()).isGreaterThanOrEqualTo(1);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT category, demoted_at, demotion_count FROM ab_agent_memory WHERE pid = ?",
                pid);
        assertThat(row.get("category")).isEqualTo("session");
        assertThat(row.get("demoted_at")).isNotNull();
        assertThat(((Number) row.get("demotion_count")).intValue()).isEqualTo(1);

        Integer auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_memory_tier_event "
                        + " WHERE memory_pid = ? AND event_type = 'L2_DEMOTED'",
                Integer.class, pid);
        assertThat(auditCount).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 2) Skips pinned (shareable=TRUE) and high-importance rows.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("skips pinned (shareable=TRUE) and high-importance rows")
    void runOnce_skipsPinnedOrHighImportance() {
        // Pinned — stale + low importance but shareable=TRUE.
        String pinnedPid = insertL2(
                TEST_PREFIX + "pinned-" + UniqueIdGenerator.generate()
                        + " admin-curated fact",
                /*importance*/ 2, /*shareable*/ true, /*lastAccessedDaysAgo*/ 200);

        // High-importance — stale but importance >= threshold (8 >= 3 default max).
        String highImpPid = insertL2(
                TEST_PREFIX + "highimp-" + UniqueIdGenerator.generate()
                        + " critical user context",
                /*importance*/ 8, /*shareable*/ false, /*lastAccessedDaysAgo*/ 200);

        demoter.runOnce();

        // Both rows must stay category='user', demoted_at NULL.
        String pinnedCategory = jdbc.queryForObject(
                "SELECT category FROM ab_agent_memory WHERE pid = ?",
                String.class, pinnedPid);
        assertThat(pinnedCategory).isEqualTo("user");

        String highImpCategory = jdbc.queryForObject(
                "SELECT category FROM ab_agent_memory WHERE pid = ?",
                String.class, highImpPid);
        assertThat(highImpCategory).isEqualTo("user");

        Integer auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_memory_tier_event "
                        + " WHERE memory_pid IN (?, ?)",
                Integer.class, pinnedPid, highImpPid);
        assertThat(auditCount).isZero();
    }

    // ------------------------------------------------------------------
    // 3) Records audit + demotion_count increments on repeat demotion.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("records L2_DEMOTED audit row; demotion_count survives as counter")
    void runOnce_writesAuditAndIncrementsCounter() {
        String pid = insertL2(
                TEST_PREFIX + "counter-" + UniqueIdGenerator.generate()
                        + " low-value historical note",
                /*importance*/ 1, /*shareable*/ false, /*lastAccessedDaysAgo*/ 365);

        demoter.runOnce();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT category, demotion_count FROM ab_agent_memory WHERE pid = ?",
                pid);
        assertThat(row.get("category")).isEqualTo("session");
        assertThat(((Number) row.get("demotion_count")).intValue()).isEqualTo(1);

        // Re-promote artificially (simulates re-access + scorer rerun) and
        // demote again — counter should reach 2.
        jdbc.update(
                "UPDATE ab_agent_memory "
                        + "   SET category = 'user', "
                        + "       last_accessed = NOW() - INTERVAL '180 days', "
                        + "       importance = 1, "
                        + "       demoted_at = NULL "
                        + " WHERE pid = ?", pid);

        demoter.runOnce();

        Map<String, Object> row2 = jdbc.queryForMap(
                "SELECT category, demotion_count FROM ab_agent_memory WHERE pid = ?",
                pid);
        assertThat(row2.get("category")).isEqualTo("session");
        assertThat(((Number) row2.get("demotion_count")).intValue()).isEqualTo(2);

        Integer auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_memory_tier_event "
                        + " WHERE memory_pid = ? AND event_type = 'L2_DEMOTED'",
                Integer.class, pid);
        assertThat(auditCount).isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private String insertL2(String content, int importance, boolean shareable,
                            int lastAccessedDaysAgo) {
        String pid = UniqueIdGenerator.generate();
        jdbc.update(
                "INSERT INTO ab_agent_memory "
                        + "(pid, tenant_id, memory_agent_id, memory_type, category, "
                        + " memory_title, memory_content, importance, access_count, "
                        + " shareable, scope, scope_key, demotion_count, "
                        + " last_accessed, created_at, updated_at, deleted_flag) "
                        + "VALUES (?, ?, ?, 'fact', 'user', ?, ?, ?, 0, ?, "
                        + " 'user', ?, 0, "
                        + " NOW() - (? || ' days')::interval, "
                        + " NOW() - INTERVAL '365 days', NOW(), FALSE)",
                pid, tenantId, agentCode, TEST_PREFIX + "title", content,
                importance, shareable, userId, String.valueOf(lastAccessedDaysAgo));
        return pid;
    }
}
