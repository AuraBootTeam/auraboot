package com.auraboot.framework.agent.memory;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.rag.service.EmbeddingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * PR-84 / Phase 3 integration tests for semantic (cosine) dedup (design §4.3
 * layer 2). Covers the brief's three cases:
 *
 * <ol>
 *   <li>Above-threshold cosine merges the candidate into an existing L2 row
 *       with a {@code DEDUP_HIT / dedup_mode='cosine'} audit entry.</li>
 *   <li>Below-threshold cosine promotes as a new L2 row.</li>
 *   <li>Missing EmbeddingService response (provider null) gracefully skips
 *       the semantic layer and falls through to a plain insert — no merge,
 *       no throw.</li>
 * </ol>
 *
 * <p>Embeddings are pre-populated on the candidate + existing L2 rows via
 * raw vector literals (no HTTP call), so the tests are deterministic and do
 * not require a live embedding provider. {@link EmbeddingService} is still
 * {@code @MockBean}-overridden to guarantee that any code path that does
 * call {@code embed(...)} receives a stable {@code null} rather than hitting
 * the real HTTP client.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("MemoryL1L2Promoter semantic dedup (PR-84)")
class MemoryL1L2SemanticDedupIntegrationTest extends BaseIntegrationTest {

    private static final String TEST_PREFIX = "MSD_";
    /** pgvector column width (see MemoryEmbeddingService.EXPECTED_DIM). */
    private static final int DIM = 1536;

    @Autowired
    private MemoryL1L2Promoter promoter;

    @Autowired
    private JdbcTemplate jdbc;

    /** Provider mocked so any unexpected resolveEmbedding fallback path returns null. */
    @MockitoBean
    private EmbeddingService embeddingService;

    private Long tenantId;
    private String agentCode;
    private String userId;

    @BeforeEach
    void setup() {
        tenantId = getTestTenant().getId();
        agentCode = TEST_PREFIX + "agent_" + UniqueIdGenerator.generate();
        userId = String.valueOf(getTestUser().getId());
        Mockito.when(embeddingService.embed(anyLong(), anyString(), anyString()))
                .thenReturn(null);
        Mockito.when(embeddingService.embedBatch(anyLong(), any(), anyString()))
                .thenReturn(java.util.List.of());
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
    // 1) Cosine >= 0.92 merges (no new L2 row).
    // ------------------------------------------------------------------
    @Test
    @DisplayName("above cosine threshold -> merge into existing L2 with dedup_mode='cosine'")
    void aboveCosineThreshold_merges() {
        String runId = UniqueIdGenerator.generate();
        // Two vectors that differ only on one coordinate -> cosine ~= 1.
        String vA = unitOneHotLiteral(0, 1.0f);
        String vB = unitOneHotLiteral(0, 1.0f); // identical -> cos = 1.0

        // Existing L2 — different text (so hash dedup misses) but vector
        // identical (so cosine dedup fires). content_hash deliberately a
        // hash that will never collide with the L1 candidate's real hash.
        String existingL2 = insertL2("L2 original phrasing of the preference", vA,
                /*importance*/ 5, /*accessCount*/ 1,
                /*contentHash*/ randomHash());

        // Candidate L1 in this run with a semantically-equivalent but
        // textually-distinct message.
        String l1Pid = insertL1(runId, "different wording but same meaning " + UniqueIdGenerator.generate(),
                vB, /*importance*/ 9, /*accessCount*/ 4);

        MemoryL1L2Promoter.PromotionSummary summary = promoter.handle(
                new SessionEndedEvent(tenantId, runId, agentCode, userId));

        assertThat(summary.candidates()).isEqualTo(1);
        assertThat(summary.promoted()).isZero();
        assertThat(summary.semanticDedupHits()).isEqualTo(1);
        assertThat(summary.dedupHits()).isZero();

        // L1 stays session (merge path does not flip it).
        String l1Category = jdbc.queryForObject(
                "SELECT category FROM ab_agent_memory WHERE pid = ?",
                String.class, l1Pid);
        assertThat(l1Category).isEqualTo("session");

        // Existing L2 had access_count bumped and importance lifted.
        Map<String, Object> merged = jdbc.queryForMap(
                "SELECT importance, access_count FROM ab_agent_memory WHERE pid = ?",
                existingL2);
        assertThat(((Number) merged.get("importance")).intValue()).isEqualTo(9);
        assertThat(((Number) merged.get("access_count")).intValue()).isEqualTo(2);

        // Audit row written with dedup_mode='cosine'.
        Map<String, Object> audit = jdbc.queryForMap(
                "SELECT event_type, dedup_mode, merged_into_pid "
                        + "  FROM ab_agent_memory_tier_event WHERE memory_pid = ?",
                l1Pid);
        assertThat(audit.get("event_type")).isEqualTo("DEDUP_HIT");
        assertThat(audit.get("dedup_mode")).isEqualTo("cosine");
        assertThat(audit.get("merged_into_pid")).isEqualTo(existingL2);
    }

    // ------------------------------------------------------------------
    // 2) Cosine below threshold -> promote as new L2 row.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("below cosine threshold -> promote as new L2 row (no merge)")
    void belowCosineThreshold_promotes() {
        String runId = UniqueIdGenerator.generate();
        // Orthogonal vectors -> cosine = 0, well below 0.92.
        String vA = unitOneHotLiteral(0, 1.0f);
        String vB = unitOneHotLiteral(1, 1.0f);

        insertL2("L2 unrelated topic", vA,
                /*importance*/ 5, /*accessCount*/ 1, randomHash());

        String l1Pid = insertL1(runId,
                "entirely-different-subject " + UniqueIdGenerator.generate(),
                vB, /*importance*/ 9, /*accessCount*/ 4);

        MemoryL1L2Promoter.PromotionSummary summary = promoter.handle(
                new SessionEndedEvent(tenantId, runId, agentCode, userId));

        assertThat(summary.candidates()).isEqualTo(1);
        assertThat(summary.promoted()).isEqualTo(1);
        assertThat(summary.semanticDedupHits()).isZero();

        // L1 flipped to L2.
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT category, promoted_at, content_hash FROM ab_agent_memory WHERE pid = ?",
                l1Pid);
        assertThat(row.get("category")).isEqualTo("user");
        assertThat(row.get("promoted_at")).isNotNull();
        assertThat((String) row.get("content_hash")).hasSize(64);

        // Audit row is L1_PROMOTED, not DEDUP_HIT.
        Integer promotedAuditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_memory_tier_event "
                        + " WHERE memory_pid = ? AND event_type = 'L1_PROMOTED'",
                Integer.class, l1Pid);
        assertThat(promotedAuditCount).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 3) Missing embedding -> skip semantic dedup gracefully, plain promote.
    // ------------------------------------------------------------------
    @Test
    @DisplayName("no embedding present + provider null -> skip semantic, promote plainly")
    void noEmbedding_skipsSemanticGracefully() {
        String runId = UniqueIdGenerator.generate();

        // Existing L2 row with an embedding (cosine would merge if candidate
        // had one). Candidate L1 has NO embedding and the mocked
        // EmbeddingService returns null — so resolveEmbedding(...) yields
        // null and the promoter must not merge.
        insertL2("L2 pre-existing memory", unitOneHotLiteral(0, 1.0f),
                5, 1, randomHash());

        String l1Pid = insertL1(runId,
                "unique new content " + UniqueIdGenerator.generate(),
                /*vectorLiteral*/ null,
                /*importance*/ 9, /*accessCount*/ 4);

        MemoryL1L2Promoter.PromotionSummary summary = promoter.handle(
                new SessionEndedEvent(tenantId, runId, agentCode, userId));

        assertThat(summary.candidates()).isEqualTo(1);
        // With no embedding, semantic dedup is skipped and the row promotes
        // cleanly (hash differs so hash dedup also misses).
        assertThat(summary.promoted()).isEqualTo(1);
        assertThat(summary.semanticDedupHits()).isZero();
        assertThat(summary.dedupHits()).isZero();

        String category = jdbc.queryForObject(
                "SELECT category FROM ab_agent_memory WHERE pid = ?",
                String.class, l1Pid);
        assertThat(category).isEqualTo("user");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private String insertL1(String runId, String content, String vectorLiteral,
                            int importance, int accessCount) {
        String pid = UniqueIdGenerator.generate();
        if (vectorLiteral == null) {
            jdbc.update(
                    "INSERT INTO ab_agent_memory "
                            + "(pid, tenant_id, memory_agent_id, memory_type, category, "
                            + " memory_title, memory_content, importance, source_run_id, "
                            + " access_count, shareable, scope, scope_key, demotion_count, "
                            + " created_at, updated_at, deleted_flag) "
                            + "VALUES (?, ?, ?, 'fact', 'session', ?, ?, ?, ?, ?, FALSE, "
                            + " 'user', ?, 0, NOW() - INTERVAL '5 minutes', NOW(), FALSE)",
                    pid, tenantId, agentCode, TEST_PREFIX + "title", content,
                    importance, runId, accessCount, userId);
        } else {
            jdbc.update(
                    "INSERT INTO ab_agent_memory "
                            + "(pid, tenant_id, memory_agent_id, memory_type, category, "
                            + " memory_title, memory_content, importance, source_run_id, "
                            + " access_count, shareable, scope, scope_key, demotion_count, "
                            + " embedding, created_at, updated_at, deleted_flag) "
                            + "VALUES (?, ?, ?, 'fact', 'session', ?, ?, ?, ?, ?, FALSE, "
                            + " 'user', ?, 0, ?::vector, NOW() - INTERVAL '5 minutes', NOW(), FALSE)",
                    pid, tenantId, agentCode, TEST_PREFIX + "title", content,
                    importance, runId, accessCount, userId, vectorLiteral);
        }
        return pid;
    }

    private String insertL2(String content, String vectorLiteral,
                            int importance, int accessCount, String contentHash) {
        String pid = UniqueIdGenerator.generate();
        jdbc.update(
                "INSERT INTO ab_agent_memory "
                        + "(pid, tenant_id, memory_agent_id, memory_type, category, "
                        + " memory_title, memory_content, importance, access_count, "
                        + " shareable, scope, scope_key, demotion_count, content_hash, "
                        + " embedding, created_at, updated_at, deleted_flag) "
                        + "VALUES (?, ?, ?, 'fact', 'user', ?, ?, ?, ?, FALSE, "
                        + " 'user', ?, 0, ?, ?::vector, "
                        + " NOW() - INTERVAL '2 days', NOW(), FALSE)",
                pid, tenantId, agentCode, TEST_PREFIX + "l2-title", content,
                importance, accessCount, userId, contentHash, vectorLiteral);
        return pid;
    }

    /** Build a unit vector with a single non-zero slot — convenient for cosine control. */
    private static String unitOneHotLiteral(int index, float magnitude) {
        StringBuilder sb = new StringBuilder(DIM * 4);
        sb.append('[');
        for (int i = 0; i < DIM; i++) {
            if (i > 0) sb.append(',');
            sb.append(i == index ? magnitude : 0.0f);
        }
        sb.append(']');
        return sb.toString();
    }

    private static String randomHash() {
        return MemoryL1L2Promoter.contentHash("arbitrary-" + UniqueIdGenerator.generate());
    }
}
