package com.auraboot.framework.agent.service;

import com.auraboot.framework.rag.service.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * <p>TODO(post-phase-1): Today we call {@link EmbeddingService#embed} which
 * uses the CloudConfig "embedding" provider type. If no embedding provider
 * is configured, no fallback to a text-generation provider is attempted —
 * the extractor logs WARN and skips. A dedicated embedding fallback (e.g.
 * local MiniLM) may be worth adding before Phase 7 tuning so tenants
 * without paid embedding keys still get proposals.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryEmbeddingService {

    private static final String DEFAULT_PROVIDER = "openai";

    private final EmbeddingService embeddingService;
    private final JdbcTemplate jdbcTemplate;

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

        float[] vector = embeddingService.embed(tenantId, content, DEFAULT_PROVIDER);
        if (vector == null) {
            log.warn("MemoryEmbeddingService: embedding provider unavailable for tenant {}, memory {} — leaving null",
                    tenantId, memoryPid);
            return false;
        }
        String literal = toVectorLiteral(vector);
        int affected = jdbcTemplate.update(
                "UPDATE ab_agent_memory SET embedding = ?::vector WHERE pid = ?",
                literal, memoryPid);
        return affected > 0;
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
