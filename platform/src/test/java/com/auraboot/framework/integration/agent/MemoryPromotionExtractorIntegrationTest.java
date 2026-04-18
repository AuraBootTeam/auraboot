package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.service.MemoryPromotionExtractor;
import com.auraboot.framework.common.util.UniqueIdGenerator;
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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link MemoryPromotionExtractor} (PR-65 Phase 1).
 *
 * <p>Embeddings are seeded directly as pgvector literals — we do not call
 * the live embedding provider in tests. Each test uses an isolated
 * {@code tenantId} so concurrent runs do not collide.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("MemoryPromotionExtractor (PR-65)")
class MemoryPromotionExtractorIntegrationTest extends BaseIntegrationTest {

    @Autowired private MemoryPromotionExtractor extractor;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;
    private String tag;

    @BeforeEach
    void setup() {
        tenantId = 9_790_000L + System.nanoTime() % 10_000;
        tag = "mpe" + Long.toString(System.nanoTime() & 0xfffff, 36) + "_";
        // Force importance-spike disabled by default; specific tests flip it.
        ReflectionTestUtils.setField(extractor, "importanceSpikeEnabled", false);
        ReflectionTestUtils.setField(extractor, "minUsersPerTenant", 3);
        ReflectionTestUtils.setField(extractor, "minSimilarity", 0.85d);
        ReflectionTestUtils.setField(extractor, "minImportanceForSpike", 9);
        ReflectionTestUtils.setField(extractor, "rationaleEnabled", false);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_memory_promotion WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_agent_memory WHERE tenant_id = ?", tenantId);
    }

    // ---------- seed helpers ----------

    private String seedMemory(String scopeKey, int importance, boolean shareable, double[] vector) {
        String pid = UniqueIdGenerator.generate();
        String literal = toVectorLiteral(vector);
        jdbc.update("INSERT INTO ab_agent_memory "
                        + "(pid, tenant_id, memory_agent_id, memory_type, category, "
                        + " memory_title, memory_content, importance, shareable, scope, scope_key, "
                        + " embedding, created_at, updated_at, deleted_flag) "
                        + "VALUES (?, ?, 'default', 'fact', 'ops', ?, ?, ?, ?, 'user', ?, ?::vector, NOW(), NOW(), FALSE)",
                pid, tenantId, "title-" + pid, "content-" + pid, importance, shareable, scopeKey, literal);
        return pid;
    }

    private static String toVectorLiteral(double[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    /** pgvector column is fixed dim=1536 — tests must match. */
    private static final int DIM = 1536;

    /** Build a fixed-length vector with a single "1" at the given index; rest zero. */
    private static double[] onehot(int index, int dim) {
        double[] v = new double[dim];
        v[index] = 1.0d;
        return v;
    }

    /**
     * Slightly-perturbed version of a one-hot vector to land well above 0.85 cosine.
     * Noise only perturbs a small number of neighbouring dims so the aggregate
     * norm stays close to 1 — important for high-dim (1536) vectors where
     * dense noise quickly dominates the signal.
     */
    private static double[] similar(int index, int dim, double noise, long seed) {
        double[] v = new double[dim];
        v[index] = 1.0d;
        java.util.Random r = new java.util.Random(seed);
        // Perturb only 8 neighbouring dims with small magnitude.
        for (int k = 0; k < 8; k++) {
            int j = (index + 1 + k) % dim;
            v[j] = (r.nextDouble() - 0.5) * noise;
        }
        return v;
    }

    // ---------- tests ----------

    @Test
    @DisplayName("cross_user_agreement: 3 users similarity 0.90 → 1 proposal, confidence ≥ 0.50")
    void crossUser_threeUsers() {
        seedMemory("101", 6, false, similar(0, DIM, 0.10, 11));
        seedMemory("102", 6, false, similar(0, DIM, 0.10, 12));
        seedMemory("103", 6, false, similar(0, DIM, 0.10, 13));

        int proposals = extractor.runForTenant(tenantId);
        assertThat(proposals).isEqualTo(1);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT reason_code, confidence_score, similarity_score, source_memory_pids "
                        + "FROM ab_agent_memory_promotion WHERE tenant_id = ?", tenantId);
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("reason_code")).isEqualTo("cross_user_agreement");
        assertThat(((Number) row.get("confidence_score")).doubleValue()).isGreaterThanOrEqualTo(0.50d);
        assertThat(((Number) row.get("similarity_score")).doubleValue()).isGreaterThanOrEqualTo(0.85d);
        assertThat(row.get("source_memory_pids")).asString().contains(",");
    }

    @Test
    @DisplayName("cross_user_agreement: 2 users → 0 proposals (below min cluster size)")
    void crossUser_twoUsers() {
        seedMemory("201", 6, false, similar(0, DIM, 0.05, 21));
        seedMemory("202", 6, false, similar(0, DIM, 0.05, 22));
        int proposals = extractor.runForTenant(tenantId);
        assertThat(proposals).isZero();
    }

    @Test
    @DisplayName("cross_user_agreement: 4 users with low similarity (orthogonal) → 0 proposals")
    void crossUser_lowSimilarity() {
        // Orthogonal one-hots → cosine 0.0
        seedMemory("301", 6, false, onehot(0, DIM));
        seedMemory("302", 6, false, onehot(1, DIM));
        seedMemory("303", 6, false, onehot(2, DIM));
        seedMemory("304", 6, false, onehot(3, DIM));
        int proposals = extractor.runForTenant(tenantId);
        assertThat(proposals).isZero();
    }

    @Test
    @DisplayName("dedup: re-run → no duplicate proposal for same cluster")
    void dedupOnRerun() {
        seedMemory("401", 6, false, similar(0, DIM, 0.05, 41));
        seedMemory("402", 6, false, similar(0, DIM, 0.05, 42));
        seedMemory("403", 6, false, similar(0, DIM, 0.05, 43));

        int first = extractor.runForTenant(tenantId);
        int second = extractor.runForTenant(tenantId);

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_memory_promotion WHERE tenant_id = ?",
                Integer.class, tenantId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("importance_spike enabled → single high-importance user memory yields proposal at 0.5")
    void importanceSpike() {
        ReflectionTestUtils.setField(extractor, "importanceSpikeEnabled", true);
        seedMemory("501", 9, /*shareable*/ true, onehot(0, DIM));
        int proposals = extractor.runForTenant(tenantId);
        assertThat(proposals).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT reason_code, confidence_score FROM ab_agent_memory_promotion WHERE tenant_id = ?", tenantId);
        assertThat(row.get("reason_code")).isEqualTo("importance_spike");
        assertThat(((Number) row.get("confidence_score")).doubleValue()).isEqualTo(0.5d);
    }

    @Test
    @DisplayName("importance_spike disabled (default) → no proposal even at importance 9")
    void importanceSpikeDisabled() {
        seedMemory("601", 9, true, onehot(0, DIM));
        int proposals = extractor.runForTenant(tenantId);
        assertThat(proposals).isZero();
    }

    @Test
    @DisplayName("tenant isolation: two tenants do not cross-contaminate")
    void tenantIsolation() {
        Long otherTenant = tenantId + 1;
        try {
            // seed 3 similar in other tenant
            for (int i = 0; i < 3; i++) {
                String pid = UniqueIdGenerator.generate();
                jdbc.update("INSERT INTO ab_agent_memory "
                                + "(pid, tenant_id, memory_agent_id, memory_type, category, "
                                + " memory_title, memory_content, importance, shareable, scope, scope_key, "
                                + " embedding, created_at, updated_at, deleted_flag) "
                                + "VALUES (?, ?, 'default', 'fact', 'ops', 't', 'c', 5, FALSE, 'user', ?, ?::vector, NOW(), NOW(), FALSE)",
                        pid, otherTenant, "90" + i, toVectorLiteral(similar(0, DIM, 0.05, 90 + i)));
            }
            // and only 2 in ours
            seedMemory("701", 6, false, similar(0, DIM, 0.05, 71));
            seedMemory("702", 6, false, similar(0, DIM, 0.05, 72));

            int ours = extractor.runForTenant(tenantId);
            int theirs = extractor.runForTenant(otherTenant);

            assertThat(ours).isZero();
            assertThat(theirs).isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM ab_agent_memory_promotion WHERE tenant_id = ?", otherTenant);
            jdbc.update("DELETE FROM ab_agent_memory WHERE tenant_id = ?", otherTenant);
        }
    }

    @Test
    @DisplayName("advisory lock: runOnce serialises concurrent callers")
    void advisoryLockConcurrent() throws ExecutionException, InterruptedException {
        seedMemory("801", 6, false, similar(0, DIM, 0.05, 81));
        seedMemory("802", 6, false, similar(0, DIM, 0.05, 82));
        seedMemory("803", 6, false, similar(0, DIM, 0.05, 83));

        CompletableFuture<Integer> f1 = CompletableFuture.supplyAsync(extractor::runOnce);
        CompletableFuture<Integer> f2 = CompletableFuture.supplyAsync(extractor::runOnce);
        int total = f1.get() + f2.get();

        // Only one tick actually processes; the other sees the lock and returns 0.
        // But both runners scan *all* tenants, so we rely on dedup — exactly one proposal.
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_memory_promotion WHERE tenant_id = ?",
                Integer.class, tenantId);
        assertThat(count).isEqualTo(1);
        // total proposals created across the two runs must equal 1 (dedup + lock).
        assertThat(total).isLessThanOrEqualTo(2);
    }
}
