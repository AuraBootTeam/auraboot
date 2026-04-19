package com.auraboot.framework.agent.memory;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-83 Phase 2 integration tests for {@link MemoryL1L2Promoter}.
 *
 * <p>Design: {@code docs/plans/2026-04/2026-04-19-memory-l1-l2-promotion-design.md §4 / §6 / §8}.
 *
 * <p>Covers (minimum 5 per task brief):
 * <ol>
 *   <li>Above-threshold L1 promoted to L2 on session-end event.</li>
 *   <li>Below-threshold L1 stays L1 (audit-free skip).</li>
 *   <li>Duplicate L2 via content_hash does not re-promote — merges + increments
 *       access_count + DEDUP_HIT audit + skipped_dup counter.</li>
 *   <li>Reader ({@link com.auraboot.framework.agent.service.AgentMemoryService})
 *       returns the promoted L2 row after promotion.</li>
 *   <li>Tier event audit row written for every promotion.</li>
 *   <li>Spring event bus dispatches {@link SessionEndedEvent} to the listener
 *       (integration wiring smoke).</li>
 * </ol>
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@Transactional
@Rollback(true)
class MemoryL1L2PromoterIntegrationTest extends BaseIntegrationTest {

    private static final String TEST_PREFIX = "MLP_";

    @Autowired
    private MemoryL1L2Promoter promoter;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private DynamicDataMapper dynamicDataMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private com.auraboot.framework.agent.service.AgentMemoryService agentMemoryService;

    // ------------------------------------------------------------------
    // 1) Above-threshold L1 -> L2
    // ------------------------------------------------------------------
    @Test
    void sessionEnd_promotesAboveThresholdL1ToL2_andWritesAudit() {
        Long tenantId = getTestTenant().getId();
        String agentCode = TEST_PREFIX + "agent_" + UniqueIdGenerator.generate();
        String userId = String.valueOf(getTestUser().getId());
        String runId = UniqueIdGenerator.generate();

        String memoryPid = insertL1(tenantId, agentCode, userId, runId,
                TEST_PREFIX + "I prefer markdown replies with fenced code blocks.",
                /*importance*/ 9, /*accessCount*/ 4);

        MemoryL1L2Promoter.PromotionSummary summary = promoter.handle(
                new SessionEndedEvent(tenantId, runId, agentCode, userId));

        assertThat(summary.candidates()).isEqualTo(1);
        assertThat(summary.promoted()).isEqualTo(1);
        assertThat(summary.dedupHits()).isZero();

        // Row was flipped to L2.
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT category, promoted_at, promoted_from_run_id, content_hash, "
                        + "       score_snapshot::text AS snap "
                        + "  FROM ab_agent_memory WHERE pid = ?",
                memoryPid);
        assertThat(row.get("category")).isEqualTo("user");
        assertThat(row.get("promoted_at")).isNotNull();
        assertThat(row.get("promoted_from_run_id")).isEqualTo(runId);
        assertThat((String) row.get("content_hash")).hasSize(64);
        assertThat((String) row.get("snap")).contains("\"score\"");

        // Audit row written as L1_PROMOTED.
        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_memory_tier_event "
                        + " WHERE memory_pid = ? AND event_type = 'L1_PROMOTED'",
                Integer.class, memoryPid);
        assertThat(auditCount).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 2) Below-threshold L1 stays L1
    // ------------------------------------------------------------------
    @Test
    void sessionEnd_belowImportanceGate_doesNotLoadCandidate() {
        Long tenantId = getTestTenant().getId();
        String agentCode = TEST_PREFIX + "agent_" + UniqueIdGenerator.generate();
        String userId = String.valueOf(getTestUser().getId());
        String runId = UniqueIdGenerator.generate();

        // importance=3 is below the base gate (6) -> never a candidate.
        String memoryPid = insertL1(tenantId, agentCode, userId, runId,
                TEST_PREFIX + "trivial fact about preferred font size",
                /*importance*/ 3, /*accessCount*/ 0);

        MemoryL1L2Promoter.PromotionSummary summary = promoter.handle(
                new SessionEndedEvent(tenantId, runId, agentCode, userId));

        assertThat(summary.candidates()).isZero();
        assertThat(summary.promoted()).isZero();

        String category = jdbcTemplate.queryForObject(
                "SELECT category FROM ab_agent_memory WHERE pid = ?",
                String.class, memoryPid);
        assertThat(category).isEqualTo("session");

        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_memory_tier_event WHERE memory_pid = ?",
                Integer.class, memoryPid);
        assertThat(auditCount).isZero();
    }

    // ------------------------------------------------------------------
    // 3) Duplicate L2 via content_hash — merge, not re-promote.
    // ------------------------------------------------------------------
    @Test
    void sessionEnd_duplicateContentHash_mergesInsteadOfReprompting() {
        Long tenantId = getTestTenant().getId();
        String agentCode = TEST_PREFIX + "agent_" + UniqueIdGenerator.generate();
        String userId = String.valueOf(getTestUser().getId());
        String runId = UniqueIdGenerator.generate();

        String content = TEST_PREFIX + "I always use GitHub flavoured markdown.";
        String hash = MemoryL1L2Promoter.contentHash(content);

        // Pre-existing L2 row with matching content_hash.
        String existingL2Pid = insertL2(tenantId, agentCode, userId, content, hash,
                /*importance*/ 7, /*accessCount*/ 1);

        // New L1 in this run with the same content.
        String newL1Pid = insertL1(tenantId, agentCode, userId, runId, content,
                /*importance*/ 9, /*accessCount*/ 5);

        MemoryL1L2Promoter.PromotionSummary summary = promoter.handle(
                new SessionEndedEvent(tenantId, runId, agentCode, userId));

        assertThat(summary.candidates()).isEqualTo(1);
        assertThat(summary.promoted()).isZero();
        assertThat(summary.dedupHits()).isEqualTo(1);

        // L1 row stays session (dedup path does not flip it).
        String l1Category = jdbcTemplate.queryForObject(
                "SELECT category FROM ab_agent_memory WHERE pid = ?",
                String.class, newL1Pid);
        assertThat(l1Category).isEqualTo("session");

        // Existing L2 merged — access_count incremented, importance bumped
        // to the max of (existing=7, incoming=9).
        Map<String, Object> merged = jdbcTemplate.queryForMap(
                "SELECT importance, access_count FROM ab_agent_memory WHERE pid = ?",
                existingL2Pid);
        assertThat(((Number) merged.get("importance")).intValue()).isEqualTo(9);
        assertThat(((Number) merged.get("access_count")).intValue()).isEqualTo(2);

        // Audit row written as DEDUP_HIT with dedup_mode='hash'.
        Map<String, Object> audit = jdbcTemplate.queryForMap(
                "SELECT event_type, dedup_mode, merged_into_pid "
                        + "  FROM ab_agent_memory_tier_event WHERE memory_pid = ?",
                newL1Pid);
        assertThat(audit.get("event_type")).isEqualTo("DEDUP_HIT");
        assertThat(audit.get("dedup_mode")).isEqualTo("hash");
        assertThat(audit.get("merged_into_pid")).isEqualTo(existingL2Pid);
    }

    // ------------------------------------------------------------------
    // 4) Reader integration — promoted L2 flows through AgentMemoryService.
    // ------------------------------------------------------------------
    @Test
    void reader_returnsPromotedL2RowAfterSessionEnd() {
        Long tenantId = getTestTenant().getId();
        String agentCode = TEST_PREFIX + "agent_" + UniqueIdGenerator.generate();
        String userId = String.valueOf(getTestUser().getId());
        String runId = UniqueIdGenerator.generate();

        String marker = TEST_PREFIX + "reader_" + UniqueIdGenerator.generate();
        String content = marker + " — user prefers concise technical answers.";
        insertL1(tenantId, agentCode, userId, runId, content,
                /*importance*/ 9, /*accessCount*/ 3);

        promoter.handle(new SessionEndedEvent(tenantId, runId, agentCode, userId));

        // Keyword search (scope=user) now returns the row with category=user.
        List<Map<String, Object>> hits = agentMemoryService.searchScoped(
                tenantId, userId, agentCode, marker, /*limit*/ 5);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).get("category")).isEqualTo("user");
        assertThat((String) hits.get(0).get("memory_content")).contains(marker);
    }

    // ------------------------------------------------------------------
    // 5) Tier event audit row is written for every promotion.
    // ------------------------------------------------------------------
    @Test
    void promotion_writesTierEventRowForEachPromotedMemory() {
        Long tenantId = getTestTenant().getId();
        String agentCode = TEST_PREFIX + "agent_" + UniqueIdGenerator.generate();
        String userId = String.valueOf(getTestUser().getId());
        String runId = UniqueIdGenerator.generate();

        // 3 distinct high-importance L1 rows in the same run.
        String p1 = insertL1(tenantId, agentCode, userId, runId,
                TEST_PREFIX + "fact-a " + UniqueIdGenerator.generate(), 9, 2);
        String p2 = insertL1(tenantId, agentCode, userId, runId,
                TEST_PREFIX + "fact-b " + UniqueIdGenerator.generate(), 8, 4);
        String p3 = insertL1(tenantId, agentCode, userId, runId,
                TEST_PREFIX + "fact-c " + UniqueIdGenerator.generate(), 10, 6);

        MemoryL1L2Promoter.PromotionSummary summary = promoter.handle(
                new SessionEndedEvent(tenantId, runId, agentCode, userId));

        assertThat(summary.candidates()).isEqualTo(3);
        assertThat(summary.promoted()).isEqualTo(3);

        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_memory_tier_event "
                        + " WHERE memory_pid IN (?, ?, ?) AND event_type = 'L1_PROMOTED'",
                Integer.class, p1, p2, p3);
        assertThat(auditCount).isEqualTo(3);

        // source_run_id round-trips into the audit row.
        List<String> runs = jdbcTemplate.queryForList(
                "SELECT DISTINCT source_run_id FROM ab_agent_memory_tier_event "
                        + " WHERE memory_pid IN (?, ?, ?)",
                String.class, p1, p2, p3);
        assertThat(runs).containsExactly(runId);
    }

    // ------------------------------------------------------------------
    // 6) Spring event bus wiring — publishing the event reaches the listener.
    // ------------------------------------------------------------------
    @Test
    void springEventBus_dispatchesSessionEndedEventToPromoter() {
        Long tenantId = getTestTenant().getId();
        String agentCode = TEST_PREFIX + "agent_" + UniqueIdGenerator.generate();
        String userId = String.valueOf(getTestUser().getId());
        String runId = UniqueIdGenerator.generate();

        String memoryPid = insertL1(tenantId, agentCode, userId, runId,
                TEST_PREFIX + "event-bus " + UniqueIdGenerator.generate(),
                /*importance*/ 9, /*accessCount*/ 5);

        eventPublisher.publishEvent(new SessionEndedEvent(tenantId, runId, agentCode, userId));

        String category = jdbcTemplate.queryForObject(
                "SELECT category FROM ab_agent_memory WHERE pid = ?",
                String.class, memoryPid);
        assertThat(category).isEqualTo("user");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private String insertL1(Long tenantId, String agentCode, String userId, String runId,
                            String content, int importance, int accessCount) {
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
        row.put("source_run_id", runId);
        row.put("access_count", accessCount);
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

    private String insertL2(Long tenantId, String agentCode, String userId,
                            String content, String contentHash,
                            int importance, int accessCount) {
        String pid = UniqueIdGenerator.generate();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("pid", pid);
        row.put("tenant_id", tenantId);
        row.put("memory_agent_id", agentCode);
        row.put("memory_type", "fact");
        row.put("category", "user"); // L2
        row.put("memory_title", TEST_PREFIX + "l2-title");
        row.put("memory_content", content);
        row.put("importance", importance);
        row.put("access_count", accessCount);
        row.put("created_at", LocalDateTime.now().minusDays(1));
        row.put("updated_at", LocalDateTime.now());
        row.put("deleted_flag", false);
        row.put("shareable", false);
        row.put("scope", "user");
        row.put("scope_key", userId);
        row.put("content_hash", contentHash);
        row.put("demotion_count", 0);
        dynamicDataMapper.insert("ab_agent_memory", row);
        return pid;
    }
}
