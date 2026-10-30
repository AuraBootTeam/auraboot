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
 * <p>Embedding profiles remain explicit CloudConfig entries. The selected default
 * model is local Qwen3-Embedding:4b; missing configuration does not trigger a
 * text-generation fallback or an automatic model download.
 */
@Slf4j
@Service
public class MemoryEmbeddingService {

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
     * Explicitly compute or rebuild the embedding for a live memory row. Existing
     * vectors are reused only when their space and content snapshot are current.
     *
     * @return true if a new vector was written, false otherwise.
     */
    public boolean computeAndStore(String memoryPid) {
        return "rebuilt".equals(computeAndStoreRow(memoryPid, loadVectorRow(memoryPid), null, null));
    }

    /** Operator-only adapter: prepare outside a transaction; the caller atomically archives the exact CAS snapshot. */
    String rebuildScoped(String memoryPid, Long tenantId, String agentCode, String expectedSpace,
                         java.util.function.Function<PreparedRebuild, String> commit) {
        java.util.Objects.requireNonNull(tenantId, "tenantId");
        java.util.Objects.requireNonNull(agentCode, "agentCode");
        java.util.Objects.requireNonNull(expectedSpace, "expectedSpace");
        java.util.Objects.requireNonNull(commit, "commit");
        Map<String, Object> row;
        try {
            row = jdbcTemplate.queryForMap(vectorRowSelect()
                    + " WHERE pid=? AND tenant_id=? AND memory_agent_id=? AND "
                    + com.auraboot.framework.agent.memory.MemoryReadPolicy.LIVE, memoryPid, tenantId, agentCode);
        } catch (EmptyResultDataAccessException absent) { return "not_found_or_inactive"; }
        return computeAndStoreRow(memoryPid, row, expectedSpace, commit);
    }

    private String computeAndStoreRow(String memoryPid, Map<String, Object> row, String expectedSpace,
                                      java.util.function.Function<PreparedRebuild, String> commit) {
        if (row == null) return "not_found_or_inactive";
        Long tenantId = ((Number) row.get("tenant_id")).longValue();
        String content = (String) row.get("memory_content");
        if (content == null || content.isBlank()) return "empty_content";
        String hash = com.auraboot.framework.agent.memory.MemoryVectorPolicy.sha256(content);
        String currentSpace = embeddingService.currentSpace(tenantId, null, EXPECTED_DIM);
        if (currentSpace == null) return "provider_unconfigured";
        if (expectedSpace != null && !expectedSpace.equals(currentSpace)) return "space_changed";
        if (row.get("embedding_text") != null && currentSpace.equals(row.get("embedding_space"))
                && hash.equals(row.get("embedding_content_hash"))) return "already_current";
        String normalised = normaliseForEmbedding(content);
        if (normalised.isEmpty()) return "empty_content";
        var embedded = embeddingService.embedWithIdentity(tenantId, normalised, null);
        if (embedded == null || embedded.values().length != EXPECTED_DIM) {
            recordDimMismatch(tenantId, "auto", embedded == null ? 0 : embedded.values().length);
            return "invalid_embedding";
        }
        if (expectedSpace != null && (!expectedSpace.equals(embedded.space())
                || !expectedSpace.equals(embeddingService.currentSpace(tenantId, null, EXPECTED_DIM)))) return "space_changed";
        PreparedRebuild prepared = new PreparedRebuild(memoryPid,
                java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(row)),
                toVectorLiteral(embedded.values()), embedded.space(), hash);
        return commit == null ? storePrepared(prepared) : commit.apply(prepared);
    }

    record PreparedRebuild(String pid, Map<String, Object> before, String vector, String space, String contentHash) {}

    /** No network access here; the operator composes this CAS with a mandatory joined audit transaction. */
    String storePrepared(PreparedRebuild prepared) {
        Map<String, Object> row = prepared.before();
        int written = jdbcTemplate.update(
                "UPDATE ab_agent_memory SET embedding=?::vector, embedding_space=?, embedding_content_hash=?, embedding_generated_at=NOW() "
                        + "WHERE pid=? AND tenant_id=? AND memory_content IS NOT DISTINCT FROM ? "
                        + "AND embedding::text IS NOT DISTINCT FROM ? AND embedding_space IS NOT DISTINCT FROM ? "
                        + "AND embedding_content_hash IS NOT DISTINCT FROM ? AND memory_agent_id IS NOT DISTINCT FROM ? "
                        + "AND embedding_generated_at IS NOT DISTINCT FROM ?::timestamptz AND "
                        + com.auraboot.framework.agent.memory.MemoryReadPolicy.LIVE,
                prepared.vector(), prepared.space(), prepared.contentHash(), prepared.pid(), row.get("tenant_id"), row.get("memory_content"),
                row.get("embedding_text"), row.get("embedding_space"), row.get("embedding_content_hash"), row.get("memory_agent_id"), row.get("embedding_generated_at"));
        return written == 1 ? "rebuilt" : "conflict";
    }

    private static String vectorRowSelect() {
        return "SELECT tenant_id,memory_agent_id,memory_content,embedding::text AS embedding_text,embedding_space,embedding_content_hash,embedding_generated_at::text AS embedding_generated_at FROM ab_agent_memory";
    }

    private Map<String, Object> loadVectorRow(String memoryPid) {
        if (memoryPid == null || memoryPid.isBlank()) return null;
        try {
            return jdbcTemplate.queryForMap(vectorRowSelect() + " WHERE pid=? AND "
                    + com.auraboot.framework.agent.memory.MemoryReadPolicy.LIVE, memoryPid);
        } catch (EmptyResultDataAccessException absent) { return null; }
    }

    /** Existing vectors are never silently rebuilt on a read; explicit computeAndStore is required. */
    public EmbeddingService.EmbeddedVector resolveVersionedEmbedding(String memoryPid) {
        Map<String, Object> row = loadVectorRow(memoryPid);
        if (row == null) return null;
        if (row.get("embedding_text") == null) {
            if (!computeAndStore(memoryPid)) return null;
            row = loadVectorRow(memoryPid);
            if (row == null) return null;
        }
        Long tenantId = ((Number) row.get("tenant_id")).longValue();
        String content = (String) row.get("memory_content");
        String currentSpace = embeddingService.currentSpace(tenantId, null, EXPECTED_DIM);
        if (content == null || currentSpace == null || !currentSpace.equals(row.get("embedding_space"))
                || !com.auraboot.framework.agent.memory.MemoryVectorPolicy.sha256(content).equals(row.get("embedding_content_hash"))) return null;
        double[] values = parseVectorLiteral((String) row.get("embedding_text"));
        if (values == null || values.length != EXPECTED_DIM) return null;
        float[] vector = new float[values.length];
        for (int i=0; i<values.length; i++) vector[i]=(float)values[i];
        return new EmbeddingService.EmbeddedVector(vector, currentSpace);
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
        var embedded = resolveVersionedEmbedding(memoryPid);
        if (embedded == null) return null;
        float[] values = embedded.values();
        double[] result = new double[values.length];
        for (int i=0; i<values.length; i++) result[i]=values[i];
        return result;
    }

    /**
     * Diagnostic raw-vector reader retained for compatibility. Do not use this value
     * for similarity: resolveVersionedEmbedding enforces identity and freshness.
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
