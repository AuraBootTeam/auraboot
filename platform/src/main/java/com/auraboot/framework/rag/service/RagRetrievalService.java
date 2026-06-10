package com.auraboot.framework.rag.service;

import com.auraboot.framework.rag.dto.RetrievalOutcome;
import com.auraboot.framework.rag.dto.RetrievalResult;
import com.auraboot.framework.rag.entity.KnowledgeBase;
import com.auraboot.framework.rag.util.CjkBigramSegmenter;
import com.auraboot.framework.rag.util.VectorUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * RAG retrieval service — hybrid search combining vector similarity and BM25 keyword matching.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagRetrievalService {

    private final EmbeddingService embeddingService;
    private final KnowledgeBaseService kbService;
    private final QueryRewriteService queryRewriteService;
    private final JdbcTemplate jdbcTemplate;
    private final RagRetrievalMetrics metrics;

    private static final int DEFAULT_TOP_K = 5;
    private static final double DEFAULT_THRESHOLD = 0.8;
    private static final double DEFAULT_VECTOR_WEIGHT = 0.7;

    /**
     * Retrieve relevant chunks for a query across specified knowledge bases.
     * Tries hybrid search first, falls back to keyword search if embedding fails.
     *
     * @param tenantId  current tenant
     * @param query     user question
     * @param kbPids    knowledge base PIDs to search (null = all active KBs)
     * @param topK      max results (default 5)
     * @param threshold max cosine distance (default 0.8, lower = more similar)
     * @return ranked list of matching chunks with metadata
     */
    public List<RetrievalResult> retrieve(Long tenantId, String query,
                                            List<String> kbPids, Integer topK, Double threshold) {
        return retrieveWithDiagnostics(tenantId, query, kbPids, topK, threshold).getResults();
    }

    /**
     * Same as {@link #retrieve} but also returns user-visible warnings about
     * silently-degraded recall (G8), and records retrieval metrics (G6).
     */
    public RetrievalOutcome retrieveWithDiagnostics(Long tenantId, String query,
                                            List<String> kbPids, Integer topK, Double threshold) {
        List<String> warnings = new ArrayList<>();
        long startedAt = System.currentTimeMillis();
        if (query == null || query.isBlank()) return new RetrievalOutcome(List.of(), warnings);

        // Query expansion for short/domain-specific queries
        QueryRewriteService.QueryRewriteResult rewrite = queryRewriteService.rewrite(query);
        String searchQuery = rewrite.expandedQuery();

        int k = topK != null && topK > 0 ? Math.min(topK, 20) : DEFAULT_TOP_K;
        double dist = threshold != null && threshold > 0 ? threshold : DEFAULT_THRESHOLD;

        // Resolve which KBs to search
        List<String> targetKbs = resolveTargetKbs(tenantId, kbPids);
        if (targetKbs.isEmpty()) {
            log.debug("No active knowledge bases found for tenant {}", tenantId);
            return new RetrievalOutcome(List.of(), warnings);
        }

        // Get embedding provider from the first KB.
        //
        // Cross-provider safety check: if targetKbs span multiple KBs with
        // different embedding providers (e.g. openai 1536-dim + qwen 1024-dim),
        // the query is embedded with only the first KB's provider but the
        // hybrid SQL mixes chunks from ALL targetKbs — pgvector silently
        // fails the cosine `<=>` operator on dimension mismatch. Previous
        // behavior was zero results or confusing SQL error. Per deep-review
        // P3-1: drop incompatible KBs + log.
        KnowledgeBase firstKb = kbService.findKbByPid(targetKbs.get(0));
        if (firstKb == null) return new RetrievalOutcome(List.of(), warnings);
        if (targetKbs.size() > 1) {
            String firstProvider = firstKb.getEmbeddingProvider();
            List<String> compatibleKbs = new ArrayList<>();
            List<String> incompatibleKbs = new ArrayList<>();
            for (String pid : targetKbs) {
                KnowledgeBase kb = kbService.findKbByPid(pid);
                if (kb == null) continue;
                if (java.util.Objects.equals(kb.getEmbeddingProvider(), firstProvider)) {
                    compatibleKbs.add(pid);
                } else {
                    incompatibleKbs.add(pid);
                }
            }
            if (!incompatibleKbs.isEmpty()) {
                log.warn("retrieve: dropping {} KB(s) with embedding provider != {} to avoid pgvector "
                                + "dimension mismatch: kept={} dropped={}",
                        incompatibleKbs.size(), firstProvider, compatibleKbs, incompatibleKbs);
                metrics.recordKbDropped(incompatibleKbs.size());
                warnings.add("Skipped " + incompatibleKbs.size() + " knowledge base(s) whose embedding "
                        + "provider differs from '" + firstProvider + "' (vector dimension mismatch): "
                        + incompatibleKbs + ". Search them separately or re-embed with one provider.");
                targetKbs = compatibleKbs;
                if (targetKbs.isEmpty()) return new RetrievalOutcome(List.of(), warnings);
            }
        }

        // Try to embed query
        float[] queryEmbedding = null;
        try {
            queryEmbedding = embeddingService.embed(tenantId, query, firstKb.getEmbeddingProvider());
        } catch (Exception e) {
            log.warn("Embedding failed, falling back to keyword search: {}", e.getMessage());
            metrics.recordDegraded("embedding_failed");
        }

        List<RetrievalResult> results;
        String path;
        if (queryEmbedding != null) {
            path = "hybrid";
            results = rerankedResults(hybridSearch(queryEmbedding, searchQuery, targetKbs, k, dist), query, k);
        } else {
            path = "keyword";
            results = rerankedResults(keywordSearch(searchQuery, targetKbs, k), query, k);
        }
        metrics.recordRetrieval(path, System.currentTimeMillis() - startedAt, results.size());
        return new RetrievalOutcome(results, warnings);
    }

    /**
     * Hybrid search combining vector distance and BM25 keyword scoring.
     */
    private List<RetrievalResult> hybridSearch(float[] queryEmbedding, String query,
                                                 List<String> targetKbs, int topK, double threshold) {
        String vectorStr = VectorUtils.toVectorString(queryEmbedding);
        String tsQuery = buildTsQuery(query);
        String placeholders = String.join(",", Collections.nCopies(targetKbs.size(), "?"));

        String sql = "WITH candidates AS ("
                + "SELECT c.pid AS chunk_pid, c.chunk_index, c.content, d.doc_name, kb.name AS kb_name, "
                + "CASE WHEN c.embedding IS NOT NULL AND c.embedding_status = 'completed' "
                + "  THEN (c.embedding <=> ?::vector) ELSE 1.0 END AS distance, "
                + "CASE WHEN c.tsv IS NOT NULL AND ?::tsquery @@ c.tsv "
                + "  THEN ts_rank_cd(c.tsv, ?::tsquery) ELSE 0.0 END AS bm25_raw "
                + "FROM ab_kb_chunk c "
                + "JOIN ab_kb_document d ON c.doc_id = d.pid "
                + "JOIN ab_knowledge_base kb ON c.kb_id = kb.pid "
                + "WHERE c.kb_id IN (" + placeholders + ") "
                + "AND ("
                + "  (c.embedding IS NOT NULL AND c.embedding_status = 'completed' AND (c.embedding <=> ?::vector) < ?) "
                + "  OR (c.tsv IS NOT NULL AND ?::tsquery @@ c.tsv)"
                + ")"
                + ") "
                + "SELECT *, (1.0 - distance) AS vector_score, "
                + "CASE WHEN (SELECT MAX(bm25_raw) FROM candidates) > 0 "
                + "  THEN bm25_raw / (SELECT MAX(bm25_raw) FROM candidates) ELSE 0.0 END AS bm25_score, "
                + DEFAULT_VECTOR_WEIGHT + " * (1.0 - distance) + " + (1.0 - DEFAULT_VECTOR_WEIGHT) + " * "
                + "CASE WHEN (SELECT MAX(bm25_raw) FROM candidates) > 0 "
                + "  THEN bm25_raw / (SELECT MAX(bm25_raw) FROM candidates) ELSE 0.0 END AS hybrid_score "
                + "FROM candidates ORDER BY hybrid_score DESC LIMIT ?";

        // Params: vectorStr, tsQuery, tsQuery, kbPids..., vectorStr, threshold, tsQuery, limit
        List<Object> params = new ArrayList<>();
        params.add(vectorStr);
        params.add(tsQuery);
        params.add(tsQuery);
        params.addAll(targetKbs);
        params.add(vectorStr);
        params.add(threshold);
        params.add(tsQuery);
        params.add(topK);

        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, params.toArray());
            return rows.stream().map(this::mapRow).toList();
        } catch (Exception e) {
            log.error("Hybrid search failed, falling back to vector-only: {}", e.getMessage());
            metrics.recordDegraded("hybrid_sql_failed");
            return vectorOnlySearch(queryEmbedding, targetKbs, topK, threshold);
        }
    }

    /**
     * BM25 keyword-only fallback when embedding is unavailable.
     *
     * <p><b>catch-pattern P2 weak (Bugfix-0, owner decision 2026-05-28)</b>:
     * the {@code catch (Exception) -> return empty} at the bottom of this
     * method is the bottom of the fallback chain (hybrid → vector-only →
     * keyword-only). Returning empty lets the LLM proceed with no RAG
     * context (graceful degradation); {@code log.error} provides operator
     * signal. Owner accepted as P2 weak rather than migrating to
     * propagation — RAG is an optional augmentation, and an empty result
     * is a valid degraded answer ("no relevant snippets found"). See
     * Bugfix-0 audit docs/backlog/2026-05-27-rag-catch-exception-audit.md
     * cluster 1.
     */
    private List<RetrievalResult> keywordSearch(String query, List<String> targetKbs, int topK) {
        String tsQuery = buildTsQuery(query);
        String placeholders = String.join(",", Collections.nCopies(targetKbs.size(), "?"));

        String sql = "SELECT c.pid AS chunk_pid, c.chunk_index, c.content, d.doc_name, kb.name AS kb_name, "
                + "1.0 AS distance, ts_rank_cd(c.tsv, ?::tsquery) AS bm25_raw, "
                + "0.0 AS vector_score, "
                + "ts_rank_cd(c.tsv, ?::tsquery) AS bm25_score, "
                + "ts_rank_cd(c.tsv, ?::tsquery) AS hybrid_score "
                + "FROM ab_kb_chunk c "
                + "JOIN ab_kb_document d ON c.doc_id = d.pid "
                + "JOIN ab_knowledge_base kb ON c.kb_id = kb.pid "
                + "WHERE c.kb_id IN (" + placeholders + ") "
                + "AND c.tsv IS NOT NULL AND ?::tsquery @@ c.tsv "
                + "ORDER BY hybrid_score DESC LIMIT ?";

        List<Object> params = new ArrayList<>();
        params.add(tsQuery);
        params.add(tsQuery);
        params.add(tsQuery);
        params.addAll(targetKbs);
        params.add(tsQuery);
        params.add(topK);

        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, params.toArray());
            return rows.stream().map(this::mapRow).toList();
        } catch (Exception e) {
            log.error("Keyword search failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Pure vector fallback when hybrid SQL fails (e.g., tsv column missing).
     *
     * <p><b>catch-pattern P2 weak (Bugfix-0, owner decision 2026-05-28)</b>:
     * the {@code catch (Exception) -> return empty} at the bottom of this
     * method is the second tier of the fallback chain hybrid → vector-only
     * → keyword-only (called from hybrid via line 129 on exception). Same
     * P2 weak rationale as {@link #keywordSearch}: empty result = degraded
     * RAG answer; not a hard failure to propagate. See Bugfix-0 audit
     * docs/backlog/2026-05-27-rag-catch-exception-audit.md cluster 1.
     */
    private List<RetrievalResult> vectorOnlySearch(float[] queryEmbedding,
                                                     List<String> targetKbs, int topK, double threshold) {
        String vectorStr = VectorUtils.toVectorString(queryEmbedding);
        String placeholders = String.join(",", Collections.nCopies(targetKbs.size(), "?"));

        String sql = "SELECT c.pid AS chunk_pid, c.chunk_index, c.content, "
                + "(c.embedding <=> ?::vector) AS distance, "
                + "d.doc_name, kb.name AS kb_name "
                + "FROM ab_kb_chunk c "
                + "JOIN ab_kb_document d ON c.doc_id = d.pid "
                + "JOIN ab_knowledge_base kb ON c.kb_id = kb.pid "
                + "WHERE c.kb_id IN (" + placeholders + ") "
                + "AND c.embedding IS NOT NULL "
                + "AND c.embedding_status = 'completed' "
                + "AND (c.embedding <=> ?::vector) < ? "
                + "ORDER BY c.embedding <=> ?::vector "
                + "LIMIT ?";

        List<Object> params = new ArrayList<>();
        params.add(vectorStr);
        params.addAll(targetKbs);
        params.add(vectorStr);
        params.add(threshold);
        params.add(vectorStr);
        params.add(topK);

        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, params.toArray());
            return rows.stream().map(row -> RetrievalResult.builder()
                    .chunkPid((String) row.get("chunk_pid"))
                    .chunkIndex(((Number) row.get("chunk_index")).intValue())
                    .content((String) row.get("content"))
                    .distance(((Number) row.get("distance")).doubleValue())
                    .similarity(1.0 - ((Number) row.get("distance")).doubleValue())
                    .vectorScore(1.0 - ((Number) row.get("distance")).doubleValue())
                    .docName((String) row.get("doc_name"))
                    .kbName((String) row.get("kb_name"))
                    .build()).toList();
        } catch (Exception e) {
            log.error("Vector-only search failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Build a tsquery string from user input. Splits into terms joined with '|' (OR).
     * CJK runs are expanded into overlapping bigrams matching the index-time
     * segmentation in {@code KbChunkIngestPipeline} (G2) — see
     * {@link com.auraboot.framework.rag.util.CjkBigramSegmenter}.
     */
    static String buildTsQuery(String query) {
        List<String> terms = CjkBigramSegmenter.tsQueryTerms(query);
        if (terms.isEmpty()) return "";
        return String.join(" | ", terms);
    }

    private List<RetrievalResult> rerankedResults(List<RetrievalResult> results, String query, int maxK) {
        return queryRewriteService.rerank(results, query, maxK);
    }

    private RetrievalResult mapRow(Map<String, Object> row) {
        double distance = row.get("distance") != null ? ((Number) row.get("distance")).doubleValue() : 1.0;
        double vectorScore = row.get("vector_score") != null ? ((Number) row.get("vector_score")).doubleValue() : 0.0;
        double bm25Score = row.get("bm25_score") != null ? ((Number) row.get("bm25_score")).doubleValue() : 0.0;
        double hybridScore = row.get("hybrid_score") != null ? ((Number) row.get("hybrid_score")).doubleValue() : 0.0;

        return RetrievalResult.builder()
                .chunkPid((String) row.get("chunk_pid"))
                .chunkIndex(((Number) row.get("chunk_index")).intValue())
                .content((String) row.get("content"))
                .distance(distance)
                .similarity(1.0 - distance)
                .vectorScore(vectorScore)
                .bm25Score(bm25Score)
                .hybridScore(hybridScore)
                .docName((String) row.get("doc_name"))
                .kbName((String) row.get("kb_name"))
                .build();
    }

    /**
     * Build a formatted RAG context section for injection into the LLM system prompt.
     */
    public String buildRagContext(List<RetrievalResult> results) {
        if (results == null || results.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("\n\n## Reference Knowledge\n");
        sb.append("Use the following information to answer the user's question. ");
        sb.append("Cite sources using [Source: docName, Chunk N] format.\n\n");

        for (RetrievalResult r : results) {
            sb.append("### [Source: ").append(r.getDocName())
              .append(", Chunk ").append(r.getChunkIndex()).append("]\n");
            sb.append(r.getContent()).append("\n\n---\n\n");
        }
        return sb.toString();
    }

    /**
     * Check if a tenant has any active knowledge bases with embedded chunks.
     *
     * <p>DB errors propagate to caller (AuraBotChatService:619 wraps the entire
     * RAG path in an outer try/catch returning empty context). Bugfix-0
     * (2026-05-27) removed a {@code catch (Exception) -> return false} A1
     * anti-pattern that silently masked schema drift / connection issues as
     * "no KB present", leaving operators with no signal in logs.
     */
    public boolean hasActiveKnowledgeBases(Long tenantId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_knowledge_base "
                + "WHERE tenant_id = ? AND status = 'active' "
                + "AND (deleted_flag IS NULL OR deleted_flag = FALSE) "
                + "AND chunk_count > 0",
                Integer.class, tenantId);
        return count != null && count > 0;
    }

    private List<String> resolveTargetKbs(Long tenantId, List<String> kbPids) {
        if (kbPids != null && !kbPids.isEmpty()) {
            return kbPids;
        }
        // Default: all active KBs with chunks for this tenant
        return jdbcTemplate.queryForList(
                "SELECT pid FROM ab_knowledge_base "
                + "WHERE tenant_id = ? AND status = 'active' "
                + "AND (deleted_flag IS NULL OR deleted_flag = FALSE) "
                + "AND chunk_count > 0",
                String.class, tenantId);
    }
}
