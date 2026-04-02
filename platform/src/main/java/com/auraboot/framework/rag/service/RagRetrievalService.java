package com.auraboot.framework.rag.service;

import com.auraboot.framework.rag.dto.RetrievalResult;
import com.auraboot.framework.rag.entity.KnowledgeBase;
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
        if (query == null || query.isBlank()) return List.of();

        // Query expansion for short/domain-specific queries
        QueryRewriteService.QueryRewriteResult rewrite = queryRewriteService.rewrite(query);
        String searchQuery = rewrite.expandedQuery();

        int k = topK != null && topK > 0 ? Math.min(topK, 20) : DEFAULT_TOP_K;
        double dist = threshold != null && threshold > 0 ? threshold : DEFAULT_THRESHOLD;

        // Resolve which KBs to search
        List<String> targetKbs = resolveTargetKbs(tenantId, kbPids);
        if (targetKbs.isEmpty()) {
            log.debug("No active knowledge bases found for tenant {}", tenantId);
            return List.of();
        }

        // Get embedding provider from the first KB
        KnowledgeBase firstKb = kbService.findKbByPid(targetKbs.get(0));
        if (firstKb == null) return List.of();

        // Try to embed query
        float[] queryEmbedding = null;
        try {
            queryEmbedding = embeddingService.embed(tenantId, query, firstKb.getEmbeddingProvider());
        } catch (Exception e) {
            log.warn("Embedding failed, falling back to keyword search: {}", e.getMessage());
        }

        if (queryEmbedding != null) {
            return rerankedResults(hybridSearch(queryEmbedding, searchQuery, targetKbs, k, dist), query, k);
        } else {
            return rerankedResults(keywordSearch(searchQuery, targetKbs, k), query, k);
        }
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
            return vectorOnlySearch(queryEmbedding, targetKbs, topK, threshold);
        }
    }

    /**
     * BM25 keyword-only fallback when embedding is unavailable.
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
     * Handles CJK characters by treating each character as a separate term.
     */
    static String buildTsQuery(String query) {
        if (query == null || query.isBlank()) return "";
        List<String> terms = new ArrayList<>();
        StringBuilder currentTerm = new StringBuilder();

        for (int i = 0; i < query.length(); i++) {
            char ch = query.charAt(i);
            if (Character.isWhitespace(ch)) {
                if (currentTerm.length() > 0) {
                    terms.add(currentTerm.toString());
                    currentTerm.setLength(0);
                }
            } else if (Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN
                    || Character.UnicodeScript.of(ch) == Character.UnicodeScript.HIRAGANA
                    || Character.UnicodeScript.of(ch) == Character.UnicodeScript.KATAKANA
                    || Character.UnicodeScript.of(ch) == Character.UnicodeScript.HANGUL) {
                // CJK: flush any Latin buffer, then add single char as term
                if (currentTerm.length() > 0) {
                    terms.add(currentTerm.toString());
                    currentTerm.setLength(0);
                }
                terms.add(String.valueOf(ch));
            } else if (Character.isLetterOrDigit(ch) || ch == '_') {
                currentTerm.append(ch);
            }
            // Skip punctuation
        }
        if (currentTerm.length() > 0) {
            terms.add(currentTerm.toString());
        }

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
     */
    public boolean hasActiveKnowledgeBases(Long tenantId) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM ab_knowledge_base "
                    + "WHERE tenant_id = ? AND status = 'active' "
                    + "AND (deleted_flag IS NULL OR deleted_flag = FALSE) "
                    + "AND chunk_count > 0",
                    Integer.class, tenantId);
            return count != null && count > 0;
        } catch (Exception e) {
            return false;
        }
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
