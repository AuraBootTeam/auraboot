package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.metrics.MemoryPromotionMetrics;
import com.auraboot.framework.rag.service.EmbeddingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Memory-scoped embedding writer/reader for Memory Promotion (PR-65).
 *
 * <p>Reuses {@code ab_agent_memory.embedding vector(1536)} — the column is
 * already provisioned and indexed (HNSW + cosine ops) for other memory
 * surfaces. This service is the single write path for promotion-related
 * embedding work:
 *
 * <ul>
 *   <li>{@link #computeAndStore(String)} — compute & persist the vector
 *       for a memory row.</li>
 *   <li>{@link #resolveEmbedding(String)} — read or lazily compute +
 *       store the vector; returns null if the embedding provider is
 *       unavailable (callers skip the memory rather than falling back
 *       to a fake score, per plan §5 cost-control).</li>
 * </ul>
 *
 * <p><b>Round-4 hardening (PR-74):</b>
 * <ul>
 *   <li>Content is normalised (lowercase, whitespace-collapsed, trailing
 *       punctuation trimmed) <i>before</i> the embedding API call so
 *       trivially-different wordings cluster together. The stored
 *       {@code memory_content} is never mutated — only the string handed
 *       to the embedder.</li>
 *   <li>The vector returned by the provider is validated against the
 *       column dimension ({@value #EXPECTED_DIM}). A mismatched or null
 *       vector is logged at WARN, counted via
 *       {@link MemoryPromotionMetrics#recordEmbeddingDimMismatch}, and
 *       dropped — we never write a malformed vector that would blow up
 *       at INSERT time.</li>
 * </ul>
 *
 * <p>TODO(post-phase-1): Today we call {@link EmbeddingService#embed} which
 * uses the CloudConfig "embedding" provider type. If no embedding provider
 * is configured, no fallback to a text-generation provider is attempted —
 * the extractor logs WARN and skips. A dedicated embedding fallback (e.g.
 * local MiniLM) may be worth adding before Phase 7 tuning so tenants
 * without paid embedding keys still get proposals.
 */
@Slf4j
@Service
public class MemoryEmbeddingService {

    private static final String DEFAULT_PROVIDER = "openai";

    /** Must match the {@code vector(N)} column width in schema.sql. */
    static final int EXPECTED_DIM = 1536;

    private final EmbeddingService embeddingService;
    private final JdbcTemplate jdbcTemplate;

    /** Optional — metrics bean may not be present in minimal slice tests. */
    @Autowired(required = false)
    private MemoryPromotionMetrics metrics;

    public MemoryEmbeddingService(EmbeddingService embeddingService, JdbcTemplate jdbcTemplate) {
        this.embeddingService = embeddingService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Compute the embedding for the given memory row and persist it. No-op
     * if the row is missing, already embedded, or the provider is down.
     *
     * @return true if a new vector was written, false otherwise.
     */
    public boolean computeAndStore(String memoryPid) {
        if (memoryPid == null || memoryPid.isBlank()) {
            return false;
        }
        Map<String, Object> row;
        try {
            row = jdbcTemplate.queryForMap(
                    "SELECT tenant_id, memory_content, embedding IS NOT NULL AS has_embedding "
                            + "FROM ab_agent_memory "
                            + "WHERE pid = ? "
                            + "AND (deleted_flag = FALSE OR deleted_flag IS NULL)",
                    memoryPid);
        } catch (EmptyResultDataAccessException e) {
            log.debug("computeAndStore: memory {} not found", memoryPid);
            return false;
        }
        if (Boolean.TRUE.equals(row.get("has_embedding"))) {
            return false;
        }
        Long tenantId = ((Number) row.get("tenant_id")).longValue();
        String content = (String) row.get("memory_content");
        if (content == null || content.isBlank()) {
            return false;
        }

        String normalised = normaliseForEmbedding(content);
        if (normalised.isEmpty()) {
            return false;
        }

        float[] vector = embeddingService.embed(tenantId, normalised, DEFAULT_PROVIDER);
        if (vector == null || vector.length == 0) {
            log.warn("MemoryEmbeddingService: embedding provider returned null/empty vector for tenant {} memory {} — leaving null",
                    tenantId, memoryPid);
            recordDimMismatch(tenantId, DEFAULT_PROVIDER, vector == null ? 0 : vector.length);
            return false;
        }
        if (vector.length != EXPECTED_DIM) {
            log.warn("MemoryEmbeddingService: embedding dim mismatch tenant={} memory={} provider={} returnedDim={} expectedDim={} — dropping",
                    tenantId, memoryPid, DEFAULT_PROVIDER, vector.length, EXPECTED_DIM);
            recordDimMismatch(tenantId, DEFAULT_PROVIDER, vector.length);
            return false;
        }

        String literal = toVectorLiteral(vector);
        int affected = jdbcTemplate.update(
                "UPDATE ab_agent_memory SET embedding = ?::vector WHERE pid = ?",
                literal, memoryPid);
        return affected > 0;
    }

    private void recordDimMismatch(Long tenantId, String provider, int actualDim) {
        if (metrics != null) {
            metrics.recordEmbeddingDimMismatch(tenantId, provider, actualDim);
        }
    }

    /**
     * Normalise free-form memory content before it's shipped to the embedder.
     * The stored content is not mutated — only the string handed to the
     * embedding API. Two trivially-different wordings (case, trailing
     * whitespace, punctuation, multiple spaces) must hash to the same
     * normalised form so the cross-user cosine clustering can see them as
     * near-neighbours.
     *
     * <p>Rules:
     * <ul>
     *   <li>Lowercase (Locale.ROOT — content is mixed-language).</li>
     *   <li>Strip leading/trailing whitespace.</li>
     *   <li>Collapse internal whitespace runs to a single ASCII space.</li>
     *   <li>Remove trailing sentence punctuation (period, exclamation,
     *       question mark, full-width 。！？) and surrounding quotation
     *       marks that don't carry semantic content.</li>
     * </ul>
     *
     * <p>Chinese / CJK text is preserved — we do not alter characters
     * other than whitespace and the specific punctuation listed above.
     */
    static String normaliseForEmbedding(String content) {
        if (content == null) return "";
        String s = content.toLowerCase(java.util.Locale.ROOT);
        // Collapse all Unicode whitespace runs to single ASCII space, then trim.
        s = s.replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) return s;

        // Strip matched surrounding quotes repeatedly.
        while (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            boolean bothQuotes =
                    (first == '"' && last == '"')
                            || (first == '\'' && last == '\'')
                            || (first == '“' && last == '”')
                            || (first == '‘' && last == '’')
                            || (first == '「' && last == '」');
            if (!bothQuotes) break;
            s = s.substring(1, s.length() - 1).trim();
            if (s.isEmpty()) return s;
        }

        // Strip trailing sentence punctuation (ASCII + CJK fullwidth).
        while (!s.isEmpty()) {
            char last = s.charAt(s.length() - 1);
            if (last == '.' || last == '!' || last == '?' || last == ',' || last == ';' || last == ':'
                    || last == '。' || last == '！' || last == '？' || last == '，' || last == '；' || last == '：') {
                s = s.substring(0, s.length() - 1).trim();
            } else {
                break;
            }
        }
        return s;
    }

    /**
     * Return the embedding vector for the given memory, computing lazily if
     * missing. Returns {@code null} if the memory does not exist, has no
     * content, or the embedding provider is down.
     */
    public double[] resolveEmbedding(String memoryPid) {
        if (memoryPid == null || memoryPid.isBlank()) {
            return null;
        }
        double[] existing = readEmbedding(memoryPid);
        if (existing != null) {
            return existing;
        }
        boolean stored = computeAndStore(memoryPid);
        if (!stored) {
            return null;
        }
        return readEmbedding(memoryPid);
    }

    /**
     * Read an existing embedding as {@code double[]}. Returns null when the
     * column is null or the row is missing.
     */
    public double[] readEmbedding(String memoryPid) {
        try {
            String text = jdbcTemplate.queryForObject(
                    "SELECT embedding::text FROM ab_agent_memory "
                            + "WHERE pid = ? AND (deleted_flag = FALSE OR deleted_flag IS NULL)",
                    String.class, memoryPid);
            return parseVectorLiteral(text);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * Convert pgvector text literal ({@code [0.1,0.2,...]}) to a double array.
     * Returns null for null / blank input.
     */
    public static double[] parseVectorLiteral(String literal) {
        if (literal == null) return null;
        String s = literal.trim();
        if (s.isEmpty() || s.equals("null")) return null;
        if (s.startsWith("[")) s = s.substring(1);
        if (s.endsWith("]")) s = s.substring(0, s.length() - 1);
        if (s.isEmpty()) return new double[0];
        String[] parts = s.split(",");
        double[] out = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Double.parseDouble(parts[i].trim());
        }
        return out;
    }

    private static String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 8);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vector[i]);
        }
        sb.append(']');
        return sb.toString();
    }
}
