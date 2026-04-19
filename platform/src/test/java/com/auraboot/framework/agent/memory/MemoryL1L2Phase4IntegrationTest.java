package com.auraboot.framework.agent.memory;

import com.auraboot.framework.agent.service.ActiveMemoryService;
import com.auraboot.framework.agent.service.AgentMemoryService;
import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
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

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-85 / Phase 4 integration tests covering L1 read-cap, leader election,
 * admin promote-now, and the L2-only soul-profile reader.
 *
 * <p>Design: {@code docs/plans/2026-04/2026-04-19-memory-l1-l2-promotion-design.md §9.2}.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("MemoryL1L2 Phase 4 (PR-85)")
class MemoryL1L2Phase4IntegrationTest extends BaseIntegrationTest {

    private static final String TEST_PREFIX = "MP4_";

    @Autowired private AgentMemoryService agentMemoryService;
    @Autowired private ActiveMemoryService activeMemoryService;
    @Autowired private MemoryL1L2Promoter promoter;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DynamicDataMapper dynamicDataMapper;

    private Long tenantId;
    private String userId;
    private String agentCode;

    @BeforeEach
    void setup() {
        tenantId = getTestTenant().getId();
        userId = String.valueOf(getTestUser().getId());
        agentCode = TEST_PREFIX + "agent_" + UniqueIdGenerator.generate();
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
        jdbc.update(
                "DELETE FROM ab_scheduler_leader WHERE job_code LIKE ?",
                TEST_PREFIX + "%");
    }

    // ------------------------------------------------------------------
    // 1) L1 read cap — seed 50 L1 rows, assert reader returns exactly 30.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("loadL1Capped returns exactly maxRows even when 50 rows exist")
    void loadL1Capped_capsAt30() {
        for (int i = 0; i < 50; i++) {
            insertL1(TEST_PREFIX + "cap-" + i + "-" + UniqueIdGenerator.generate(),
                    /*importance*/ 5, /*accessCount*/ i);
        }

        List<Map<String, Object>> rows = agentMemoryService.loadL1Capped(tenantId, userId, 30);
        assertThat(rows).hasSize(30);

        // Every row must have category=session and scope_key=userId.
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.get("category")).isEqualTo("session");
            assertThat(row.get("scope_key")).isEqualTo(userId);
        });

        // ActiveMemoryService.recallL1Capped honours the @Value default (30).
        assertThat(activeMemoryService.getMaxL1()).isEqualTo(30);
        List<Map<String, Object>> capped = activeMemoryService.recallL1Capped(tenantId, userId);
        assertThat(capped).hasSize(30);
    }

    // ------------------------------------------------------------------
    // 2) loadScopedByImportanceL2Only excludes L1 rows (Soul Profile input).
    // ------------------------------------------------------------------
    @Test
    @DisplayName("loadScopedByImportanceL2Only returns only user/agent category rows")
    void loadScopedByImportanceL2Only_excludesSession() {
        // Set default agent so the deriver path's agentCode='default' matches.
        String defaultAgent = "default";
        String l2Marker = TEST_PREFIX + "l2-" + UniqueIdGenerator.generate();
        String l1Marker = TEST_PREFIX + "l1-" + UniqueIdGenerator.generate();

        insertWithCategory(defaultAgent, l2Marker + " durable preference", "user", 7);
        insertWithCategory(defaultAgent, l1Marker + " ephemeral note",    "session", 9);

        List<Map<String, Object>> rows = agentMemoryService.loadScopedByImportanceL2Only(
                tenantId, userId, defaultAgent, 20);

        // L2 row present; L1 row filtered out.
        assertThat(rows).anyMatch(r -> ((String) r.get("memory_content")).contains(l2Marker));
        assertThat(rows).noneMatch(r -> ((String) r.get("memory_content")).contains(l1Marker));
        assertThat(rows).allSatisfy(r ->
                assertThat(r.get("category")).isIn("user", "agent"));

        // cleanup — not covered by the agentCode-prefix cleanup (uses 'default')
        jdbc.update("DELETE FROM ab_agent_memory "
                + "WHERE memory_agent_id = ? AND memory_content LIKE ?",
                defaultAgent, TEST_PREFIX + "%");
    }

    // ------------------------------------------------------------------
    // 3) Leader election — two instances, only one wins; stale leader replaced.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("leader election: only one of two instances wins; stale leader replaced after 60s")
    void leaderElection_exclusiveAndStaleReplacement() {
        String jobCode = TEST_PREFIX + "job_" + UniqueIdGenerator.generate();

        MemoryL1L2LeaderElection a = new MemoryL1L2LeaderElection(
                jdbc, "inst-A-" + UniqueIdGenerator.generate(), /*enabled*/ true);
        MemoryL1L2LeaderElection b = new MemoryL1L2LeaderElection(
                jdbc, "inst-B-" + UniqueIdGenerator.generate(), /*enabled*/ true);

        boolean aWon = a.acquire(jobCode);
        boolean bWon = b.acquire(jobCode);

        assertThat(aWon).isTrue();
        assertThat(bWon).isFalse();

        // The winner can re-acquire (heartbeat renewal).
        assertThat(a.acquire(jobCode)).isTrue();

        // Simulate stale leader (crashed JVM): push heartbeat back > 60s.
        jdbc.update(
                "UPDATE ab_scheduler_leader SET heartbeat_at = NOW() - make_interval(secs => 120) "
                        + " WHERE job_code = ?",
                jobCode);

        // Now B can take over.
        assertThat(b.acquire(jobCode)).isTrue();

        // And after takeover, A (still alive but stale-displaced) loses the
        // next acquire attempt — the row now points at B and is fresh.
        assertThat(a.acquire(jobCode)).isFalse();

        // Release contract — B releases, next acquirer gets it.
        b.release(jobCode);
        assertThat(a.acquire(jobCode)).isTrue();
    }

    @Test
    @DisplayName("leader election disabled: acquire is a no-op returning true")
    void leaderElection_disabled_alwaysWins() {
        String jobCode = TEST_PREFIX + "job_" + UniqueIdGenerator.generate();
        MemoryL1L2LeaderElection disabled = new MemoryL1L2LeaderElection(
                jdbc, "inst-disabled", /*enabled*/ false);

        assertThat(disabled.acquire(jobCode)).isTrue();
        assertThat(disabled.acquire(jobCode)).isTrue();

        // No row should have been written.
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_scheduler_leader WHERE job_code = ?",
                Integer.class, jobCode);
        assertThat(count).isZero();
    }

    // ------------------------------------------------------------------
    // 4) Admin promote-now — success flips session -> user with admin audit.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("promoteNow: session row flipped to user with admin_promoted audit + reason")
    void promoteNow_success() {
        String pid = insertL1(
                TEST_PREFIX + "force-" + UniqueIdGenerator.generate()
                        + " trivial note the admin wants pinned",
                /*importance*/ 2 /* below gate — only admin can force */,
                /*accessCount*/ 0);

        String reason = "ops requested pin for debugging";
        MemoryL1L2Promoter.AdminPromoteOutcome out =
                promoter.promoteNow(pid, tenantId, userId, reason);

        assertThat(out.outcome()).isEqualTo(MemoryL1L2Promoter.Outcome.PROMOTED);
        assertThat(out.targetPid()).isEqualTo(pid);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT category, promoted_at, score_snapshot::text AS snap "
                        + "  FROM ab_agent_memory WHERE pid = ?",
                pid);
        assertThat(row.get("category")).isEqualTo("user");
        assertThat(row.get("promoted_at")).isNotNull();
        assertThat((String) row.get("snap")).contains("admin_reason").contains(reason);

        Map<String, Object> audit = jdbc.queryForMap(
                "SELECT event_type, score_snapshot::text AS snap "
                        + "  FROM ab_agent_memory_tier_event WHERE memory_pid = ?",
                pid);
        assertThat(audit.get("event_type")).isEqualTo("admin_promoted");
        assertThat((String) audit.get("snap")).contains("admin_reason").contains(reason);
    }

    // ------------------------------------------------------------------
    // 5) Admin promote-now — rejects non-L1 (already promoted / missing).
    // ------------------------------------------------------------------
    @Test
    @DisplayName("promoteNow: already-L2 pid throws memory_not_l1")
    void promoteNow_alreadyL2_throws() {
        String pid = insertWithCategory(agentCode,
                TEST_PREFIX + "already-l2 " + UniqueIdGenerator.generate(),
                "user", 7);

        assertThatThrownBy(() -> promoter.promoteNow(pid, tenantId, userId, "try again"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("memory_not_l1");
    }

    @Test
    @DisplayName("promoteNow: missing pid throws memory_not_found")
    void promoteNow_missing_throws() {
        String ghost = "gh_" + UniqueIdGenerator.generate();
        assertThatThrownBy(() -> promoter.promoteNow(ghost, tenantId, userId, "forced"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("memory_not_found");
    }

    @Test
    @DisplayName("promoteNow: cross-tenant pid rejected as memory_not_found (Round-3 C1)")
    void promoteNow_crossTenantRejected() {
        Long otherTenant = tenantId + 99999L;
        String pid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_memory "
                        + "(pid, tenant_id, memory_agent_id, memory_type, memory_title, memory_content, "
                        + " importance, access_count, scope, scope_key, category, created_at) "
                        + "VALUES (?, ?, ?, 'insight', 'x', ?, 5, 0, 'user', 'other-user', 'session', NOW())",
                pid, otherTenant, agentCode, TEST_PREFIX + "cross-tenant content");
        try {
            assertThatThrownBy(() -> promoter.promoteNow(pid, tenantId, userId, "try leak"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("memory_not_found");
        } finally {
            jdbc.update("DELETE FROM ab_agent_memory WHERE pid = ?", pid);
        }
    }

    @Test
    @DisplayName("promoteNow: blank reason rejected")
    void promoteNow_blankReason_throws() {
        String pid = insertL1(TEST_PREFIX + "r " + UniqueIdGenerator.generate(), 2, 0);
        assertThatThrownBy(() -> promoter.promoteNow(pid, tenantId, userId, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> promoter.promoteNow(pid, tenantId, userId, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private String insertL1(String content, int importance, int accessCount) {
        String pid = UniqueIdGenerator.generate();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("pid", pid);
        row.put("tenant_id", tenantId);
        row.put("memory_agent_id", agentCode);
        row.put("memory_type", "fact");
        row.put("category", "session");
        row.put("memory_title", TEST_PREFIX + "title");
        row.put("memory_content", content);
        row.put("importance", importance);
        row.put("access_count", accessCount);
        row.put("last_accessed", LocalDateTime.now().minusMinutes(accessCount + 1));
        row.put("created_at", LocalDateTime.now().minusMinutes(5));
        row.put("updated_at", LocalDateTime.now());
        row.put("deleted_flag", false);
        row.put("shareable", false);
        row.put("scope", "user");
        row.put("scope_key", userId);
        row.put("demotion_count", 0);
        dynamicDataMapper.insert("ab_agent_memory", row);
        return pid;
    }

    private String insertWithCategory(String useAgent, String content, String category, int importance) {
        String pid = UniqueIdGenerator.generate();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("pid", pid);
        row.put("tenant_id", tenantId);
        row.put("memory_agent_id", useAgent);
        row.put("memory_type", "fact");
        row.put("category", category);
        row.put("memory_title", TEST_PREFIX + "title");
        row.put("memory_content", content);
        row.put("importance", importance);
        row.put("access_count", 1);
        row.put("created_at", LocalDateTime.now().minusMinutes(5));
        row.put("updated_at", LocalDateTime.now());
        row.put("deleted_flag", false);
        row.put("shareable", false);
        row.put("scope", "user");
        row.put("scope_key", userId);
        row.put("demotion_count", 0);
        dynamicDataMapper.insert("ab_agent_memory", row);
        return pid;
    }
}
