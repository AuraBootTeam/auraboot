package com.auraboot.framework.rag.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.rag.util.VectorUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded retry of chunks whose embedding failed (G6). Scheduled via
 * SystemTaskInitializer ({@code sys-rag-embedding-retry}, every 5 min).
 *
 * <p>This is outbox-style queue processing of an explicit {@code failed} state,
 * not a self-heal of missing data: each attempt increments
 * {@code embedding_retry_count}; after {@value #MAX_RETRIES} attempts the chunk
 * moves to the terminal {@code failed_permanent} state and stays visible via the
 * {@code rag.embedding.retry} metric (outcome=exhausted).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingRetryService {

    static final int MAX_RETRIES = 5;
    static final int BATCH_LIMIT = 100;

    private final EmbeddingService embeddingService;
    private final JdbcTemplate jdbcTemplate;
    private final RagRetrievalMetrics metrics;

    /** Entry point invoked by the scheduler. @return number of chunks successfully embedded */
    public int retryFailedChunks() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT c.pid, c.content, c.tenant_id, c.embedding_retry_count, "
                + "kb.embedding_provider FROM ab_kb_chunk c "
                + "JOIN ab_knowledge_base kb ON c.kb_id = kb.pid "
                + "WHERE c.embedding_status = 'failed' AND c.embedding_retry_count < ? "
                + "ORDER BY c.updated_at ASC LIMIT ?",
                MAX_RETRIES, BATCH_LIMIT);
        if (rows.isEmpty()) {
            return 0;
        }

        // Group by (tenant, provider) so each group is one batch-embedding call.
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String key = row.get("tenant_id") + "|" + row.get("embedding_provider");
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }

        int succeeded = 0;
        for (List<Map<String, Object>> group : groups.values()) {
            succeeded += retryGroup(group);
        }
        log.info("Embedding retry pass: {} chunk(s) recovered of {} candidates", succeeded, rows.size());
        return succeeded;
    }

    private int retryGroup(List<Map<String, Object>> group) {
        long tenantId = ((Number) group.get(0).get("tenant_id")).longValue();
        String provider = (String) group.get(0).get("embedding_provider");
        List<String> texts = group.stream().map(r -> (String) r.get("content")).toList();

        // Scheduler thread has no MetaContext; embedding config lookup is tenant-scoped.
        boolean owns = !MetaContext.exists();
        if (owns) {
            MetaContext.setSystemTenantContext(tenantId);
        }
        try {
            List<float[]> embeddings;
            try {
                embeddings = embeddingService.embedBatch(tenantId, texts,
                        provider != null ? provider : "openai");
            } catch (Exception e) {
                log.warn("Embedding retry batch failed for tenant {} provider {}: {}",
                        tenantId, provider, e.getMessage());
                group.forEach(this::recordAttemptFailure);
                return 0;
            }
            int ok = 0;
            for (int i = 0; i < group.size(); i++) {
                float[] emb = i < embeddings.size() ? embeddings.get(i) : null;
                if (emb != null) {
                    jdbcTemplate.update(
                            "UPDATE ab_kb_chunk SET embedding = ?::vector, embedding_status = 'completed', "
                            + "updated_at = NOW() WHERE pid = ?",
                            VectorUtils.toVectorString(emb), group.get(i).get("pid"));
                    ok++;
                } else {
                    recordAttemptFailure(group.get(i));
                }
            }
            if (ok > 0) {
                metrics.recordEmbeddingRetry("success", ok);
            }
            return ok;
        } finally {
            if (owns) {
                MetaContext.clear();
            }
        }
    }

    private void recordAttemptFailure(Map<String, Object> row) {
        int attempts = ((Number) row.get("embedding_retry_count")).intValue() + 1;
        if (attempts >= MAX_RETRIES) {
            jdbcTemplate.update(
                    "UPDATE ab_kb_chunk SET embedding_status = 'failed_permanent', "
                    + "embedding_retry_count = ?, updated_at = NOW() WHERE pid = ?",
                    attempts, row.get("pid"));
            metrics.recordEmbeddingRetry("exhausted", 1);
            log.warn("Chunk {} embedding permanently failed after {} attempts", row.get("pid"), attempts);
        } else {
            jdbcTemplate.update(
                    "UPDATE ab_kb_chunk SET embedding_retry_count = ?, updated_at = NOW() WHERE pid = ?",
                    attempts, row.get("pid"));
            metrics.recordEmbeddingRetry("failed", 1);
        }
    }
}
