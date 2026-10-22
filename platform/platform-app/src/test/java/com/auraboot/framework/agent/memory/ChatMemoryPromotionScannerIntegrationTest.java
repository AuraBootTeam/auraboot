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
 * D1 integration tests for {@link ChatMemoryPromotionScanner} — the chat
 * memory promotion channel (review G2 closure, owner-approved 2026-07-19).
 *
 * <p>Same {@code @Commit @Transactional(NEVER)} harness as
 * {@link MemoryL1L2OrphanScannerIntegrationTest}: the scanner takes a PG
 * advisory lock inside its own TransactionTemplate.
 *
 * <p>Covers the decision's four load-bearing properties:
 * <ol>
 *   <li>Aged {@code conversation_turn} row at/above the chat gate with recall
 *       history is promoted through the SHARED promoter pipeline (score +
 *       dedup + audit), flipping to {@code category='user'}.</li>
 *   <li>Importance below the chat gate (CONTEXTUAL_ANSWER = 3) is never a
 *       candidate — stays L1 by design.</li>
 *   <li>The idle window is respected — fresh rows stay untouched.</li>
 *   <li>{@code category='session'} rows are NOT this channel's business
 *       (they belong to the event/orphan pipelines).</li>
 * </ol>
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("ChatMemoryPromotionScanner (review D1)")
class ChatMemoryPromotionScannerIntegrationTest extends BaseIntegrationTest {

    private static final String TEST_PREFIX = "CMP_";

    @Autowired
    private ChatMemoryPromotionScanner scanner;

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

    @Test
    @DisplayName("aged SYNC_ACTION-tier chat row with recall history is promoted via the shared pipeline")
    void runOnce_promotesAgedChatRow() {
        // importance=5 (ACP_RUN tier), access=12, age 2h, unique content:
        // score ≈ 0.78 > 0.65 with the v1 weights — comfortably above the
        // gate so this can't flake on rounding.
        String pid = insertChatRow(
                TEST_PREFIX + "chat-" + UniqueIdGenerator.generate()
                        + " created customer TestCo with default owner",
                /*importance*/ 5, /*accessCount*/ 12, /*ageHours*/ 2);

        MemoryL1L2OrphanScanner.ScanSummary summary = scanner.runOnce();

        assertThat(summary.candidates()).isGreaterThanOrEqualTo(1);
        assertThat(summary.promoted()).isGreaterThanOrEqualTo(1);

        String category = jdbc.queryForObject(
                "SELECT category FROM ab_agent_memory WHERE pid = ?", String.class, pid);
        assertThat(category).isEqualTo("user");

        Integer auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_memory_tier_event "
                        + " WHERE memory_pid = ? AND event_type = 'L1_PROMOTED'",
                Integer.class, pid);
        assertThat(auditCount).isEqualTo(1);
    }

    @Test
    @DisplayName("below the chat gate (CONTEXTUAL=3) is never a candidate — stays L1 by design")
    void runOnce_ignoresBelowGate() {
        String pid = insertChatRow(
                TEST_PREFIX + "ctx-" + UniqueIdGenerator.generate()
                        + " page shows outstanding orders",
                /*importance*/ 3, /*accessCount*/ 20, /*ageHours*/ 3);

        scanner.runOnce();

        assertThat(jdbc.queryForObject(
                "SELECT category FROM ab_agent_memory WHERE pid = ?", String.class, pid))
                .isEqualTo(ChatMemoryPromotionScanner.SOURCE_CATEGORY);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_memory_tier_event WHERE memory_pid = ?",
                Integer.class, pid)).isZero();
    }

    @Test
    @DisplayName("idle window respected — fresh chat rows stay untouched")
    void runOnce_respectsIdleWindow() {
        String pid = insertChatRow(
                TEST_PREFIX + "fresh-" + UniqueIdGenerator.generate()
                        + " just happened action",
                5, 12, 0);
        jdbc.update("UPDATE ab_agent_memory SET created_at = NOW() - INTERVAL '5 minutes' "
                + " WHERE pid = ?", pid);

        scanner.runOnce();

        assertThat(jdbc.queryForObject(
                "SELECT category FROM ab_agent_memory WHERE pid = ?", String.class, pid))
                .isEqualTo(ChatMemoryPromotionScanner.SOURCE_CATEGORY);
    }

    @Test
    @DisplayName("session-category rows are not this channel's business")
    void runOnce_ignoresSessionCategory() {
        // A row the ORPHAN scanner would pick up must be invisible here.
        String pid = UniqueIdGenerator.generate();
        jdbc.update(
                "INSERT INTO ab_agent_memory "
                        + "(pid, tenant_id, memory_agent_id, memory_type, category, "
                        + " memory_title, memory_content, importance, source_run_id, "
                        + " access_count, shareable, scope, scope_key, demotion_count, "
                        + " created_at, updated_at, deleted_flag) "
                        + "VALUES (?, ?, ?, 'fact', 'session', ?, ?, 9, ?, 5, FALSE, "
                        + " 'user', ?, 0, NOW() - INTERVAL '2 hours', NOW(), FALSE)",
                pid, tenantId, agentCode, TEST_PREFIX + "title",
                TEST_PREFIX + "session-" + UniqueIdGenerator.generate() + " durable run memory",
                UniqueIdGenerator.generate(), userId);

        scanner.runOnce();

        assertThat(jdbc.queryForObject(
                "SELECT category FROM ab_agent_memory WHERE pid = ?", String.class, pid))
                .isEqualTo("session");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private String insertChatRow(String content, int importance, int accessCount, int ageHours) {
        String pid = UniqueIdGenerator.generate();
        jdbc.update(
                "INSERT INTO ab_agent_memory "
                        + "(pid, tenant_id, memory_agent_id, memory_type, category, "
                        + " memory_title, memory_content, importance, source_run_id, "
                        + " access_count, shareable, scope, scope_key, demotion_count, "
                        + " created_at, updated_at, deleted_flag) "
                        + "VALUES (?, ?, ?, 'turn_summary', ?, ?, ?, ?, NULL, ?, FALSE, "
                        + " 'user', ?, 0, NOW() - (? || ' hours')::interval, NOW(), FALSE)",
                pid, tenantId, agentCode, ChatMemoryPromotionScanner.SOURCE_CATEGORY,
                TEST_PREFIX + "title", content, importance, accessCount, userId,
                String.valueOf(ageHours));
        return pid;
    }
}
