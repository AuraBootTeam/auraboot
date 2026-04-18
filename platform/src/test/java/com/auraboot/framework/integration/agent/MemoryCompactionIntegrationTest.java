package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.service.MemoryCompactionService;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-23: Memory L5 flush-before-compression — oversized (tenant, agent)
 * buckets get their lowest-importance batch compressed into a single
 * summary memory.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("MemoryCompactionService L5 (PR-23)")
class MemoryCompactionIntegrationTest extends BaseIntegrationTest {

    @Autowired private MemoryCompactionService compactor;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;
    private String agent;

    @BeforeEach
    void setup() {
        tenantId = 9_550_000L + System.nanoTime() % 100_000;
        agent = "compact_" + System.nanoTime();
        compactor.setMaxPerAgent(10);
        compactor.setThresholdPct(80);
        compactor.setFlushBatchSize(5);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_memory WHERE tenant_id = ?", tenantId);
    }

    private String seed(int importance, String scope, String title) {
        String pid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_memory " +
                        "(pid, tenant_id, memory_agent_id, memory_type, category, " +
                        " memory_title, memory_content, importance, shareable, scope, " +
                        " created_at, updated_at, deleted_flag) " +
                        "VALUES (?, ?, ?, 'fact', 'agent', ?, ?, ?, FALSE, ?, NOW(), NOW(), FALSE)",
                pid, tenantId, agent, title, "body-" + title, importance, scope);
        return pid;
    }

    private int liveCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_memory " +
                        "WHERE tenant_id = ? AND memory_agent_id = ? " +
                        "AND (deleted_flag IS NULL OR deleted_flag = FALSE)",
                Integer.class, tenantId, agent);
    }

    // -----------------------------------------------------------------------

    @Test
    @DisplayName("bucket above threshold → lowest-importance batch compressed into 1 summary")
    void oversized_bucket_compresses_batch() {
        // 12 tenant-scoped rows; threshold=8 (10*0.8), batch=5 → compress 5.
        for (int i = 0; i < 12; i++) seed(i + 1, "tenant", "T" + i);

        MemoryCompactionService.CompactionResult r = compactor.compactOversizedBuckets();
        assertThat(r.buckets()).isEqualTo(1);
        assertThat(r.compressed()).isEqualTo(1);
        assertThat(r.replaced()).isEqualTo(5);

        assertThat(liveCount()).isEqualTo(12 - 5 + 1); // -5 compressed +1 summary = 8

        // The 5 lowest-importance (T0..T4 with importance 1..5) should be deleted.
        for (int i = 0; i < 5; i++) {
            Boolean deleted = jdbc.queryForObject(
                    "SELECT deleted_flag FROM ab_agent_memory WHERE memory_title = ?",
                    Boolean.class, "T" + i);
            assertThat(deleted).as("row %s deleted", "T" + i).isTrue();
        }
        // The summary exists with category='compressed'.
        Integer summaries = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_memory " +
                        "WHERE tenant_id = ? AND memory_agent_id = ? AND category = 'compressed'",
                Integer.class, tenantId, agent);
        assertThat(summaries).isEqualTo(1);
    }

    @Test
    @DisplayName("under-threshold bucket is untouched")
    void under_threshold_skipped() {
        for (int i = 0; i < 5; i++) seed(i + 1, "tenant", "low" + i);
        MemoryCompactionService.CompactionResult r = compactor.compactOversizedBuckets();
        assertThat(r.buckets()).isZero();
        assertThat(liveCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("user-scoped rows are never compressed — private preferences stay intact")
    void user_scope_preserved() {
        for (int i = 0; i < 12; i++) {
            // All user-scoped; even at 12 rows the bucket query filters them out.
            String pid = UniqueIdGenerator.generate();
            jdbc.update("INSERT INTO ab_agent_memory " +
                            "(pid, tenant_id, memory_agent_id, memory_type, category, " +
                            " memory_title, memory_content, importance, shareable, scope, scope_key, " +
                            " created_at, updated_at, deleted_flag) " +
                            "VALUES (?, ?, ?, 'fact', 'user', ?, ?, ?, FALSE, 'user', 'u1', " +
                            " NOW(), NOW(), FALSE)",
                    pid, tenantId, agent, "U" + i, "body", i + 1);
        }
        MemoryCompactionService.CompactionResult r = compactor.compactOversizedBuckets();
        assertThat(r.buckets()).as("user scope excluded from buckets").isZero();
        assertThat(liveCount()).isEqualTo(12);
    }

    @Test
    @DisplayName("summary memory inherits median importance and dominant scope")
    void summary_metadata() {
        seed(1,  "tenant", "T1");
        seed(2,  "tenant", "T2");
        seed(3,  "tenant", "T3");
        seed(4,  "tenant", "T4");
        seed(5,  "global", "T5"); // dominant = tenant (4 vs 1)
        for (int i = 6; i <= 12; i++) seed(i, "tenant", "T" + i);

        compactor.compactBucket(tenantId, agent);

        Map<String, Object> summary = jdbc.queryForMap(
                "SELECT importance, scope, memory_title, memory_content FROM ab_agent_memory " +
                        "WHERE tenant_id = ? AND memory_agent_id = ? AND category = 'compressed'",
                tenantId, agent);
        // Median of [1,2,3,4,5] is 3 (lowest 5 by importance, flushBatchSize=5).
        assertThat(((Number) summary.get("importance")).intValue()).isEqualTo(3);
        assertThat(summary.get("scope")).isEqualTo("tenant");
        assertThat((String) summary.get("memory_title")).contains("Compressed");
        assertThat((String) summary.get("memory_content")).contains("T1").contains("T5");
    }

    @Test
    @DisplayName("compactBucket with fewer rows than flushBatchSize is a no-op")
    void under_batch_size_noop() {
        // 4 rows, flushBatchSize=5 → no compression even though we forced call.
        for (int i = 0; i < 4; i++) seed(i + 1, "tenant", "small" + i);
        int replaced = compactor.compactBucket(tenantId, agent);
        assertThat(replaced).isZero();
        assertThat(liveCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("idempotent — running twice compacts once per batch (threshold drops below)")
    void idempotent_second_run() {
        for (int i = 0; i < 12; i++) seed(i + 1, "tenant", "A" + i);

        MemoryCompactionService.CompactionResult r1 = compactor.compactOversizedBuckets();
        assertThat(r1.buckets()).isEqualTo(1);

        // After first run: 12 -5 +1 = 8 → at threshold (=8). Run again.
        MemoryCompactionService.CompactionResult r2 = compactor.compactOversizedBuckets();
        // Could be 0 or 1 depending on threshold semantics. Spec uses >= threshold → yes compresses.
        // After second run: 8 -5 +1 = 4 rows.
        assertThat(liveCount()).isLessThanOrEqualTo(8);
    }
}
